"""Asking Pravaha a question from Python.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

    from pravaha import connect

    with connect("grpc://localhost:9090") as client:
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

import base64
import dataclasses
import urllib.parse
from dataclasses import dataclass
from typing import Any, Iterator, Optional, Sequence

from pravaha.debug import DebugCommands
from pravaha.endpoint import Endpoint
from pravaha.errors import PravahaError
from pravaha.options import ClientOptions
from pravaha.rest import ApiError, RestClient
from pravaha.tls import TlsOptions

try:  # pragma: no cover - exercised by the import-error path, not by the happy one
    import pyarrow.flight as _flight
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "the Pravaha client transport needs pyarrow. Install it with:\n"
        '    pip install "pravaha[flight]"\n'
        "It is optional because a client is installed into somebody else's environment, "
        "and every pin it adds is one their resolver has to reconcile."
    ) from exc


def _flight_client_tls_kwargs(tls: TlsOptions) -> dict:
    """Translates ``TlsOptions`` into the keyword arguments ``pyarrow.flight.FlightClient`` takes.

    ``FlightClient`` accepts only PEM bytes -- ``tls_root_certs``, ``cert_chain``,
    ``private_key`` -- with no keystore API of its own. A keystore is bridged to those
    bytes by :mod:`pravaha._keystore`, imported lazily here so that a client never
    touching a keystore never needs the ``cryptography`` package that bridge uses.
    """
    kwargs: dict = {}
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
        super().__init__(1041, message)


class ReadError(PravahaError):
    """A result could not be read, or was read twice."""

    def __init__(self, message: str) -> None:
        super().__init__(1042, message)


class ConnectError(PravahaError):
    """The server could not be reached. Worth retrying: a server may come back."""

    def __init__(self, message: str) -> None:
        super().__init__(1040, message, retryable=True)


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

        An ordinary query answer has no weights: every row in it is a row that is
        present, so this is ``1`` there. Only a subscription carries real ones.
        """
        return self._weight

    @property
    def is_retraction(self) -> bool:
        """Whether this withdraws a row rather than adding one."""
        return self._weight < 0

    def to_dict(self) -> dict:
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

class ChangeBatch(list):
    """One commit's rows -- or, first on a snapshot subscription, the view it starts from.

    A ``list`` of :class:`Row`, so code written for plain subscriptions, which iterates or
    indexes each batch, reads it unchanged. Two attributes say what it is:

    ``snapshot``
        True for the first batch of ``subscribe(..., snapshot=True)``: every row of the view
        at a commit, each with its multiplicity as its weight, delivered even when empty.
    ``frontier``
        The committed frontier the batch brings the view to, or ``None`` on a plain
        subscription, whose server does not say.
    """

    def __init__(self, rows: "Sequence[Row]", *, snapshot: bool = False,
                 frontier: Optional[int] = None) -> None:
        super().__init__(rows)
        self.snapshot = snapshot
        self.frontier = frontier

    def __repr__(self) -> str:
        kind = "snapshot" if self.snapshot else "commit"
        return f"ChangeBatch({kind}, frontier={self.frontier}, rows={list.__repr__(self)})"


_MARK_PREFIX = "pravaha:"
_MARK_SNAPSHOT = "snapshot"
_MARK_SNAPSHOT_END = "snapshot-end"


def _mark_of(metadata: Any) -> "Optional[tuple[str, int]]":
    """A snapshot stream's batch mark, ``pravaha:<kind>:<frontier>``; None when absent.

    Only a snapshot subscription's batches carry one. Anything else in the metadata is not
    ours and reads as no mark, rather than as a failure.
    """
    if metadata is None:
        return None
    raw = metadata.to_pybytes() if hasattr(metadata, "to_pybytes") else bytes(metadata)
    if not raw:
        return None
    text = raw.decode("utf-8", errors="replace")
    kind, _, frontier = text[len(_MARK_PREFIX):].rpartition(":")
    if not text.startswith(_MARK_PREFIX) or not kind:
        return None
    try:
        return kind, int(frontier)
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
    def columns(self) -> list:
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

    def to_list(self) -> list:
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
        node = options.endpoint.nodes[0]
        scheme = "grpc+tls" if options.endpoint.tls else "grpc"
        self._uri = f"{scheme}://{node.host}:{node.port}"
        # Held rather than sent once at connect time because Flight has no session: each
        # call is authenticated on its own, which is what lets a server behind a load
        # balancer answer without the balancer pinning a client to a node.
        self._call_options = (
            _flight.FlightCallOptions(
                headers=[(b"authorization", f"Bearer {options.token}".encode())]
            )
            if options.token
            else _flight.FlightCallOptions()
        )
        self._rest: Optional[RestClient] = None
        kwargs = _flight_client_tls_kwargs(options.tls) if options.endpoint.tls else {}
        try:
            self._client = _flight.FlightClient(self._uri, **kwargs)
        except Exception as exc:  # pragma: no cover - network failure shape varies
            raise ConnectError(f"cannot connect to {self._uri}: {exc}") from exc

    @property
    def uri(self) -> str:
        return self._uri

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
        if parameters:
            return self._query_with_parameters(sql, parameters)
        try:
            descriptor = _flight.FlightDescriptor.for_command(_statement_command(sql))
            info = self._client.get_flight_info(descriptor, self._call_options)
            reader = self._client.do_get(info.endpoints[0].ticket, self._call_options)
        except _flight.FlightError as exc:
            raise QueryError(_message_of(exc)) from exc
        except Exception as exc:
            raise QueryError(f"query failed: {exc}") from exc
        return QueryResult(reader)

    def _query_with_parameters(self, sql: str, parameters: Sequence[object]) -> QueryResult:
        """Prepare, bind, fetch.

        Four round trips, and the handle may be rewritten in the middle: the server
        keeps no session, so when parameters are bound it hands back a *new* handle
        that carries them, and the fetch uses that one. Doing it this way means a
        client can be answered by any node and can come back after a restart.
        """
        try:
            handle, parameter_schema = self._prepare(sql)
            try:
                batch = _bind(parameter_schema, parameters)
                handle = self._put_parameters(handle, batch)
                descriptor = _flight.FlightDescriptor.for_command(_prepared_command(handle))
                info = self._client.get_flight_info(descriptor, self._call_options)
                reader = self._client.do_get(info.endpoints[0].ticket, self._call_options)
            finally:
                self._close_prepared(handle)
        except (QueryError, ValueError):
            raise
        except _flight.FlightError as exc:
            raise QueryError(_message_of(exc)) from exc
        except Exception as exc:
            raise QueryError(f"query failed: {exc}") from exc
        return QueryResult(reader)

    def _prepare(self, sql: str) -> tuple[bytes, "pyarrow.Schema"]:
        action = _flight.Action("CreatePreparedStatement", _create_prepared_request(sql))
        results = list(self._client.do_action(action, self._call_options))
        if not results:
            raise QueryError("the server did not return a prepared statement")
        handle, _dataset, parameter_schema_bytes = _parse_prepared_result(results[0].body.to_pybytes())
        return handle, _read_schema(parameter_schema_bytes)

    def _put_parameters(self, handle: bytes, batch: "pyarrow.RecordBatch") -> bytes:
        descriptor = _flight.FlightDescriptor.for_command(_prepared_command(handle))
        writer, reader = self._client.do_put(descriptor, batch.schema, self._call_options)
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
            list(self._client.do_action(action, self._call_options))
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
                rows_in = int(_at(row, 4) or 0)
            except ValueError:
                rows_in = 0
            # Fields 5-7 were added after the first five and trail them, so a server that
            # predates them sends five and these read as "unknown": an empty key, no sink,
            # no retention. 8-12 are the feed (FEED-1), trailing for the same reason.
            code = _at(row, 9)
            out.append(
                RegisteredQuery(
                    name=_at(row, 0),
                    state=_at(row, 1),
                    sql=_at(row, 2),
                    fingerprint=_at(row, 3),
                    rows_in=rows_in,
                    key_columns=_ordinals(_at(row, 5)),
                    sink=_at(row, 6) or None,
                    retention=_at(row, 7) or None,
                    feed=_at(row, 8) or None,
                    feed_stop=(FeedStop(code=code, message=_at(row, 10), where=_at(row, 11),
                                        at=_at(row, 12)) if code else None),
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
        filters: Optional[dict] = None,
        *,
        batch_size_hint: Optional[int] = None,
        snapshot: bool = False,
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
        weights gives the view with nothing missed and nothing counted twice. A subscriber
        that falls too far behind has its stream ended with ``PRV-6105`` rather than skipped
        past a commit; subscribe again to start from a fresh snapshot. A server older than
        this SDK refuses ``snapshot=True`` with ``PRV-6102``.
        """
        pairs: list = []
        for column, value in (filters or {}).items():
            pairs.append(str(column))
            pairs.append(str(value))
        ticket = _flight.Ticket(_subscribe_ticket(view, pairs, snapshot=snapshot))
        try:
            reader = self._client.do_get(ticket, self._call_options)
        except Exception as exc:
            # A refused subscription -- an unknown view, a filter naming a column the view does
            # not have -- surfaces here, before a single batch. Converted like every other
            # failure so callers catch one exception type rather than pyarrow's several.
            raise QueryError(_message_of(exc)) from exc
        try:
            parts: list = []
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
                    yield ChangeBatch(rows, frontier=None if mark is None else mark[1])
        except (KeyboardInterrupt, GeneratorExit):
            raise
        except QueryError:
            raise
        except Exception as exc:
            raise QueryError(_message_of(exc)) from exc

    def _act(self, action: str, fields: Sequence[str]) -> "list[list[str]]":
        try:
            results = self._client.do_action(
                _flight.Action(action, _wire_encode(fields)), self._call_options
            )
            return [_wire_decode(bytes(r.body)) for r in results]
        except QueryError:
            raise
        except Exception as exc:
            # Every failure becomes a QueryError carrying the server's own message, PRV code and
            # all. pyarrow maps Flight statuses onto several of its own exception classes --
            # ArrowInvalid for INVALID_ARGUMENT, FlightError for others -- and which one a caller
            # sees should not depend on which status the server happened to choose.
            raise QueryError(_message_of(exc)) from exc

    # ---------------------------------------------------------------------------------
    # The engine's published HTTP API: the calls that have no Flight form.
    # ---------------------------------------------------------------------------------

    def _http(self) -> "RestClient":
        if self._rest is None:
            if not self._options.http_url:
                raise ApiError(
                    0,
                    "this client has no HTTP URL for the engine; set ClientOptions.http_url "
                    "(the engine's HTTP port, 8080 by default, not the Flight port)",
                )
            self._rest = RestClient(
                self._options.http_url,
                token=self._options.token,
                timeout_seconds=self._options.request_timeout_seconds,
                tls=self._options.tls if self._options.http_url.startswith("https://") else None,
                allow_insecure_token=self._options.allow_insecure_token,
            )
        return self._rest

    def streams(self) -> "list[dict]":
        """Every stream this principal may read: ``name``, ``version``, ``fields``,
        ``eventTime``, ``outOfOrderness`` (ISO-8601) and ``source`` (the plugin that feeds
        it, if bound). ``GET /api/v1/streams``."""
        return list(self._http().get("/api/v1/streams") or [])

    def stream(self, name: str) -> dict:
        """One stream. ``GET /api/v1/streams/{name}``."""
        return dict(self._http().get("/api/v1/streams/" + _segment(name)) or {})

    def declare_stream(
        self,
        name: str,
        schema: str,
        *,
        event_time: str | None = None,
        out_of_orderness: str | None = None,
    ) -> dict:
        """Declares a stream from ``name:TYPE,...``, with its event-time column and how late
        its rows may be (ISO-8601, such as ``"PT10S"``). An administrative act; the server
        refuses it to a principal who may not change what it serves. ``POST /api/v1/streams``."""
        body: dict = {"name": name, "schema": schema}
        if event_time:
            body["eventTime"] = event_time
        if out_of_orderness:
            body["outOfOrderness"] = out_of_orderness
        return dict(self._http().post("/api/v1/streams", body) or {})

    def validate(self, sql: str) -> dict:
        """Plans ``sql`` without running it: ``valid``, ``diagnostics`` (each with ``code``,
        ``message``, ``helpUrl`` and, when the parser knew it, ``range`` -- 1-based lines and
        columns, end column inclusive), ``outputFields`` and ``elapsedMicros``. An invalid
        query is an answer, not an error. ``POST /api/v1/queries/validate``."""
        return dict(self._http().post("/api/v1/queries/validate", {"sql": sql}) or {})

    def explain(self, sql: str, level: str = "physical", *, graph: bool = False) -> dict:
        """The plan, as ``plan`` text at ``level`` (``physical``, ``logical`` or ``codegen``),
        and with ``graph=True`` also as ``graph``: ``nodes`` and ``edges``.
        ``POST /api/v1/queries/explain``."""
        query = {"level": level, "format": "graph" if graph else "text"}
        return dict(self._http().post("/api/v1/queries/explain", {"sql": sql}, query) or {})

    def describe_queries(self) -> "list[dict]":
        """Every registered query this principal may see, described in full: keys by name and
        ordinal, retention, sink and whether it is still attached, rows in, the other names
        sharing the computation, and ``feed`` -- each source partition's state and, for one
        that stopped, its ``failure`` (code, message, help URL) and ``stoppedAt``. Visibility is exactly :meth:`queries`'s. ``GET /api/v1/queries``."""
        return list(self._http().get("/api/v1/queries") or [])

    def describe_query(self, name: str) -> dict:
        """One registered query, as :meth:`describe_queries` describes it.
        ``GET /api/v1/queries/{name}``."""
        return dict(self._http().get("/api/v1/queries/" + _segment(name)) or {})

    def dead_letters_http(self, name: str, *, offset: int = 0, limit: int = 50) -> dict:
        """A page of a query's dead letters over HTTP, newest first, with the queue's totals.

        The same answer :meth:`dead_letters` gives over Flight, in the API's JSON shape: an
        ``entries`` list, and ``total``, ``evicted``, ``retention`` and ``configured`` beside
        it. An entry's ``raw`` is ``null`` with ``withheld`` saying why when this caller reads
        the view through a row filter. ``GET /api/v1/queries/{name}/dead-letters``.
        """
        return dict(
            self._http().get(
                "/api/v1/queries/" + _segment(name) + "/dead-letters",
                {"offset": offset, "limit": limit},
            )
            or {}
        )

    def dead_letter_count(self, name: str) -> dict:
        """How deep a query's queue is, and what retention has taken, without any of the
        records. The call a dashboard polls, because fetching a page of records with their
        bytes to learn a number would be reading production data to draw a line.
        ``GET /api/v1/queries/{name}/dead-letters/count``."""
        return dict(self._http().get("/api/v1/queries/" + _segment(name) + "/dead-letters/count") or {})

    def replay_dead_letters_http(self, name: str, ids: Sequence[str]) -> dict:
        """Feeds chosen dead letters back through the query, over HTTP.

        A new row at the query's current frontier, not a rewind. Not idempotent.
        ``POST /api/v1/queries/{name}/dead-letters/replay``.
        """
        return dict(
            self._http().post(
                "/api/v1/queries/" + _segment(name) + "/dead-letters/replay",
                {"ids": list(ids)},
            )
            or {}
        )

    def query_plan(self, name: str) -> dict:
        """The plan a registered query is running, as ``nodes`` and ``edges``, with the
        query-level numbers the engine measures under ``query`` -- including how long its
        writers spent unable to place a row. ``operatorMetrics`` carries rows in, rows out,
        state bytes, the watermark and a sampled self time per node, keyed by the same node
        ids ``nodes`` uses, and ``bottleneck`` names the node most of the query's own time
        went into. It is ``None`` when nothing was measuring -- the query is not registered,
        the node runs with ``pravaha.metrics.operators`` off, or the caller is entitled only
        to a row-filtered slice -- and ``metricsNote`` says which.
        ``GET /api/v1/queries/{name}/plan``."""
        return dict(self._http().get("/api/v1/queries/" + _segment(name) + "/plan") or {})

    def describe_view(self, name: str) -> dict:
        """A view's ``schema``, ``keyColumns``, ``retention``, ``sink`` and ``fingerprint``,
        without reading it. ``GET /api/v1/views/{name}``."""
        return dict(self._http().get("/api/v1/views/" + _segment(name)) or {})

    def sinks(self) -> "list[dict]":
        """The sinks this node binds that this principal may see: ``plugin``, ``fields``,
        ``keyColumns``, ``emitModes``, ``acceptsRetractions`` and the visible ``writers``.
        Never a binding's options. ``GET /api/v1/sinks``."""
        return list(self._http().get("/api/v1/sinks") or [])

    def status(self) -> dict:
        """The node: identity, version, engine state, plugin health. ``GET /api/v1/status``."""
        return dict(self._http().get("/api/v1/status") or {})

    def plugins(self) -> "list[dict]":
        """Every plugin the node can load: ``name``, ``version``, ``requiredApiVersion``,
        ``compatible``, ``loaded``, ``kinds`` (``source``/``sink``/``lookup``), declared
        ``capabilities``, manifest ``settings`` (names only), ``health`` (with ``reported``:
        whether a live instance said so) and the ``bindings`` this principal may see. Never a
        binding's options. ``GET /api/v1/plugins``."""
        return list(self._http().get("/api/v1/plugins") or [])

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
    ) -> dict:
        """One page of the node's recorded authorization decisions, newest first: ``events``
        (each with ``sequence``, ``at``, ``principal``, ``action``, ``target``, ``decision``,
        ``reason``, ``detail``), ``nextCursor`` (pass back as ``cursor``; ``None`` on the last
        page), and what the readable window holds (``capacity``, ``retained``, ``evicted``,
        ``oldestRetained``). ``since``/``until`` are ISO-8601 instants; ``decision`` is
        ``"allow"`` or ``"deny"``.

        A permission of its own: a principal the policy does not let read the trail gets
        :class:`ApiError` with status 403, however much else it may read -- and the attempt is
        recorded either way. ``GET /api/v1/audit``."""
        query = {
            name: value
            for name, value in (
                ("since", since),
                ("until", until),
                ("principal", principal),
                ("view", view),
                ("action", action),
                ("decision", decision),
                ("limit", limit),
                ("cursor", cursor),
            )
            if value not in (None, "")
        }
        return dict(self._http().get("/api/v1/audit", query or None) or {})

    def permissions(self) -> dict:
        """What the node's policy lets this principal do: ``register``, ``readAudit`` (each
        ``allowed`` with a ``reason`` when not), and for each view and stream it may see, how
        it may ``read`` it (``full`` or ``filtered``) and whether it may ``administer`` it.
        ``GET /api/v1/me/permissions``."""
        return dict(self._http().get("/api/v1/me/permissions") or {})

    def metrics_text(self) -> str:
        """The node's Prometheus exposition, unparsed. ``GET /actuator/prometheus``."""
        return self._http().text("/actuator/prometheus")

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
) -> Client:
    """Connects to a Pravaha server.

    ``connect("grpc://host:9090")`` for plaintext; omitting the scheme means TLS, which
    is the right default for a client and the reason it is not the terse one.

    ``http_url`` is the engine's HTTP port (``http://host:8080``), needed only for the
    catalogue, validation, plans, sinks and status calls; pass it here or in ``options``.
    """
    if options is None:
        if connection_string is None:
            raise ValueError("connect() needs a connection string or options")
        options = ClientOptions(endpoint=Endpoint.parse(connection_string), http_url=http_url)
    elif http_url is not None:
        raise ValueError("give http_url in options or as an argument, not both")
    return Client(options)


def _statement_command(sql: str) -> bytes:
    """The Flight SQL ``CommandStatementQuery`` for a piece of SQL.

    Hand-encoded rather than pulled from a generated protobuf module, because the
    message is two fields and the alternative is making every user of this SDK install
    a protobuf runtime and a generated package to send them. The encoding is
    ``Any{type_url, value}`` wrapping ``CommandStatementQuery{query}``, and it is
    covered by a test against a real server rather than trusted.
    """
    query = _proto_field(1, sql.encode("utf-8"))
    type_url = _proto_field(1, b"type.googleapis.com/arrow.flight.protocol.sql.CommandStatementQuery")
    return type_url + _proto_field(2, query)


# Pravaha's own control protocol. Flight SQL has no vocabulary for "register a continuous
# query" or "subscribe to one", so both travel as Flight actions and a Flight ticket -- the
# extension points the protocol provides. The framing is a magic number, a version and a list
# of length-prefixed UTF-8 strings, which is four lines to write in either language and keeps
# this SDK free of a protobuf runtime.
_WIRE_MAGIC = 0x50525648
_WIRE_VERSION = 1

_ACTION_REGISTER = "pravaha.register"
_ACTION_DROP = "pravaha.drop"
_ACTION_LIST = "pravaha.list"
_ACTION_PAUSE = "pravaha.pause"
_ACTION_RESUME = "pravaha.resume"
_ACTION_REPLACE = "pravaha.replace"
_ACTION_REPLACEMENT = "pravaha.replacement"
_ACTION_CUTOVER = "pravaha.cutover"
_ACTION_ROLLBACK = "pravaha.rollback"
_ACTION_ABANDON = "pravaha.abandon"
_ACTION_FINISH = "pravaha.finish"
_ACTION_BACKFILL = "pravaha.backfill"

_ACTION_DLQ_LIST = "pravaha.dlq.list"
_ACTION_DLQ_SHOW = "pravaha.dlq.show"
_ACTION_DLQ_REPLAY = "pravaha.dlq.replay"



def _wire_encode(fields: Sequence[str]) -> bytes:
    out = bytearray()
    out += _WIRE_MAGIC.to_bytes(4, "big")
    out.append(_WIRE_VERSION)
    out += len(fields).to_bytes(4, "big")
    for field in fields:
        encoded = (field or "").encode("utf-8")
        out += len(encoded).to_bytes(4, "big")
        out += encoded
    return bytes(out)


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


def _subscribe_ticket(view: str, filter_pairs: Sequence[str], *, snapshot: bool = False) -> bytes:
    # A verb of its own for the snapshot form, so an older server refuses it as a ticket it
    # does not know rather than reading a flag as a filter column.
    verb = "subscribe.snapshot" if snapshot else "subscribe"
    return _wire_encode([verb, view, *filter_pairs])


def _at(row: Sequence[str], index: int) -> str:
    return row[index] if index < len(row) else ""


def _ordinals(text: str) -> "tuple[int, ...]":
    try:
        return tuple(int(part) for part in text.split(",") if part.strip())
    except ValueError:
        # Not a field this client understands; an empty key reads as "unknown", not wrong.
        return ()


def _int(text: str) -> int:
    """A numeric field, or zero from a server that did not send one."""
    try:
        return int(text) if text else 0
    except ValueError:
        return 0


def _dead_letter_of(row: Sequence[str]) -> "DeadLetter":
    """One entry from the wire, with its record decoded from Base64."""
    try:
        raw = base64.b64decode(_at(row, 8), validate=True)
    except Exception:
        raw = b""
    return DeadLetter(
        id=_at(row, 0),
        sequence=_int(_at(row, 1)),
        stream=_at(row, 2),
        offset=_at(row, 3),
        code=_at(row, 4),
        reason=_at(row, 5),
        at=_at(row, 6),
        size=_int(_at(row, 7)),
        raw=raw,
        withheld=_at(row, 9),
        replay=_at(row, 10) or "NEW",
        replayed_at=_at(row, 11),
    )


def _segment(name: str) -> str:
    """A name as one URL path segment, so a name cannot address a different endpoint."""
    return urllib.parse.quote(str(name), safe="")


def _number(text: str) -> int:
    try:
        return int(text)
    except ValueError:
        return 0


def _replacement(row: Sequence[str]) -> "Replacement":
    """Reads a status from the wire's positional fields, which are append-only."""
    return Replacement(
        name=_at(row, 0),
        state=_at(row, 1),
        sql=_at(row, 2),
        candidate=_at(row, 3) or None,
        replacing=_at(row, 4) or None,
        sink=_at(row, 5) or None,
        options=_at(row, 6),
        owner=_at(row, 7) or None,
        started_at=_at(row, 8) or None,
        cut_over_at=_at(row, 9) or None,
        rollback_until=_at(row, 10) or None,
        rollback_available=_at(row, 11) == "true",
        history_rows=_number(_at(row, 12)),
        live_rows=_number(_at(row, 13)),
        rows_per_second=_number(_at(row, 14)),
        partitions=_number(_at(row, 15)),
        partitions_live=_number(_at(row, 16)),
        history_complete=_at(row, 17) == "true",
        rate_limit=_number(_at(row, 18)),
        paused=_at(row, 19) == "true",
        lag_nanos=_number(_at(row, 20)),
        failure_code=_at(row, 21) or None,
        failure=_at(row, 22) or None,
    )


def _one_replacement(rows: "Sequence[Sequence[str]]", name: str) -> "Replacement":
    if not rows:
        raise QueryError(
            f"the server accepted the request but said nothing about the replacement of {name!r}"
        )
    return _replacement(rows[0])


@dataclass(frozen=True)
class Replacement:
    """A blue/green replacement as the server reports it (ADR-046).

    One answer rather than three calls: a screen that has to ask separately for the state,
    the progress and the rollback window shows three moments instead of one.
    """

    name: str
    #: ``BACKFILLING``, ``CAUGHT_UP``, ``CUT_OVER``, ``ROLLED_BACK``, ``ABANDONED``,
    #: ``FAILED`` or ``FINISHED``.
    state: str
    sql: str
    #: The fingerprint of the computation being prepared.
    candidate: Optional[str] = None
    #: The fingerprint of the one serving the name.
    replacing: Optional[str] = None
    sink: Optional[str] = None
    options: str = ""
    owner: Optional[str] = None
    started_at: Optional[str] = None
    cut_over_at: Optional[str] = None
    rollback_until: Optional[str] = None
    rollback_available: bool = False
    history_rows: int = 0
    live_rows: int = 0
    rows_per_second: int = 0
    partitions: int = 0
    partitions_live: int = 0
    history_complete: bool = False
    rate_limit: int = 0
    paused: bool = False
    lag_nanos: int = 0
    failure_code: Optional[str] = None
    failure: Optional[str] = None

    @property
    def active(self) -> bool:
        """Still doing something: backfilling, caught up, or cut over and retaining."""
        return self.state in ("BACKFILLING", "CAUGHT_UP", "CUT_OVER")

    def __str__(self) -> str:
        return f"{self.name} [{self.state}, {self.history_rows} history rows]"


@dataclass(frozen=True)
class FeedStop:
    """Why a registered query's source stopped (FEED-1).

    A source that fails mid-read is not retried: the query stays ``RUNNING`` and its view
    answers at the frontier it reached. ``code`` is ``PRV-5092`` or the source's own;
    ``message`` is what it said, or a note that the server withheld it from a row-filtered
    caller; ``where`` is ``stream#partition``; ``at`` is when, ISO-8601.
    """

    code: str
    message: str = ""
    where: str = ""
    at: str = ""


@dataclass(frozen=True)
class RegisteredQuery:
    """What a server says about one registered continuous query."""

    name: str
    state: str
    sql: str
    fingerprint: str
    #: ``-1`` when the server withholds the count: your access to the view is row-filtered.
    rows_in: int
    #: The view's key as output ordinals; empty from a server that predates the field.
    key_columns: "tuple[int, ...]" = ()
    #: The sink binding its changes are also written to, or ``None``.
    sink: Optional[str] = None
    #: How much event time the view keeps (ISO-8601, or ``"forever"``); ``None`` if unknown.
    retention: Optional[str] = None
    #: Whether rows still reach it: ``RUNNING``, ``PAUSED``, ``STOPPED`` (a source failed and is
    #: not retried) or ``NONE`` (nothing bound); ``None`` from a server that predates it.
    feed: Optional[str] = None
    #: Why the first stopped source stopped, or ``None`` while every source reads.
    feed_stop: Optional[FeedStop] = None

    @property
    def is_running(self) -> bool:
        return self.state == "RUNNING"

    @property
    def is_source_stopped(self) -> bool:
        """``RUNNING`` and not moving: a source stopped mid-read. :attr:`feed_stop` says why."""
        return self.feed == "STOPPED"

    def __str__(self) -> str:
        return f"{self.name} [{self.state}, {self.fingerprint}, {self.rows_in} rows]"


def _create_prepared_request(sql: str) -> bytes:
    """``ActionCreatePreparedStatementRequest{query}``, packed as ``Any``."""
    body = _proto_field(1, sql.encode("utf-8"))
    type_url = _proto_field(
        1, b"type.googleapis.com/arrow.flight.protocol.sql.ActionCreatePreparedStatementRequest"
    )
    return type_url + _proto_field(2, body)


def _close_prepared_request(handle: bytes) -> bytes:
    """``ActionClosePreparedStatementRequest{prepared_statement_handle}``, packed as ``Any``."""
    body = _proto_field(1, handle)
    type_url = _proto_field(
        1, b"type.googleapis.com/arrow.flight.protocol.sql.ActionClosePreparedStatementRequest"
    )
    return type_url + _proto_field(2, body)


def _prepared_command(handle: bytes) -> bytes:
    """``CommandPreparedStatementQuery{prepared_statement_handle}``, packed as ``Any``."""
    body = _proto_field(1, handle)
    type_url = _proto_field(
        1, b"type.googleapis.com/arrow.flight.protocol.sql.CommandPreparedStatementQuery"
    )
    return type_url + _proto_field(2, body)


def _parse_prepared_result(body: bytes) -> tuple[bytes, bytes, bytes]:
    """Reads ``Any{ActionCreatePreparedStatementResult}``: handle, dataset and parameter schemas."""
    fields = _proto_fields(_proto_fields(body).get(2, b""))
    return fields.get(1, b""), fields.get(2, b""), fields.get(3, b"")


def _parse_doput_result(body: bytes) -> bytes:
    """Reads ``DoPutPreparedStatementResult{prepared_statement_handle}``.

    Not wrapped in ``Any``: this one travels as application metadata on the put
    acknowledgement rather than as an action result, which is a difference in the spec
    and not an inconsistency here.
    """
    return _proto_fields(body).get(1, b"")


def _proto_fields(payload: bytes) -> dict:
    """Every length-delimited field in a message, by field number.

    Enough of a protobuf reader for the four messages this SDK exchanges, and no more.
    Non-length-delimited wire types are skipped rather than decoded, because none of
    the fields we read use them -- and guessing at the ones we do not read is how a
    hand-rolled parser starts drifting from the spec.
    """
    fields: dict = {}
    index = 0
    while index < len(payload):
        tag, index = _read_varint(payload, index)
        number, wire_type = tag >> 3, tag & 0x7
        if wire_type == 2:
            length, index = _read_varint(payload, index)
            fields[number] = payload[index : index + length]
            index += length
        elif wire_type == 0:
            _, index = _read_varint(payload, index)
        elif wire_type == 5:
            index += 4
        elif wire_type == 1:
            index += 8
        else:
            break
    return fields


def _read_varint(payload: bytes, index: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while index < len(payload):
        byte = payload[index]
        index += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, index
        shift += 7
    return value, index


def _read_schema(serialized: bytes) -> "pyarrow.Schema":
    """The parameter schema, as the server serialised it.

    Flight SQL sends it as an IPC *message*, and pyarrow reads schemas from IPC
    *streams*, so a stream continuation and end-of-stream marker are added around it.
    A schema message alone is a valid stream prefix; this is framing, not translation.
    """
    import pyarrow as pa

    if not serialized:
        return pa.schema([])
    try:
        return pa.ipc.read_schema(pa.py_buffer(serialized))
    except Exception:
        stream = b"\xff\xff\xff\xff" + len(serialized).to_bytes(4, "little") + serialized
        return pa.ipc.open_stream(pa.py_buffer(stream + b"\xff\xff\xff\xff\x00\x00\x00\x00")).schema


def _bind(schema: "pyarrow.Schema", parameters: Sequence[object]) -> "pyarrow.RecordBatch":
    """One row of values, in the types the server asked for.

    The schema comes from the server, so nothing here guesses a type -- which is the
    part a driver usually gets wrong. A value of the wrong type fails here, naming the
    placeholder; the same value reaching the server fails with a message about a query
    the caller did not write.
    """
    import pyarrow as pa

    if len(parameters) != len(schema):
        raise ValueError(
            f"this statement has {len(schema)} placeholder"
            f"{'' if len(schema) == 1 else 's'} and {len(parameters)} "
            f"value{' was' if len(parameters) == 1 else 's were'} given"
        )
    columns = []
    for index, (field, value) in enumerate(zip(schema, parameters)):
        try:
            columns.append(pa.array([value], type=field.type))
        except (pa.ArrowInvalid, pa.ArrowTypeError, TypeError) as exc:
            raise ValueError(
                f"?{index + 1} needs {field.type}, but {value!r} was given"
            ) from exc
    return pa.RecordBatch.from_arrays(columns, schema=schema)


def _proto_field(number: int, payload: bytes) -> bytes:
    """One length-delimited protobuf field: tag, length, bytes."""
    return _varint((number << 3) | 2) + _varint(len(payload)) + payload


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def _message_of(exc: Exception) -> str:
    text = str(exc)
    return text if text else exc.__class__.__name__


@dataclass(frozen=True)
class DeadLetter:
    """One record a query's feed could not decode (B5).

    :attr:`raw` may be empty with :attr:`withheld` saying why, and that is not the same as an
    empty record: a dead letter's bytes are a row of the source, a record that failed to decode
    has no row for a row filter to be applied to, and a caller entitled to a slice of the view
    is therefore shown everything about the record except the record. Check
    :attr:`is_withheld` rather than drawing an empty cell.
    """

    #: What addresses this entry: the correlation id, the same string the node's log lines carry.
    id: str
    #: Its position in the file, oldest first. It shifts when retention evicts, which is why
    #: :attr:`id` and not this is the handle.
    sequence: int
    #: Which of the query's streams it arrived on, or ``""`` for an entry that predates the field.
    stream: str
    #: Where it came from in the source's own terms: ``line 812``, ``orders/3@1041``.
    offset: str
    #: The ``PRV-`` code of the decode failure, or ``""`` when the source named none.
    code: str
    #: The decoder's own sentence, or ``""`` when it is withheld.
    reason: str
    #: When it was rejected, ISO-8601, or ``""`` for an entry written before that was recorded.
    at: str
    #: How many bytes the record is. Disclosed even when the record is not: a length is not a row.
    size: int
    #: The record itself, or empty when withheld.
    raw: bytes
    #: Why the record is absent, or ``""`` when it is not.
    withheld: str
    #: ``NEW``, ``REPLAYED`` or ``FAILED_AGAIN``.
    replay: str = "NEW"
    #: When it was replayed, ISO-8601, or ``""``.
    replayed_at: str = ""

    @property
    def is_withheld(self) -> bool:
        """True when the server would not give this caller the record itself."""
        return bool(self.withheld)

    def __str__(self) -> str:
        code = f" {self.code}" if self.code else ""
        return f"{self.id} at {self.offset}{code}"


@dataclass(frozen=True)
class DeadLetterPage:
    """A page of one query's dead letters, newest first, with the queue's totals.

    The totals come with the page rather than from a second call, because the first thing
    anyone does with a page of failures is ask how many there are -- and a second call answers
    from a different moment.
    """

    query: str
    entries: "tuple[DeadLetter, ...]"
    offset: int
    total: int
    bytes: int
    #: Entries retention has removed and are gone. Never omitted: a depth without it cannot be
    #: read, since a queue steady at two thousand is either one bad afternoon or a bound
    #: throwing two thousand a minute away.
    evicted: int
    evicted_bytes: int
    replayed: int
    failed_again: int
    #: The bound in force, as words.
    retention: str
    #: Whether the server has a dead-letter directory at all. ``False`` means a record it cannot
    #: decode stops the source rather than being kept -- a different state from an empty queue.
    configured: bool = True

    @property
    def has_more(self) -> bool:
        """Whether there are older entries past this page."""
        return self.offset + len(self.entries) < self.total


@dataclass(frozen=True)
class DeadLetterReplay:
    """What replaying one dead letter did."""

    id: str
    #: ``REPLAYED`` -- the record decoded and is a row of the view now, applied at the frontier
    #: the query has reached -- or ``FAILED_AGAIN``.
    outcome: str
    #: The server's sentence, which says what that means for this query.
    detail: str = ""
    #: When it failed again, the entry it went back on the queue as. That is the id to replay
    #: next; replaying :attr:`id` again would decode the same bytes with the same decoder.
    new_id: str = ""

    @property
    def succeeded(self) -> bool:
        return self.outcome == "REPLAYED"
