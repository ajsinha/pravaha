"""What the control protocol answers, as values: queries, replacements, dead letters.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Split out of :mod:`pravaha.client` (CONSOLESIZE-1), which re-exports every name here, so
``from pravaha.client import Replacement`` keeps working.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
from typing import TYPE_CHECKING, Optional, Sequence


if TYPE_CHECKING:  # pyarrow is imported where it is used; this is for the annotations only.
    pass


def _at(row: Sequence[str], index: int) -> str:
    return row[index] if index < len(row) else ""


# The fields one pravaha.list row carries, in order: ControlWire.LIST_FIELDS on the server, which
# ControlWireListFieldsTest holds this tuple to (WIRE-1). Positional and append-only.
LIST_FIELDS = (
    "name",
    "state",
    "sql",
    "fingerprint",
    "rows_in",
    "key_ordinals",
    "sink",
    "retention",
    "feed_state",
    "feed_code",
    "feed_message",
    "feed_where",
    "feed_at",
    "sink_state",
    "sink_code",
    "sink_message",
)


def _listed(row: Sequence[str], name: str) -> str:
    """A pravaha.list row's field by its name; empty when an older server did not send it."""
    return _at(row, LIST_FIELDS.index(name))


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
class SinkFailure:
    """Why a registered query's sink was detached (SINK-3, ``PRV-8009``).

    A sink that refuses a batch is detached rather than written past: the query stays
    ``RUNNING``, its view stays right, and nothing more is written. ``code`` is ``PRV-8009``;
    ``message`` is what happened, with every configured sink option struck out of it, or a note
    that the server withheld it from a row-filtered caller.
    """

    code: str
    message: str = ""


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
    #: Whether the sink is still writing: ``ATTACHED``, ``DETACHED`` or ``NONE`` (writes
    #: nowhere); ``None`` from a server that predates the field.
    sink_state: Optional[str] = None
    #: Why the sink was detached, or ``None`` while it writes.
    sink_failure: Optional[SinkFailure] = None

    @property
    def is_running(self) -> bool:
        return self.state == "RUNNING"

    @property
    def is_source_stopped(self) -> bool:
        """``RUNNING`` and not moving: a source stopped mid-read. :attr:`feed_stop` says why."""
        return self.feed == "STOPPED"

    @property
    def is_sink_detached(self) -> bool:
        """The sink refused a batch and was detached (``PRV-8009``).

        The query is still ``RUNNING`` and its view is still right; nothing more reaches the
        sink. :attr:`sink_failure` says what happened.
        """
        return self.sink_state == "DETACHED"

    def __str__(self) -> str:
        return f"{self.name} [{self.state}, {self.fingerprint}, {self.rows_in} rows]"


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
