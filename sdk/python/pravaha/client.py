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

from dataclasses import dataclass
from typing import Any, Iterator, Optional, Sequence

from pravaha.endpoint import Endpoint
from pravaha.errors import PravahaError
from pravaha.options import ClientOptions

try:  # pragma: no cover - exercised by the import-error path, not by the happy one
    import pyarrow.flight as _flight
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "the Pravaha client transport needs pyarrow. Install it with:\n"
        '    pip install "pravaha[flight]"\n'
        "It is optional because a client is installed into somebody else's environment, "
        "and every pin it adds is one their resolver has to reconcile."
    ) from exc


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


class Client:
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
        try:
            self._client = _flight.FlightClient(self._uri)
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

    def register(self, name: str, sql: str, key_columns: Sequence[int]) -> "RegisteredQuery":
        """Registers a continuous query and returns what the server made of it.

        A registration is not a request -- it is a computation that keeps running and
        keeps a view current until somebody drops it::

            client.register("trade_feed", open("sql/01-continuous-trade-feed.sql").read(), [0])

        Registering the same question twice, even worded differently, gives one
        computation with two names: the server matches on the normalised plan rather
        than the text. The returned fingerprint is how you can tell.
        """
        ordinals = ",".join(str(int(c)) for c in key_columns)
        rows = self._act(_ACTION_REGISTER, [name, sql, ordinals])
        if not rows:
            raise QueryError("the server accepted the registration but said nothing about it")
        row = rows[0]
        return RegisteredQuery(
            name=_at(row, 0), state=_at(row, 1), sql=sql, fingerprint=_at(row, 2), rows_in=0
        )

    def queries(self) -> "list[RegisteredQuery]":
        """Every continuous query this server is running."""
        out = []
        for row in self._act(_ACTION_LIST, []):
            try:
                rows_in = int(_at(row, 4) or 0)
            except ValueError:
                rows_in = 0
            out.append(
                RegisteredQuery(
                    name=_at(row, 0),
                    state=_at(row, 1),
                    sql=_at(row, 2),
                    fingerprint=_at(row, 3),
                    rows_in=rows_in,
                )
            )
        return out

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

    def subscribe(
        self,
        view: str,
        filters: Optional[dict] = None,
        *,
        batch_size_hint: Optional[int] = None,
    ) -> Iterator["list[Row]"]:
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
        """
        pairs: list = []
        for column, value in (filters or {}).items():
            pairs.append(str(column))
            pairs.append(str(value))
        ticket = _flight.Ticket(_subscribe_ticket(view, pairs))
        try:
            reader = self._client.do_get(ticket, self._call_options)
        except Exception as exc:
            # A refused subscription -- an unknown view, a filter naming a column the view does
            # not have -- surfaces here, before a single batch. Converted like every other
            # failure so callers catch one exception type rather than pyarrow's several.
            raise QueryError(_message_of(exc)) from exc
        try:
            for chunk in reader:
                rows = _weighted_rows_of(chunk.data)
                if rows:
                    yield rows
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
) -> Client:
    """Connects to a Pravaha server.

    ``connect("grpc://host:9090")`` for plaintext; omitting the scheme means TLS, which
    is the right default for a client and the reason it is not the terse one.
    """
    if options is None:
        if connection_string is None:
            raise ValueError("connect() needs a connection string or options")
        options = ClientOptions(endpoint=Endpoint.parse(connection_string))
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


def _subscribe_ticket(view: str, filter_pairs: Sequence[str]) -> bytes:
    return _wire_encode(["subscribe", view, *filter_pairs])


def _at(row: Sequence[str], index: int) -> str:
    return row[index] if index < len(row) else ""


@dataclass(frozen=True)
class RegisteredQuery:
    """What a server says about one registered continuous query."""

    name: str
    state: str
    sql: str
    fingerprint: str
    rows_in: int

    @property
    def is_running(self) -> bool:
        return self.state == "RUNNING"

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
