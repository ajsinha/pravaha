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

    __slots__ = ("_columns", "_values")

    def __init__(self, columns: Sequence[str], values: Sequence[Any]) -> None:
        self._columns = columns
        self._values = values

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
        try:
            self._client = _flight.FlightClient(self._uri)
        except Exception as exc:  # pragma: no cover - network failure shape varies
            raise ConnectError(f"cannot connect to {self._uri}: {exc}") from exc

    @property
    def uri(self) -> str:
        return self._uri

    def query(self, sql: str) -> QueryResult:
        """Runs one query and returns its rows."""
        try:
            descriptor = _flight.FlightDescriptor.for_command(_statement_command(sql))
            info = self._client.get_flight_info(descriptor)
            reader = self._client.do_get(info.endpoints[0].ticket)
        except _flight.FlightError as exc:
            raise QueryError(_message_of(exc)) from exc
        except Exception as exc:
            raise QueryError(f"query failed: {exc}") from exc
        return QueryResult(reader)

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
