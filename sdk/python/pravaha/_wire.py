"""Pravaha's Flight framing: the control protocol's messages and the Flight SQL commands.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Split out of :mod:`pravaha.client` (CONSOLESIZE-1), which imports every name here. Pure
encoding: no transport, and pyarrow only inside the two functions that build Arrow values.
Decoding a response stays in the client, because a malformed one is a :class:`QueryError`.
"""
from __future__ import annotations

from typing import TYPE_CHECKING, Optional, Sequence

from pravaha.errors import require_well_formed

if TYPE_CHECKING:  # pyarrow is imported where it is used; this is for the annotations only.
    import pyarrow


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
    for index, field in enumerate(fields):
        require_well_formed(field, f"field {index} of this request")
        encoded = (field or "").encode("utf-8")
        out += len(encoded).to_bytes(4, "big")
        out += encoded
    return bytes(out)


def _subscribe_ticket(
    view: str,
    filter_pairs: Sequence[str],
    *,
    snapshot: bool = False,
    preference: Optional[str] = None,
    answer: bool = False,
) -> bytes:
    # A verb of its own for the snapshot form, and for following the answer (SUBANSWERWIRE-1),
    # so an older server refuses it as a ticket it does not know rather than reading a flag as a
    # filter column -- or quietly handing the changelog to a client that asked for the answer.
    verb = "subscribe.answer" if answer else "subscribe"
    if snapshot:
        verb += ".snapshot"
    fields = [verb, view, *filter_pairs]
    if preference is not None:
        # Last, and that is what makes it safe to add (STRM-16). Filter pairs are alternating
        # column and value, so their count is even; one more field makes it odd, which both
        # ends can tell without a version bump and with no chance of a filter column being
        # read as a policy.
        fields.append(preference)
    return _wire_encode(fields)


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


def _proto_fields(payload: bytes) -> dict[int, bytes]:
    """Every length-delimited field in a message, by field number.

    Enough of a protobuf reader for the four messages this SDK exchanges, and no more.
    Non-length-delimited wire types are skipped rather than decoded, because none of
    the fields we read use them -- and guessing at the ones we do not read is how a
    hand-rolled parser starts drifting from the spec.
    """
    fields: dict[int, bytes] = {}
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
