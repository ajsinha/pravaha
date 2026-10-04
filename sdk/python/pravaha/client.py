"""Asking Pravaha a question from Python.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

    from pravaha import connect

    with connect("grpc://localhost:19090") as client:
        for row in client.query("SELECT user_id, total FROM user_volume WHERE total > 100"):
            print(row["user_id"], row["total"])

The transport is Arrow Flight SQL (ADR-030), so this module is a thin wrapper over
``pyarrow.flight`` rather than an implementation of a wire protocol -- which is the
whole reason that protocol was chosen. What it adds is Pravaha's vocabulary:
endpoints, options and errors that match the Java SDK name for name, so a team
running both does not hold two mental models.

``pyarrow`` is an optional dependency, installed with the ``flight`` extra::

    pip install "pravaha[flight]"

Importing this module without it raises a message saying exactly that, rather than a
bare ImportError from three frames down.
"""

from __future__ import annotations

import dataclasses
import re
import threading
import time
from typing import TYPE_CHECKING, Any, Iterator, List, Optional, Sequence

from pravaha import tracecontext
from pravaha.api import EngineApi
from pravaha.debug import DebugCommands
from pravaha.endpoint import Endpoint
from pravaha.errors import PravahaError, configure_docs_base_from_environment, require_well_formed
from pravaha._wire import (
    _ACTION_ABANDON,
    _ACTION_BACKFILL,
    _ACTION_CUTOVER,
    _ACTION_DLQ_LIST,
    _ACTION_DLQ_REPLAY,
    _ACTION_DLQ_SHOW,
    _ACTION_DROP,
    _ACTION_FINISH,
    _ACTION_LIST,
    _ACTION_PAUSE,
    _ACTION_REGISTER,
    _ACTION_REPLACE,
    _ACTION_REPLACEMENT,
    _ACTION_RESUME,
    _ACTION_ROLLBACK,
    _WIRE_MAGIC,
    _WIRE_VERSION,
    _bind,
    _close_prepared_request,
    _create_prepared_request,
    _parse_doput_result,
    _parse_prepared_result,
    _prepared_command,
    _read_schema,
    _statement_command,
)
from pravaha._wire import _subscribe_ticket as _subscribe_ticket
from pravaha._wire import _wire_encode as _wire_encode
from pravaha.options import ClientOptions
from pravaha.rest import ApiError, RestClient
# The control protocol's answers as values (CONSOLESIZE-1 moved them); re-exported, because
# ``from pravaha.client import Replacement`` is how callers and the docs name them.
from pravaha.records import LIST_FIELDS as LIST_FIELDS
from pravaha.records import DeadLetter as DeadLetter
from pravaha.records import DeadLetterPage as DeadLetterPage
from pravaha.records import DeadLetterReplay as DeadLetterReplay
from pravaha.records import FeedStop as FeedStop
from pravaha.records import RegisteredQuery as RegisteredQuery
from pravaha.records import Replacement as Replacement
from pravaha.records import SinkFailure as SinkFailure
from pravaha.records import _at, _dead_letter_of, _int, _listed, _ordinals, _replacement
from pravaha.tls import TlsOptions

if TYPE_CHECKING:  # pyarrow is imported where it is used; this is for the annotations only.
    import pyarrow

try:  # pragma: no cover - exercised by the import-error path, not by the happy one
    import pyarrow.flight as _flight
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "the Pravaha client transport needs pyarrow. Install it with:\n"
        '    pip install "pravaha[flight]"\n'
        "It is optional because a client is installed into somebody else's environment, "
        "and every pin it adds is one their resolver has to reconcile."
    ) from exc


def _flight_client_tls_kwargs(tls: TlsOptions) -> dict[str, Any]:
    """Translates ``TlsOptions`` into the keyword arguments ``pyarrow.flight.FlightClient`` takes.

    ``FlightClient`` accepts only PEM bytes -- ``tls_root_certs``, ``cert_chain``,
    ``private_key`` -- with no keystore API of its own. A keystore is bridged to those
    bytes by :mod:`pravaha._keystore`, imported lazily here so that a client never
    touching a keystore never needs the ``cryptography`` package that bridge uses.
    """
    kwargs: dict[str, Any] = {}
    if tls.ca_certificate is not None:
        kwargs["tls_root_certs"] = tls.ca_certificate.read_bytes()
    elif tls.trust_store is not None:
        from pravaha._keystore import trusted_certificates_pem

        kwargs["tls_root_certs"] = trusted_certificates_pem(
            tls.trust_store, tls.trust_store_password or "", tls.trust_store_type
        )
    if tls.client_certificate is not None:
        # client_key is guaranteed present too: TlsOptions refuses one without the other.
        kwargs["cert_chain"] = tls.client_certificate.read_bytes()
        assert tls.client_key is not None  # TlsOptions refuses a certificate without its key
        kwargs["private_key"] = tls.client_key.read_bytes()
    elif tls.key_store is not None:
        from pravaha._keystore import client_certificate_and_key_pem

        cert_pem, key_pem = client_certificate_and_key_pem(
            tls.key_store, tls.key_store_password or "", tls.key_store_type
        )
        kwargs["cert_chain"] = cert_pem
        kwargs["private_key"] = key_pem
    if tls.override_hostname is not None:
        kwargs["override_hostname"] = tls.override_hostname
    if tls.disable_hostname_verification:
        # pyarrow's own name for "disable all server verification". TlsOptions refuses
        # combining this with certificate material, mirroring the Java SDK, so this is
        # never reached alongside tls_root_certs/cert_chain/private_key above.
        kwargs["disable_server_verification"] = True
    return kwargs


class QueryError(PravahaError):
    """The server refused a query, carrying its own ``PRV-nnnn`` diagnosis.

    The message is the server's, not a wrapper's: "PRV-4023 ... this server serves
    ['user_volume']" tells somebody what to do and "query failed" does not.

    The code matches the Java SDK's ``ClientErrors.QUERY_REFUSED`` deliberately. A team
    running both languages should find one page per code, not two.
    """

    def __init__(self, message: str) -> None:
        server = _server_words(message)
        super().__init__(1041, server)
        #: The server's own words, without the ``PRV-1041`` prefix ``str()`` adds and without
        #: the transport's wrapping -- pyarrow's "Flight returned ... with message:" in front and
        #: gRPC's debug context behind, which name a peer address and say nothing to act on.
        self.message = server
        #: The engine's own code, ``"PRV-8002"`` for an unknown filter column, or ``None`` when the
        #: failure carried none. The same attribute :class:`pravaha.rest.ApiError` has, so a caller
        #: branches on the engine's diagnosis the same way whichever protocol refused: ``code`` is
        #: this client's 1041 on both, because the Java SDK's ``QUERY_REFUSED`` is 1041 too.
        self.engine_code = _engine_code_of(server)


_FLIGHT_PREFIX = re.compile(r"^.*?Flight returned [^,]*, with message: ", re.S)
_GRPC_CONTEXT = re.compile(r"\.? ?gRPC client debug context:.*$", re.S)
_ENGINE_CODE = re.compile(r"\bPRV-(\d{4})\b")


def _server_words(message: str) -> str:
    """The server's sentence out of pyarrow's rendering of a Flight status."""
    text = _GRPC_CONTEXT.sub("", _FLIGHT_PREFIX.sub("", message or "")).rstrip()
    return text or (message or "")


def _engine_code_of(text: str) -> "str | None":
    """The first engine code in the server's words, other than this client's own 1041."""
    for match in _ENGINE_CODE.finditer(text):
        if match.group(1) != "1041":
            return "PRV-" + match.group(1)
    return None


class ReadError(PravahaError):
    """A result could not be read, or was read twice."""

    def __init__(self, message: str) -> None:
        super().__init__(1042, message)


class ConnectError(PravahaError):
    """The server could not be reached. Worth retrying: a server may come back."""

    def __init__(self, message: str) -> None:
        super().__init__(1040, message, retryable=True)


class DeadlineExceededError(PravahaError):
    """A call was not answered within ``ClientOptions.request_timeout_seconds``.

    Worth retrying: nothing was refused, and a node that is slow now may not be in a moment.
    The same ``PRV-1045`` as the Java SDK's ``ClientErrors.DEADLINE_EXCEEDED`` (SDKDEADLINE-1).
    The message names the call and the deadline, because "timed out" alone says neither which
    of several calls it was nor which setting to raise.
    """

    def __init__(self, call: str, uri: str, deadline: float) -> None:
        super().__init__(
            1045,
            f"{call} was not answered by {uri} within the {deadline:g} s deadline "
            "(request_timeout_seconds). The node is slow, stalled or overloaded; retry, or raise "
            "request_timeout_seconds if the request is known to take longer",
            retryable=True,
        )
        #: What was asked -- ``"query (planning)"``, ``"action ListQueries"``, ``"subscribe(v)"``.
        self.call = call
        #: The deadline it was given, in seconds.
        self.deadline = deadline


class Row:
    """One row of an answer.

    Indexable by column name or position, because both are natural in Python and
    neither is worth forcing::

        row["total"]    row[2]    row.get("tier")    row.to_dict()

    A row is a snapshot of its values rather than a cursor. Python's iteration
    protocol makes a reused cursor genuinely surprising -- ``list(result)`` would
    return the same row N times -- so the Python SDK copies where the Java one does
    not, and pays an allocation per row for it. ``to_table()`` is the way out for
    anybody who cares.
    """

    __slots__ = ("_columns", "_values", "_weight")

    def __init__(
        self, columns: Sequence[str], values: Sequence[Any], weight: int = 1
    ) -> None:
        self._columns = columns
        self._values = values
        self._weight = weight

    def __getitem__(self, key: Any) -> Any:
        if isinstance(key, int):
            return self._values[key]
        try:
            return self._values[self._columns.index(key)]
        except ValueError:
            raise KeyError(
                f"no column {key!r} in this result; it has {list(self._columns)}"
            ) from None

    def get(self, key: str, default: Any = None) -> Any:
        """The column, or ``default`` when it is absent -- not when it is null."""
        try:
            return self[key]
        except KeyError:
            return default

    def is_null(self, key: Any) -> bool:
        return self[key] is None

    @property
    def columns(self) -> Sequence[str]:
        return self._columns

    @property
    def weight(self) -> int:
        """How this row changes the view: ``+1`` appearing, ``-1`` being withdrawn.

        A correction arrives as a retraction of the old row followed by an insert of the
        new one, so a consumer keeping its own running total must add the weight rather
        than count rows -- the retraction is what cancels the value being corrected. A
        consumer that only wants the current state can overwrite by key and skip
        negatives.

        The weights are the view's changelog, and for a keyed view that upserts -- a second
        row under a key replacing the first -- the replaced row gets no ``-1`` (KEYEDWT-1):
        summed, they count that key twice. Subscribe to a query registered over the view to
        receive its answer's changes instead; those sum to exactly what a reader sees.

        An ordinary query answer has no weights: every row in it is a row that is
        present, so this is ``1`` there. Only a subscription carries real ones.
        """
        return self._weight

    @property
    def is_retraction(self) -> bool:
        """Whether this withdraws a row rather than adding one."""
        return self._weight < 0

    def to_dict(self) -> dict[str, Any]:
        return dict(zip(self._columns, self._values))

    def __len__(self) -> int:
        return len(self._values)

    def __iter__(self) -> Iterator[Any]:
        return iter(self._values)

    def __eq__(self, other: object) -> bool:
        if isinstance(other, Row):
            return self._columns == other._columns and self._values == other._values
        if isinstance(other, dict):
            return self.to_dict() == other
        return NotImplemented

    def __repr__(self) -> str:
        return f"Row({self.to_dict()!r})"


_WEIGHT_METADATA_KEY = b"pravaha.weight"


def _weight_column_of(schema: Any) -> int:
    """The ordinal of the subscription weight column, or -1 when there is not one."""
    for ordinal in range(len(schema)):
        metadata = schema.field(ordinal).metadata
        if metadata and metadata.get(_WEIGHT_METADATA_KEY) == b"true":
            return ordinal
    return -1


def _weighted_rows_of(table: Any) -> list[Row]:
    """One commit's Arrow batch as rows, with the weight lifted off the wire.

    The weight column is found by its metadata mark, not its name. A view may select a
    column called whatever the engine's happens to be called, and a subscriber that
    guessed by name would read that column's values as weights -- and would also hand
    the caller a column it never selected.
    """
    weight_at = _weight_column_of(table.schema)
    ordinals = [i for i in range(table.num_columns) if i != weight_at]
    columns = [table.schema.names[i] for i in ordinals]
    weights = None if weight_at < 0 else table.column(weight_at)
    return [
        Row(
            columns,
            [table.column(i)[r].as_py() for i in ordinals],
            1 if weights is None else weights[r].as_py(),
        )
        for r in range(table.num_rows)
    ]

class ChangeBatch(List[Any]):
    """One commit's rows -- or, first on a snapshot subscription, the view it starts from.

    A ``list`` of :class:`Row`, so code written for plain subscriptions, which iterates or
    indexes each batch, reads it unchanged. Two attributes say what it is:

    ``snapshot``
        True for the first batch of ``subscribe(..., snapshot=True)``: every row of the view
        at a commit, each with its multiplicity as its weight, delivered even when empty.
    ``frontier``
        The committed frontier the batch brings the view to, or ``None`` on a plain
        subscription, whose server does not say.
    ``dropped_before``
        How many whole commits this subscription has lost before this batch, cumulative
        (STRM-10). Non-zero means the rows you hold are not the view. A plain subscription
        drops whole commits when it falls behind -- deliberately, so one slow client cannot
        slow the query -- and the count used to reach an audit sink and never the client, so
        a dashboard that had lost 98 % of its changes looked exactly like one that had not.
    """

    def __init__(self, rows: "Sequence[Row]", *, snapshot: bool = False,
                 frontier: Optional[int] = None, dropped_before: int = 0,
                 reconnected: bool = False) -> None:
        super().__init__(rows)
        self.snapshot = snapshot
        self.frontier = frontier
        self.dropped_before = dropped_before
        #: True on the first batch after ``subscribe(..., reconnect=True)`` opened the stream
        #: again. With ``snapshot=True`` that batch is a fresh snapshot: replace what you hold
        #: with it. Without, commits made while the stream was down were not delivered.
        self.reconnected = reconnected

    def missed_anything(self) -> bool:
        """Whether anything was lost before this batch."""
        return self.dropped_before > 0

    def __repr__(self) -> str:
        kind = "snapshot" if self.snapshot else "commit"
        lost = f", dropped_before={self.dropped_before}" if self.dropped_before else ""
        return f"ChangeBatch({kind}, frontier={self.frontier}{lost}, rows={list.__repr__(self)})"


_MARK_PREFIX = "pravaha:"
_MARK_SNAPSHOT = "snapshot"
_MARK_SNAPSHOT_END = "snapshot-end"

# What the server sends for "this batch has no frontier", which is every plain subscription's.
_NO_FRONTIER = -(2 ** 63)


def _mark_of(metadata: Any) -> "Optional[tuple[str, int, int]]":
    """A batch mark, ``pravaha:<kind>:<frontier>[:<dropped>]``; None when absent.

    Anything in the metadata that is not one of ours reads as no mark, rather than as a
    failure. Split on every colon rather than on the last one: a fourth component moved
    where "the last colon" is, and reading ``commit:42`` as a kind is the sort of thing that
    decodes to a plausible wrong answer instead of to nothing.
    """
    if metadata is None:
        return None
    raw = metadata.to_pybytes() if hasattr(metadata, "to_pybytes") else bytes(metadata)
    if not raw:
        return None
    text = raw.decode("utf-8", errors="replace")
    if not text.startswith(_MARK_PREFIX):
        return None
    parts = text[len(_MARK_PREFIX):].split(":")
    if len(parts) < 2 or not parts[0]:
        return None
    try:
        return parts[0], int(parts[1]), int(parts[2]) if len(parts) > 2 else 0
    except ValueError:
        return None


class QueryResult:
    """An answer, iterated as it arrives.

    Streamed rather than materialised: the server sends bounded batches and this walks
    them, so reading a million rows holds one batch rather than a million rows.

    For anybody who wants the whole thing at once -- which in Python is most people --
    ``to_table()`` hands back a ``pyarrow.Table``, and from there ``to_pandas()`` or
    ``polars.from_arrow()`` are one call away with no conversion in between. That path
    is why the wire format is Arrow.
    """

    def __init__(self, reader: Any) -> None:
        self._reader = reader
        self._schema = reader.schema
        self._columns = list(self._schema.names)
        self._consumed = False

    @property
    def columns(self) -> list[Any]:
        """The column names, known before any row arrives."""
        return list(self._columns)

    @property
    def schema(self) -> Any:
        """The Arrow schema of the answer."""
        return self._schema

    def __iter__(self) -> Iterator[Row]:
        if self._consumed:
            raise ReadError(
                "this result has already been read. A stream is consumed once; call "
                "to_table() first if you need the rows more than once."
            )
        self._consumed = True
        for batch in self._reader:
            data = batch.data if hasattr(batch, "data") else batch
            for values in zip(*[column.to_pylist() for column in data.columns]):
                yield Row(self._columns, list(values))

    def to_table(self) -> Any:
        """Everything, as a ``pyarrow.Table``. The path to pandas and Polars."""
        if self._consumed:
            raise ReadError("this result has already been read")
        self._consumed = True
        return self._reader.read_all()

    def to_list(self) -> list[Any]:
        """Everything, as a list of dicts. For small answers and quick scripts."""
        return [row.to_dict() for row in self]


class Client(DebugCommands):
    """A connection to a Pravaha server.

    Reads are consistent: a query sees each view as of its last committed frontier,
    which is the only mode under which two views agree on the same prefix of the input
    and the one a person acting on the answer should have. Per-query consistency is
    declared on the wire and is the next piece of this work; a knob here before the
    server honours it would be a knob that does nothing.
    """

    def __init__(self, options: ClientOptions) -> None:
        self._options = options
        # DOCX-21. The help-page base, read once while the client is being built, so a
        # value that is not a URL is refused here rather than pasted onto a code inside
        # the report of some later failure. Unset is the default and means no URL.
        configure_docs_base_from_environment()
        node = options.endpoint.nodes[0]
        scheme = "grpc+tls" if options.endpoint.tls else "grpc"
        self._uri = f"{scheme}://{node.host}:{node.port}"
        # Held rather than sent once at connect time because Flight has no session: each
        # call is authenticated on its own, which is what lets a server behind a load
        # balancer answer without the balancer pinning a client to a node.
        self._auth_headers: list[tuple[bytes, bytes]] = (
            [(b"authorization", f"Bearer {options.token}".encode())] if options.token else []
        )
        self._api: Optional[EngineApi] = None
        kwargs = _flight_client_tls_kwargs(options.tls) if options.endpoint.tls else {}
        try:
            self._client = _flight.FlightClient(self._uri, **kwargs)
        except Exception as exc:  # pragma: no cover - network failure shape varies
            raise ConnectError(f"cannot connect to {self._uri}: {exc}") from exc

    @property
    def uri(self) -> str:
        return self._uri

    @property
    def _call_options(self) -> Any:
        """The options each Flight call is made with: the bearer token, and the caller's W3C trace
        context when there is one (:mod:`pravaha.tracecontext`), so a node that traces continues the
        caller's trace. Built per call because the trace is the caller's current one."""
        traced = [(k.encode(), v.encode()) for k, v in tracecontext.headers().items()]
        headers = self._auth_headers + traced
        return _flight.FlightCallOptions(headers=headers) if headers else _flight.FlightCallOptions()

    @property
    def _unary_options(self) -> Any:
        """:attr:`_call_options` with ``request_timeout_seconds`` as the call's deadline: what every
        unary call -- planning, preparing, binding, every action -- is made with (SDKDEADLINE-1).

        Streams are not given it. gRPC's deadline bounds a whole call, and a subscription is a call
        meant to run for hours; a stream's *opening* is bounded instead, by :meth:`_open_stream`.
        Before this, ``request_timeout_seconds`` reached only the HTTP API, and a node that accepted
        a Flight call and never answered held its caller for ever."""
        traced = [(k.encode(), v.encode()) for k, v in tracecontext.headers().items()]
        headers = self._auth_headers + traced
        return _flight.FlightCallOptions(
            headers=headers, timeout=self._options.request_timeout_seconds
        )

    def _deadline_or_failure(self, exc: Exception, call: str) -> PravahaError:
        """:func:`_failure`, except that a call this client gave up on is the deadline it was."""
        timed_out = getattr(_flight, "FlightTimedOutError", None)
        if timed_out is not None and isinstance(exc, timed_out):
            if _engine_code_of(_server_words(_message_of(exc))) is None:
                return DeadlineExceededError(call, self._uri, self._options.request_timeout_seconds)
        return _failure(exc)

    def _open_stream(self, ticket: Any, call: str) -> Any:
        """``do_get``, its opening bounded by ``request_timeout_seconds`` and its reading not.

        pyarrow's ``do_get`` returns once the server has sent the schema, and a deadline on it
        would bound the whole stream -- wrong for a subscription, and for a large answer read
        slowly. So the opening runs on a helper thread and is waited for with the deadline; if
        the server has not answered by then, the caller gets :class:`DeadlineExceededError`, and
        a stream that does open afterwards is cancelled, since nobody is waiting for it.
        """
        options = self._call_options  # built here: the trace context is the caller's thread's
        lock = threading.Lock()
        opened: dict[str, Any] = {}
        abandoned = False
        finished = threading.Event()

        def open_it() -> None:
            try:
                reader = self._client.do_get(ticket, options)
            except BaseException as exc:  # handed to the waiting caller, never lost
                opened["error"] = exc
            else:
                with lock:
                    if abandoned:
                        try:
                            reader.cancel()
                        except Exception:  # pragma: no cover - already gone
                            pass
                        return
                    opened["reader"] = reader
            finally:
                finished.set()

        threading.Thread(target=open_it, name="pravaha-open", daemon=True).start()
        if not finished.wait(self._options.request_timeout_seconds):
            with lock:
                if "reader" not in opened and "error" not in opened:
                    abandoned = True
            if abandoned:
                raise DeadlineExceededError(call, self._uri, self._options.request_timeout_seconds)
            finished.wait()
        if "error" in opened:
            error = opened["error"]
            raise _failure(error) from error
        return opened["reader"]

    def query(self, sql: str, parameters: Optional[Sequence[object]] = None) -> QueryResult:
        """Runs one query and returns its rows.

        Pass ``parameters`` to bind values to the ``?`` placeholders in ``sql``::

            client.query("SELECT total FROM user_volume WHERE user_id = ?", ["u1"])

        Prefer that to building the SQL string yourself. A bound value can never be
        read as SQL -- by the time it reaches the server the statement is already
        planned, and there is no parser left for it to reach -- and the server plans a
        statement once and reuses the plan, so two callers asking the same question
        about different users share the work rather than each paying for it.

        Any value may be ``None``. What that *means* is SQL's business: ``WHERE x = ?``
        bound to ``None`` matches no rows, because a comparison with NULL is UNKNOWN.
        ``IS NULL`` is what finds the empty ones.
        """
        # Refused here, not sent: the server would never see what was written (SDK-1).
        require_well_formed(sql, "the SQL")
        if parameters:
            return self._query_with_parameters(sql, parameters)
        try:
            descriptor = _flight.FlightDescriptor.for_command(_statement_command(sql))
            info = self._client.get_flight_info(descriptor, self._unary_options)
        except Exception as exc:
            raise self._deadline_or_failure(exc, "query (planning)") from exc
        return QueryResult(self._open_stream(info.endpoints[0].ticket, "query (opening its result)"))

    def _query_with_parameters(self, sql: str, parameters: Sequence[object]) -> QueryResult:
        """Prepare, bind, fetch.

        Four round trips, and the handle may be rewritten in the middle: the server
        keeps no session, so when parameters are bound it hands back a *new* handle
        that carries them, and the fetch uses that one. Doing it this way means a
        client can be answered by any node and can come back after a restart.
        """
        try:
            try:
                handle, parameter_schema = self._prepare(sql)
            except Exception as exc:
                raise self._deadline_or_failure(exc, "query (preparing)") from exc
            try:
                batch = _bind(parameter_schema, parameters)
                try:
                    handle = self._put_parameters(handle, batch)
                    descriptor = _flight.FlightDescriptor.for_command(_prepared_command(handle))
                    info = self._client.get_flight_info(descriptor, self._unary_options)
                except Exception as exc:
                    raise self._deadline_or_failure(exc, "query (binding and planning)") from exc
                reader = self._open_stream(info.endpoints[0].ticket, "query (opening its result)")
            finally:
                self._close_prepared(handle)
        except (PravahaError, ValueError):
            raise
        except _flight.FlightError as exc:
            raise _failure(exc) from exc
        except Exception as exc:
            raise _failure(exc) from exc
        return QueryResult(reader)

    def _prepare(self, sql: str) -> tuple[bytes, "pyarrow.Schema"]:
        action = _flight.Action("CreatePreparedStatement", _create_prepared_request(sql))
        results = list(self._client.do_action(action, self._unary_options))
        if not results:
            raise QueryError("the server did not return a prepared statement")
        handle, _dataset, parameter_schema_bytes = _parse_prepared_result(results[0].body.to_pybytes())
        return handle, _read_schema(parameter_schema_bytes)

    def _put_parameters(self, handle: bytes, batch: "pyarrow.RecordBatch") -> bytes:
        descriptor = _flight.FlightDescriptor.for_command(_prepared_command(handle))
        writer, reader = self._client.do_put(descriptor, batch.schema, self._unary_options)
        with writer:
            writer.write_batch(batch)
            writer.done_writing()
            metadata = reader.read()
        if metadata is None:
            # A server that keeps its own session would not send one back; ours does,
            # and using the old handle then would fetch rows for an unbound statement.
            return handle
        return _parse_doput_result(metadata.to_pybytes()) or handle

    def _close_prepared(self, handle: bytes) -> None:
        try:
            action = _flight.Action("ClosePreparedStatement", _close_prepared_request(handle))
            list(self._client.do_action(action, self._unary_options))
        except Exception:  # pragma: no cover - closing is best effort
            # The server holds nothing, so a failure here costs nothing. Letting it
            # propagate would replace a good result with an error about tidying up.
            pass

    # ---------------------------------------------------------------------------------
    # Continuous queries: registering them, and subscribing to what they produce.
    # ---------------------------------------------------------------------------------

    def register(
        self,
        name: str,
        sql: str,
        key_columns: Sequence[int],
        sink: str | None = None,
        retention: str | None = None,
    ) -> "RegisteredQuery":
        """Registers a continuous query and returns what the server made of it.

        A registration is not a request -- it is a computation that keeps running and
        keeps a view current until somebody drops it::

            client.register("trade_feed", open("sql/01-continuous-trade-feed.sql").read(), [0])

        Registering the same question twice, even worded differently, gives one
        computation with two names: the server matches on the normalised plan rather
        than the text. The returned fingerprint is how you can tell.

        ``sink`` names a binding under the server's ``pravaha.sinks``; the query's
        changes are then written there as well as to its view, retractions included,
        at least once. The server refuses the pair before anything runs when the query
        revises its answer and the sink can only append (``PRV-2041``).

        ``retention`` is how much event time the view keeps: an ISO-8601 duration such as
        ``"PT24H"`` or ``"P7D"``, or ``"forever"``; ``None`` takes the server's default. A
        server that cannot read it refuses the registration rather than keeping a
        different amount than was asked for.
        """
        ordinals = ",".join(str(int(c)) for c in key_columns)
        # Trailing fields are optional on the wire, so only what is set is sent: a server
        # that predates retention answers a four-field registration exactly as before.
        fields = [name, sql, ordinals]
        if retention:
            fields += [sink or "", retention.strip()]
        elif sink:
            fields += [sink]
        rows = self._act(_ACTION_REGISTER, fields)
        if not rows:
            raise QueryError("the server accepted the registration but said nothing about it")
        row = rows[0]
        return RegisteredQuery(
            name=_at(row, 0),
            state=_at(row, 1),
            sql=sql,
            fingerprint=_at(row, 2),
            rows_in=0,
            key_columns=tuple(int(c) for c in key_columns),
            sink=sink or None,
            retention=retention.strip() if retention else None,
        )

    def queries(self) -> "list[RegisteredQuery]":
        """Every continuous query this server is running."""
        out = []
        for row in self._act(_ACTION_LIST, []):
            try:
                rows_in = int(_listed(row, "rows_in") or 0)
            except ValueError:
                rows_in = 0
            # Fields 5-7 were added after the first five and trail them, so a server that
            # predates them sends five and these read as "unknown": an empty key, no sink,
            # no retention. 8-12 are the feed (FEED-1), trailing for the same reason.
            code = _listed(row, "feed_code")
            # 13-15 are the sink's own state (SINK-3): ATTACHED, DETACHED or NONE, and the
            # code and message it was detached with. Empty from a server that predates them.
            sink_code = _listed(row, "sink_code")
            out.append(
                RegisteredQuery(
                    name=_listed(row, "name"),
                    state=_listed(row, "state"),
                    sql=_listed(row, "sql"),
                    fingerprint=_listed(row, "fingerprint"),
                    rows_in=rows_in,
                    key_columns=_ordinals(_listed(row, "key_ordinals")),
                    sink=_listed(row, "sink") or None,
                    retention=_listed(row, "retention") or None,
                    feed=_listed(row, "feed_state") or None,
                    feed_stop=(FeedStop(code=code, message=_listed(row, "feed_message"), where=_listed(row, "feed_where"),
                                        at=_listed(row, "feed_at")) if code else None),
                    sink_state=_listed(row, "sink_state") or None,
                    sink_failure=(SinkFailure(code=sink_code, message=_listed(row, "sink_message"))
                                  if sink_code else None),
                    # 16 is the owner (who may administer it without a grant); empty from an older server.
                    owner=_listed(row, "owner") or None,
                )
            )
        return out

    def dead_letters(self, name: str, *, offset: int = 0, limit: int = 50) -> "DeadLetterPage":
        """A page of the records this query's feed could not decode, newest first.

        Newest first and no other order offered: a queue is read because something has just
        started failing, and the entries that answer "what is happening now" are at the end
        of the file.

        An entry's ``raw`` may be empty with ``withheld`` saying why -- your access to the
        view is row-filtered, and a record that failed to decode has no row for that filter
        to be applied to. The count, the offset and the code are not withheld.
        """
        entries: "list[DeadLetter]" = []
        totals: Optional[DeadLetterPage] = None
        for row in self._act(_ACTION_DLQ_LIST, [name, str(offset), str(limit)]):
            # A "#" in the first field is the trailer carrying the queue's totals. An id is a
            # UUID, so it can never be "#", and a client tells them apart without being told
            # how many entries to expect.
            if _at(row, 0) == "#":
                totals = DeadLetterPage(
                    query=name,
                    entries=(),
                    offset=offset,
                    total=_int(_at(row, 1)),
                    bytes=_int(_at(row, 2)),
                    evicted=_int(_at(row, 3)),
                    evicted_bytes=_int(_at(row, 4)),
                    replayed=_int(_at(row, 5)),
                    failed_again=_int(_at(row, 6)),
                    retention=_at(row, 7),
                    configured=_at(row, 8) == "true",
                )
                continue
            entries.append(_dead_letter_of(row))
        if totals is None:
            # A server that predates the trailer. The page is still a page; the totals it could
            # not report are what the page itself shows.
            return DeadLetterPage(
                query=name,
                entries=tuple(entries),
                offset=offset,
                total=offset + len(entries),
                bytes=0,
                evicted=0,
                evicted_bytes=0,
                replayed=0,
                failed_again=0,
                retention="unknown",
                configured=True,
            )
        return dataclasses.replace(totals, entries=tuple(entries))

    def dead_letter(self, name: str, letter_id: str) -> "DeadLetter":
        """One dead letter whole, by its id.

        Raises :class:`QueryError` carrying ``PRV-4091`` when no entry with that id is in the
        queue -- the id is wrong, the page it came from is stale, or retention evicted it.
        """
        rows = self._act(_ACTION_DLQ_SHOW, [name, letter_id])
        if not rows:
            raise QueryError("the server answered pravaha.dlq.show with no result")
        return _dead_letter_of(rows[0])

    def replay_dead_letters(self, name: str, ids: Sequence[str]) -> "list[DeadLetterReplay]":
        """Feeds chosen dead letters back through the query that rejected them.

        **A new row at the query's current frontier, not a rewind.** Nothing is re-read, no
        offset moves, and no earlier answer is recomputed. A record that fails to decode again
        goes back on the queue as a fresh entry -- named in :attr:`DeadLetterReplay.new_id` --
        and is not retried, so a caller walking a queue moves forwards through it.

        Not idempotent: replaying the same id twice puts the row in twice.
        """
        chosen = [i.strip() for i in ids if i and i.strip()]
        if not chosen:
            raise ValueError(
                "say which dead letters to replay; replaying a whole queue by omission is not "
                "offered, because a queue is usually a mix of causes"
            )
        return [
            DeadLetterReplay(
                id=_at(row, 0), outcome=_at(row, 1), detail=_at(row, 2), new_id=_at(row, 3)
            )
            for row in self._act(_ACTION_DLQ_REPLAY, [name, *chosen])
        ]

    def replay_dead_letter(self, name: str, letter_id: str) -> "DeadLetterReplay":
        """Feeds one dead letter back through the query."""
        return self.replay_dead_letters(name, [letter_id])[0]

    def pause(self, name: str) -> None:
        """Stops a query without releasing it; its view keeps answering where it reached."""
        self._act(_ACTION_PAUSE, [name])

    def resume(self, name: str) -> None:
        self._act(_ACTION_RESUME, [name])

    def drop(self, name: str) -> None:
        """Removes a name.

        The computation goes when its *last* name goes. If somebody else registered the
        same question, dropping yours leaves theirs running -- which is the point:
        neither of you knows the other exists.
        """
        self._act(_ACTION_DROP, [name])

    # ---------------------------------------------------------------------------------
    # Blue/green replacement: a new version beside the running one, backfilled, cut over
    # to at a position both have consumed exactly, and rolled back from (ADR-046).
    # ---------------------------------------------------------------------------------

    def replace(
        self,
        name: str,
        sql: str,
        key_columns: Sequence[int],
        *,
        backfill: str | None = None,
        rate_limit: int | None = None,
        cutover: str | None = None,
        rollback_retention: str | None = None,
    ) -> "Replacement":
        """Starts replacing ``name`` with a new version, and says where that has got to.

        The name goes on answering the version it answers now. What this starts is a
        shadow: it reads the same sources from the beginning, splices onto the live stream
        at the position the running version has reached, and is compared with it.
        :meth:`cut_over` is what moves the name, and only when the two have consumed
        exactly the same input -- so a reader sees the old answer up to the seam and the
        new one after it, with no gap and nothing counted twice.

        Requires the administer permission on the name, as dropping it does.

        ``backfill`` is ``"history"`` (the default: replay it) or ``"none"`` (start where
        the running version is, with empty state -- correct only for a query whose answer
        does not depend on history). ``rate_limit`` is a ceiling in records a second, and
        the one an operator may lower while it runs and may not raise. ``cutover`` is
        ``"manual"`` (the default) or ``"auto"``. An option this engine does not build is
        refused by name with ``PRV-4018`` rather than ignored.
        """
        options = []
        if backfill is not None:
            options.append(f"backfill={backfill}")
        if rate_limit is not None:
            options.append(f"backfill.rate.limit={int(rate_limit)}")
        if cutover is not None:
            options.append(f"cutover={cutover}")
        if rollback_retention is not None:
            options.append(f"rollback.retention={rollback_retention}")
        ordinals = ",".join(str(int(c)) for c in key_columns)
        fields = [name, sql, ordinals]
        if options:
            fields.append(";".join(options))
        return _one_replacement(self._act(_ACTION_REPLACE, fields), name)

    def replacement(self, name: str) -> "Optional[Replacement]":
        """How the replacement of ``name`` is getting on, or ``None`` when there is not one."""
        rows = self._act(_ACTION_REPLACEMENT, [name])
        return _replacement(rows[0]) if rows else None

    def replacements(self) -> "list[Replacement]":
        """Every replacement this server knows about, in flight or finished."""
        return [_replacement(row) for row in self._act(_ACTION_REPLACEMENT, [])]

    def cut_over(self, name: str) -> "Replacement":
        """Moves the name to the new version.

        Refused with ``PRV-4014`` when it has not caught up, or when the two versions
        cannot be brought to the same position in their input: a cutover at different
        positions would leave the records between them in neither version's output, or in
        both. Every subscription to the name ends with ``PRV-4019`` -- subscribe again,
        and a snapshot subscription starts from a fresh snapshot of the new version.
        """
        return _one_replacement(self._act(_ACTION_CUTOVER, [name]), name)

    def roll_back(self, name: str) -> "Replacement":
        """Puts the replaced version back, while it is still retained."""
        return _one_replacement(self._act(_ACTION_ROLLBACK, [name]), name)

    def abandon_replacement(self, name: str) -> "Replacement":
        """Ends a replacement that has not cut over, releasing the candidate."""
        return _one_replacement(self._act(_ACTION_ABANDON, [name]), name)

    def finish_replacement(self, name: str) -> "Replacement":
        """Confirms a cutover: the replaced version is released, and there is no rollback."""
        return _one_replacement(self._act(_ACTION_FINISH, [name]), name)

    def throttle_backfill(self, name: str, records_per_second: int) -> "Replacement":
        """Sets how fast the backfill reads history, up to the ceiling it was started with."""
        return _one_replacement(
            self._act(_ACTION_BACKFILL, [name, "throttle", str(int(records_per_second))]), name
        )

    def pause_backfill(self, name: str) -> "Replacement":
        """Stops the backfill reading, without giving up what it has read."""
        return _one_replacement(self._act(_ACTION_BACKFILL, [name, "pause"]), name)

    def resume_backfill(self, name: str) -> "Replacement":
        return _one_replacement(self._act(_ACTION_BACKFILL, [name, "resume"]), name)

    def subscribe(
        self,
        view: str,
        filters: Optional[dict[str, Any]] = None,
        *,
        batch_size_hint: Optional[int] = None,
        snapshot: bool = False,
        buffer_rows: Optional[int] = None,
        overflow: Optional[str] = None,
        reconnect: bool = False,
        reconnect_timeout: Optional[float] = 300.0,
        changes: str = "changelog",
    ) -> Iterator[ChangeBatch]:
        """Yields one list of rows per commit, for as long as you keep iterating.

            for batch in client.subscribe("trade_feed", {"product_type": "SWAP"}):
                for row in batch:
                    handle(row["trade_json"])

        A batch is a **commit**, not an arbitrary chunk. Between commits the view holds a
        half-applied window, so a consumer woken per row could act on a total that was
        still being assembled.

        ``filters`` are applied at the tap, so rows you did not ask for never cross the
        network. Equality only, and a column the view does not have is refused rather
        than ignored -- a filter quietly dropped would leave you receiving everything
        while believing you had asked for a slice.

        Each row carries a ``weight``: ``+1`` for a row appearing, ``-1`` for one being
        withdrawn. A window corrected by late data arrives as a retraction of the old row
        followed by an insert of the new one, so a consumer maintaining its own total must
        apply ``row.weight`` rather than count rows.

        This is a generator and it does not end on its own: stop iterating, or close the
        client, when you have had enough.

        **Keeping a copy of a view? Pass** ``snapshot=True``. A plain subscription starts at
        the next commit and says nothing of what the view already holds, and reading the view
        beside it does not close the gap: subscribe-then-read and read-then-subscribe can
        both lose the commit in flight at that moment, silently (SUB-1). With
        ``snapshot=True`` the first batch has ``batch.snapshot`` set and holds every row of
        the view at a commit, each with its multiplicity as its weight -- sent even when
        there are none -- and every batch after it is a commit after that one, so adding
        weights gives the view's Z-set with nothing missed and nothing counted twice. For a
        keyed view that upserts (the latest row per key over a stream that only inserts) that
        is not what a reader sees: a replaced row arrives with no ``-1`` (KEYEDWT-1), so pass
        ``changes="answer"`` as well (below). A subscriber
        that falls too far behind has its stream ended with ``PRV-6105`` rather than skipped
        past a commit; subscribe again to start from a fresh snapshot. A server older than
        this SDK refuses ``snapshot=True`` with ``PRV-6102``.

        **Falling behind.** ``buffer_rows`` and ``overflow`` say what the server's own
        buffer should do when you cannot keep up (STRM-16): ``"CONFLATE"`` keeps the latest
        value per key, ``"DROP_OLDEST"`` keeps the newest changes, and ``"FAIL"`` ends this
        subscription rather than lose a change -- which is what anything maintaining its own
        total from the weights should ask for, because conflating drops the intermediate
        weights that total is built from. Left unset, the server's default applies, which is
        ``(10000, CONFLATE)``; until this was carried on the ticket it was the *only* thing a
        remote subscriber could have. Whatever is lost is reported on each batch as
        ``batch.dropped_before`` (STRM-10).

        **The changelog or the answer.** ``changes="changelog"`` (the default) is what the
        query applied to its view, weights verbatim. ``changes="answer"`` is how the view's
        answer moved at each commit: the rows a reader stopped seeing at ``-1`` and the rows a
        reader started seeing at ``+1`` -- so the weights sum to exactly the rows the view
        shows, through upserts and retention, which a keyed view's changelog does not
        (SUBANSWERWIRE-1). With ``snapshot=True`` the first batch is each row a reader sees,
        once. A server older than this SDK refuses ``changes="answer"`` as a ticket it does not
        know.

        **Surviving a restart.** A server that restarts ends every stream it serves. By default
        that ends this generator with :class:`ConnectError` (``PRV-1040``, retryable) or simply
        ends it. With ``reconnect=True`` the subscription opens itself again instead: after the
        stream ends, after a retryable failure, and after ``PRV-6105`` (fell too far behind),
        retrying with backoff from 0.25 s up to 10 s between attempts, for at most
        ``reconnect_timeout`` seconds without a stream open (``None``: for ever). A refusal that
        will not change -- the view was dropped, a filter names no column -- is raised at once.
        The first batch after reopening has ``batch.reconnected`` set. Pair it with
        ``snapshot=True``: that batch is then a fresh snapshot of the view, so replacing what you
        hold with it loses nothing. A plain subscription resumes at the next commit, and what was
        committed while it was down is not delivered.
        """
        if changes not in ("changelog", "answer"):
            raise QueryError(
                f"changes is 'changelog' (what the query applied) or 'answer' (how the view's "
                f"answer moved), not {changes!r}"
            )
        opened = self._subscription_ticket(
            view, filters, snapshot, buffer_rows, overflow, answer=changes == "answer"
        )
        call = f"subscribe({view})"
        if not reconnect:
            yield from self._batches(self._open_subscription(opened, call))
            return
        yield from self._reconnecting(opened, reconnect_timeout, call)

    def _reconnecting(self, ticket: Any, timeout: Optional[float],
                      call: str = "subscribe") -> Iterator[ChangeBatch]:
        delay = _RECONNECT_FIRST_DELAY
        down_since: Optional[float] = None
        reopened = False
        while True:
            streaming = False
            try:
                reader = self._open_subscription(ticket, call)
                streaming = True
                down_since, delay = None, _RECONNECT_FIRST_DELAY
                for batch in self._batches(reader):
                    if reopened:
                        batch.reconnected, reopened = True, False
                    yield batch
            except PravahaError as failure:
                code = getattr(failure, "engine_code", None)
                # A stream that breaks after it opened, with no diagnosis from the engine, is the
                # transport going away under it -- a restart -- whatever status gRPC chose for it.
                if not (failure.retryable or code == "PRV-6105" or (streaming and code is None)):
                    raise
                if down_since is None:
                    down_since = time.monotonic()
                elif timeout is not None and time.monotonic() - down_since >= timeout:
                    raise
                _sleep(delay)
                delay = min(delay * 2, _RECONNECT_MAX_DELAY)
            else:
                # The server closed the stream without an error, which is what a node shutting
                # down does. The next attempt finds out whether it is back.
                _sleep(_RECONNECT_FIRST_DELAY)
            reopened = True

    def _subscription_ticket(self, view: str, filters: Optional[dict[str, Any]], snapshot: bool,
                             buffer_rows: Optional[int], overflow: Optional[str],
                             answer: bool = False) -> Any:
        pairs: list[Any] = []
        for column, value in (filters or {}).items():
            pairs.append(str(column))
            pairs.append(str(value))
        preference = None
        if buffer_rows is not None or overflow is not None:
            capacity = 10_000 if buffer_rows is None else int(buffer_rows)
            if capacity < 1:
                raise QueryError(f"a subscriber's buffer must hold at least one row, not {capacity}")
            preference = f"rows={capacity};overflow={(overflow or 'CONFLATE').upper()}"
        return _flight.Ticket(
            _subscribe_ticket(view, pairs, snapshot=snapshot, preference=preference, answer=answer)
        )

    def _open_subscription(self, ticket: Any, call: str = "subscribe") -> Any:
        # A refused subscription -- an unknown view, a filter naming a column the view does not
        # have -- surfaces here, before a single batch, converted like every other failure so
        # callers catch one exception type rather than pyarrow's several. One that is never
        # answered at all is DeadlineExceededError; one that opened runs as long as it runs.
        return self._open_stream(ticket, call)

    def _batches(self, reader: Any) -> Iterator[ChangeBatch]:
        try:
            parts: list[Any] = []
            for chunk in reader:
                rows = _weighted_rows_of(chunk.data)
                mark = _mark_of(getattr(chunk, "app_metadata", None))
                if mark is not None and mark[0] in (_MARK_SNAPSHOT, _MARK_SNAPSHOT_END):
                    # A snapshot may span several Arrow batches; it is handed over as one.
                    parts.extend(rows)
                    if mark[0] == _MARK_SNAPSHOT_END:
                        whole, parts = parts, []
                        yield ChangeBatch(whole, snapshot=True, frontier=mark[1])
                    continue
                if rows:
                    # A plain subscription's mark carries no frontier -- the server sends
                    # Long.MIN_VALUE for it, because the mark exists to carry the dropped
                    # count (STRM-10) and the frontier a plain subscription never had is
                    # still not sent.
                    frontier = None if mark is None or mark[1] == _NO_FRONTIER else mark[1]
                    yield ChangeBatch(
                        rows,
                        frontier=frontier,
                        dropped_before=0 if mark is None else mark[2],
                    )
        except (KeyboardInterrupt, GeneratorExit):
            raise
        except QueryError:
            raise
        except Exception as exc:
            raise _failure(exc) from exc

    def _act(self, action: str, fields: Sequence[str]) -> "list[list[str]]":
        # Encoded before the try, so a lone surrogate is refused as PRV-1053 rather than wrapped.
        payload = _wire_encode(fields)
        try:
            results = self._client.do_action(_flight.Action(action, payload), self._unary_options)
            return [_wire_decode(bytes(r.body)) for r in results]
        except QueryError:
            raise
        except Exception as exc:
            # Every failure becomes a QueryError carrying the server's own message, PRV code and
            # all. pyarrow maps Flight statuses onto several of its own exception classes --
            # ArrowInvalid for INVALID_ARGUMENT, FlightError for others -- and which one a caller
            # sees should not depend on which status the server happened to choose.
            raise self._deadline_or_failure(exc, f"action {action}") from exc

    # ---------------------------------------------------------------------------------
    # The engine's published HTTP API: the calls that have no Flight form.
    # ---------------------------------------------------------------------------------

    def _http(self) -> "RestClient":
        return self.http_api.rest

    @property
    def http_api(self) -> EngineApi:
        """The engine's HTTP API (:class:`pravaha.api.EngineApi`) with this client's token, TLS
        and timeout: the calls that have no Flight form -- the catalogue, validation, plans,
        lanes, identity, audit and health. Every HTTP method on this class delegates to it.

        Raises :class:`ApiError` when :attr:`ClientOptions.http_url` was not set, naming it."""
        if self._api is None:
            if not self._options.http_url:
                raise ApiError(
                    0,
                    "this client has no HTTP URL for the engine; set ClientOptions.http_url "
                    "(the engine's HTTP port, 18080 by default, not the Flight port)",
                )
            self._api = EngineApi(
                self._options.http_url,
                token=self._options.token,
                timeout_seconds=self._options.request_timeout_seconds,
                tls=self._options.tls if self._options.http_url.startswith("https://") else None,
                allow_insecure_token=self._options.allow_insecure_token,
            )
        return self._api

    def streams(self) -> "list[dict[str, Any]]":
        """Every stream this principal may read. See :meth:`EngineApi.streams`."""
        return self.http_api.streams()

    def stream(self, name: str) -> dict[str, Any]:
        """One stream. See :meth:`EngineApi.stream`."""
        return self.http_api.stream(name)

    def declare_stream(
        self,
        name: str,
        schema: str,
        *,
        event_time: str | None = None,
        out_of_orderness: str | None = None,
    ) -> dict[str, Any]:
        """Declares a stream from ``name:TYPE,...``. See :meth:`EngineApi.declare_stream`."""
        return self.http_api.declare_stream(
            name, schema, event_time=event_time, out_of_orderness=out_of_orderness
        )

    def validate(self, sql: str) -> dict[str, Any]:
        """Plans ``sql`` without running it. See :meth:`EngineApi.validate`."""
        return self.http_api.validate(sql)

    def explain(self, sql: str, level: str = "physical", *, graph: bool = False) -> dict[str, Any]:
        """The plan at ``level``, and with ``graph=True`` as nodes and edges too.
        See :meth:`EngineApi.explain`."""
        return self.http_api.explain(sql, level, graph=graph)

    def describe_queries(self) -> "list[dict[str, Any]]":
        """Every registered query this principal may see, described in full; visibility is
        exactly :meth:`queries`'s. See :meth:`EngineApi.describe_queries`."""
        return self.http_api.describe_queries()

    def describe_query(self, name: str) -> dict[str, Any]:
        """One registered query. See :meth:`EngineApi.describe_query`."""
        return self.http_api.describe_query(name)

    def dead_letters_http(self, name: str, *, offset: int = 0, limit: int = 50) -> dict[str, Any]:
        """The same page :meth:`dead_letters` gives over Flight, in the API's JSON shape.
        See :meth:`EngineApi.dead_letters`."""
        return self.http_api.dead_letters(name, offset=offset, limit=limit)

    def dead_letter_count(self, name: str) -> dict[str, Any]:
        """How deep a query's queue is, without any of the records.
        See :meth:`EngineApi.dead_letter_count`."""
        return self.http_api.dead_letter_count(name)

    def replay_dead_letters_http(self, name: str, ids: Sequence[str]) -> dict[str, Any]:
        """Feeds chosen dead letters back through the query, over HTTP: a new row at the
        query's current frontier, not a rewind. See :meth:`EngineApi.replay_dead_letters`."""
        return self.http_api.replay_dead_letters(name, ids)

    def query_plan(self, name: str) -> dict[str, Any]:
        """The plan a registered query is running, with its operator metrics.
        See :meth:`EngineApi.query_plan`."""
        return self.http_api.query_plan(name)

    def replacement_http(self, name: str) -> dict[str, Any]:
        """The replacement of ``name`` in the API's JSON shape, plus the ``history`` the Flight
        row cannot carry. Refused with ``PRV-4017`` when the name is not being replaced.
        See :meth:`EngineApi.replacement`."""
        return self.http_api.replacement(name)

    def describe_view(self, name: str) -> dict[str, Any]:
        """A view's schema, key, retention, sink and fingerprint, without reading it.
        See :meth:`EngineApi.describe_view`."""
        return self.http_api.describe_view(name)

    def sinks(self) -> "list[dict[str, Any]]":
        """The sinks this node binds that this principal may see. See :meth:`EngineApi.sinks`."""
        return self.http_api.sinks()

    def status(self) -> dict[str, Any]:
        """The node: identity, version, engine state, plugin health. See :meth:`EngineApi.status`."""
        return self.http_api.status()

    def plugins(self) -> "list[dict[str, Any]]":
        """Every plugin the node can load. See :meth:`EngineApi.plugins`."""
        return self.http_api.plugins()

    def audit(
        self,
        *,
        since: str | None = None,
        until: str | None = None,
        principal: str | None = None,
        view: str | None = None,
        action: str | None = None,
        decision: str | None = None,
        limit: int | None = None,
        cursor: str | None = None,
    ) -> dict[str, Any]:
        """One page of the node's recorded authorization decisions, newest first. A permission
        of its own: :class:`ApiError` with status 403 otherwise. See :meth:`EngineApi.audit`."""
        return self.http_api.audit(
            since=since,
            until=until,
            principal=principal,
            view=view,
            action=action,
            decision=decision,
            limit=limit,
            cursor=cursor,
        )

    def permissions(self) -> dict[str, Any]:
        """What the node's policy lets this principal do. See :meth:`EngineApi.permissions`."""
        return self.http_api.permissions()

    def tenants(self) -> dict[str, Any]:
        """The admission quotas in force and each tenant's use. See :meth:`EngineApi.tenants`."""
        return self.http_api.tenants()

    def metrics_text(self) -> str:
        """The node's Prometheus exposition, unparsed. See :meth:`EngineApi.metrics_text`."""
        return self.http_api.metrics_text()

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def connect(
    connection_string: Optional[str] = None,
    *,
    options: Optional[ClientOptions] = None,
    http_url: Optional[str] = None,
    timeout: Optional[float] = None,
) -> Client:
    """Connects to a Pravaha server.

    ``connect("grpc://host:19090")`` for plaintext; omitting the scheme means TLS, which
    is the right default for a client and the reason it is not the terse one.

    ``http_url`` is the engine's HTTP port (``http://host:18080``), needed only for the
    catalogue, validation, plans, sinks and status calls; pass it here or in ``options``.

    ``timeout`` is ``ClientOptions.request_timeout_seconds`` (60 by default): how long one
    request may wait for its answer -- every query up to its first batch, every action, every
    HTTP call, and the *opening* of a subscription, which then runs for as long as it runs. A
    call past it raises :class:`DeadlineExceededError` (PRV-1045).
    """
    if options is None:
        if connection_string is None:
            raise ValueError("connect() needs a connection string or options")
        extra: dict[str, Any] = {} if timeout is None else {"request_timeout_seconds": timeout}
        options = ClientOptions(endpoint=Endpoint.parse(connection_string), http_url=http_url, **extra)
    elif http_url is not None:
        raise ValueError("give http_url in options or as an argument, not both")
    elif timeout is not None:
        raise ValueError("give timeout in options (request_timeout_seconds) or as an argument, not both")
    return Client(options)

#: Backoff between attempts to reopen a ``reconnect=True`` subscription, in seconds.
_RECONNECT_FIRST_DELAY = 0.25
_RECONNECT_MAX_DELAY = 10.0
#: Indirected so a test can wait without waiting.
_sleep = time.sleep


def _wire_decode(payload: bytes) -> "list[str]":
    if len(payload) < 9 or int.from_bytes(payload[0:4], "big") != _WIRE_MAGIC:
        raise QueryError("this is not a Pravaha response")
    if payload[4] != _WIRE_VERSION:
        raise QueryError("this response was built by a different version of the server")
    count = int.from_bytes(payload[5:9], "big")
    fields = []
    index = 9
    for _ in range(count):
        if index + 4 > len(payload):
            raise QueryError("this Pravaha response is malformed")
        length = int.from_bytes(payload[index : index + 4], "big")
        index += 4
        if length < 0 or index + length > len(payload):
            raise QueryError("this Pravaha response is malformed")
        fields.append(payload[index : index + length].decode("utf-8"))
        index += length
    return fields


def _one_replacement(rows: "Sequence[Sequence[str]]", name: str) -> "Replacement":
    if not rows:
        raise QueryError(
            f"the server accepted the request but said nothing about the replacement of {name!r}"
        )
    return _replacement(rows[0])


def _failure(exc: Exception) -> PravahaError:
    """What a failed Flight call becomes: :class:`ConnectError` when nothing answered, otherwise
    :class:`QueryError` carrying the server's words.

    The distinction is the one a retry policy needs. The Flight client connects lazily, so an
    engine that is down or restarting surfaces at the first call as UNAVAILABLE -- and was reported
    as a refusal, 1041 and not retryable, telling a caller to give up on an outage. UNAVAILABLE is
    1040, retryable, as the constructor's own failure to connect already was.
    """
    unavailable = getattr(_flight, "FlightUnavailableError", None)
    if unavailable is not None and isinstance(exc, unavailable):
        return ConnectError(_server_words(_message_of(exc)))
    return QueryError(_message_of(exc))


def _message_of(exc: Exception) -> str:
    text = str(exc)
    return text if text else exc.__class__.__name__
