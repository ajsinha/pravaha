"""The time-travel debugger's types, and how they are read off the control wire.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Split out of :mod:`pravaha.client` because the parsing is most of the code and none of
it needs a connection: a session's status and a step's report are positional rows, and
turning one into a dataclass is a pure function that is easier to test on its own.

The layout mirrors the Java SDK's, field for field, which is the promise the two clients
make to a team running both (ADR-047).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional, Sequence

# The time-travel debugger's actions (ADR-048). One per verb: a debugger is a conversation,
# and a single action carrying a verb field would hide the routing in the body.
_ACTION_DEBUG_FORK = "pravaha.debug.fork"
_ACTION_DEBUG_SESSION = "pravaha.debug.session"
_ACTION_DEBUG_STEP = "pravaha.debug.step"
_ACTION_DEBUG_STATE = "pravaha.debug.state"
_ACTION_DEBUG_INSPECT = "pravaha.debug.inspect"
_ACTION_DEBUG_VIEW = "pravaha.debug.view"
_ACTION_DEBUG_EXPORT = "pravaha.debug.export"
_ACTION_DEBUG_END = "pravaha.debug.end"
_ACTION_DEBUG_CHECKPOINTS = "pravaha.debug.checkpoints"


def _at(row: Sequence[str], index: int) -> str:
    """One field, or empty past the end.

    The wire is append-only: a server adds a field and an older client keeps reading the
    ones it knows. That promise only holds if reading past the end is not an error.
    """
    return row[index] if index < len(row) else ""


def _number(text: str) -> int:
    try:
        return int(text)
    except ValueError:
        return 0


def _optional_number(text: str) -> Optional[int]:
    """A number, or ``None`` when the field is empty -- "no watermark" is not "zero"."""
    if not text.strip():
        return None
    try:
        return int(text)
    except ValueError:
        return None


@dataclass(frozen=True)
class DebugSession:
    """A debug session as the server reports it (ADR-047).

    A fork of a query from one of its checkpoints, reading the same sources from the
    offsets that checkpoint recorded, with every sink disabled and nothing able to read
    its view. It costs what the query costs, so it is bounded and it expires.
    """

    id: str
    query: str
    sql: str
    checkpoint_id: int
    owner: str
    started_at: str
    #: When it was last stepped or read, which is what its expiry is measured from.
    last_used_at: str
    steps: int
    rows_consumed: int
    view_size: int
    #: Where event time stands, or ``None`` when no step has moved it.
    watermark_nanos: Optional[int]
    #: Always true, and carried anyway: a screen has to be able to say so permanently.
    sinks_disabled: bool
    streams: "tuple[str, ...]"


@dataclass(frozen=True)
class InputRow:
    """One row that entered the query at a step, as the source had it."""

    stream: str
    partition: int
    offset: str
    #: Negative withdraws a row: a delete, or the old half of an update.
    weight: int
    event_time_nanos: int
    values: "tuple[str, ...]"


@dataclass(frozen=True)
class OperatorFlow:
    """What one operator of the plan saw and produced during a step."""

    id: str
    kind: str
    label: str
    rows_in: int
    rows_out: int


@dataclass(frozen=True)
class ViewDelta:
    """One change to the fork's view. A negative weight withdraws a row."""

    weight: int
    values: "tuple[str, ...]"


@dataclass(frozen=True)
class DebugStep:
    """What one step did.

    One answer rather than four calls: the input rows, the operator flows, the view's
    changes and the watermark are four panels of one moment.
    """

    session: str
    sequence: int
    #: ``ROW``, ``ROWS``, ``COMMIT``, ``WATERMARK`` or ``UNTIL``.
    kind: str
    rows_in: "tuple[InputRow, ...]"
    operators: "tuple[OperatorFlow, ...]"
    view_changes: "tuple[ViewDelta, ...]"
    watermark_nanos: Optional[int]
    rows_consumed: int
    view_size: int
    #: Whether the sources had no more rows to give.
    exhausted: bool
    #: Why the step stopped, in words.
    stopped: str


@dataclass(frozen=True)
class StateSlot:
    """One piece of state a fork holds, and how much of it."""

    id: str
    kind: str
    label: str
    entries: int


@dataclass(frozen=True)
class StateEntry:
    """One key's state: a join's row, a group's accumulators, a window's contents."""

    key: str
    values: dict = field(default_factory=dict)


@dataclass(frozen=True)
class StatePage:
    """One bounded page of one operator's state."""

    id: str
    kind: str
    key: Optional[str]
    offset: int
    limit: int
    total: int
    entries: "tuple[StateEntry, ...]"

    @property
    def has_more(self) -> bool:
        return self.offset + len(self.entries) < self.total


@dataclass(frozen=True)
class Fixture:
    """A generated JUnit test: what to call it, where it goes, and what is in it."""

    class_name: str
    path: str
    source: str


def read_session(row: Sequence[str]) -> DebugSession:
    """A session's status, from the wire's positional fields."""
    streams = _at(row, 12).strip()
    return DebugSession(
        id=_at(row, 0),
        query=_at(row, 1),
        sql=_at(row, 2),
        checkpoint_id=_number(_at(row, 3)),
        owner=_at(row, 4),
        started_at=_at(row, 5),
        last_used_at=_at(row, 6),
        steps=_number(_at(row, 7)),
        rows_consumed=_number(_at(row, 8)),
        view_size=_number(_at(row, 9)),
        watermark_nanos=_optional_number(_at(row, 10)),
        sinks_disabled=_at(row, 11) == "true",
        streams=tuple(streams.split(",")) if streams else (),
    )


def read_step(row: Sequence[str]) -> DebugStep:
    """A step's report.

    The layout is the fixed fields, then each list as a count followed by that many
    groups. A cursor rather than fixed indexes, because the lists are variable length
    and the next one starts wherever the last ended -- the price of a flat wire, paid
    here once rather than by every caller.
    """
    at = 8

    rows: "list[InputRow]" = []
    count = _number(_at(row, at))
    at += 1
    for _ in range(count):
        stream, partition, offset = _at(row, at), _number(_at(row, at + 1)), _at(row, at + 2)
        weight, event_time = _number(_at(row, at + 3)), _number(_at(row, at + 4))
        at += 5
        columns = _number(_at(row, at))
        at += 1
        values = tuple(_at(row, at + i) for i in range(columns))
        at += columns
        rows.append(InputRow(stream, partition, offset, weight, event_time, values))

    operators: "list[OperatorFlow]" = []
    count = _number(_at(row, at))
    at += 1
    for _ in range(count):
        operators.append(
            OperatorFlow(
                _at(row, at),
                _at(row, at + 1),
                _at(row, at + 2),
                _number(_at(row, at + 3)),
                _number(_at(row, at + 4)),
            )
        )
        at += 5

    changes: "list[ViewDelta]" = []
    count = _number(_at(row, at))
    at += 1
    for _ in range(count):
        weight = _number(_at(row, at))
        at += 1
        columns = _number(_at(row, at))
        at += 1
        changes.append(ViewDelta(weight, tuple(_at(row, at + i) for i in range(columns))))
        at += columns

    return DebugStep(
        session=_at(row, 0),
        sequence=_number(_at(row, 1)),
        kind=_at(row, 2),
        rows_in=tuple(rows),
        operators=tuple(operators),
        view_changes=tuple(changes),
        watermark_nanos=_optional_number(_at(row, 3)),
        rows_consumed=_number(_at(row, 4)),
        view_size=_number(_at(row, 5)),
        exhausted=_at(row, 6) == "true",
        stopped=_at(row, 7),
    )


def read_page(row: Sequence[str]) -> StatePage:
    """A page of operator state."""
    at = 7
    entries: "list[StateEntry]" = []
    for _ in range(_number(_at(row, 6))):
        key = _at(row, at)
        at += 1
        columns = _number(_at(row, at))
        at += 1
        values = {}
        for _column in range(columns):
            values[_at(row, at)] = _at(row, at + 1)
            at += 2
        entries.append(StateEntry(key, values))
    key_filter = _at(row, 2)
    return StatePage(
        id=_at(row, 0),
        kind=_at(row, 1),
        key=key_filter or None,
        offset=_number(_at(row, 3)),
        limit=_number(_at(row, 4)),
        total=_number(_at(row, 5)),
        entries=tuple(entries),
    )


def read_state(row: Sequence[str]) -> StateSlot:
    """One inspectable slot."""
    return StateSlot(_at(row, 0), _at(row, 1), _at(row, 2), _number(_at(row, 3)))


def read_view(row: Sequence[str]) -> ViewDelta:
    """One row of a fork's view: its weight, then its columns."""
    return ViewDelta(_number(_at(row, 0)), tuple(row[1:]))


class DebugCommands:
    """The nine debug verbs, mixed into :class:`pravaha.client.Client` (ADR-048).

    A mixin rather than nine more methods on ``Client``, because ``client.py`` is already
    the largest file in this SDK and the debugger's types are here: a reader looking at
    :class:`DebugStep` finds the call that returns one in the same file.

    It calls ``self._act``, the client's own control-action helper, which is the whole of
    what it needs from its host -- so this class is about the debugger's protocol and
    nothing about connecting.
    """

    def debug_fork(self, query: str, checkpoint_id: "Optional[int]" = None) -> "DebugSession":
        """Forks a debug session from a query's checkpoint (ADR-047).

        A second copy of the query, restored from that checkpoint and reading the same
        sources from the offsets it recorded, with **every sink disabled** and nothing
        able to read its view -- the live query, its view and its subscribers are
        untouched. Omit ``checkpoint_id`` for the newest retained one;
        :meth:`debug_checkpoints` says which there are.

        Refused by name: ``PRV-8011`` when there is no checkpoint to fork from,
        ``PRV-8012`` when a source cannot be rewound to it, ``PRV-8014`` when this node
        already holds as many sessions as it allows. Needs the administer permission.
        """
        fields = [query, "" if checkpoint_id is None else str(int(checkpoint_id))]
        rows = self._act(_ACTION_DEBUG_FORK, fields)
        if not rows:
            raise _query_error("the server accepted the fork and said nothing about the session")
        return read_session(rows[0])

    def debug_checkpoints(self, query: str) -> "list[int]":
        """Which checkpoints of ``query`` a session could be forked from, newest first."""
        rows = self._act(_ACTION_DEBUG_CHECKPOINTS, [query])
        # Field 0 is the query's name, so the ids start at 1.
        return [int(value) for value in rows[0][1:]] if rows else []

    def debug_step(self, session_id: str, step: str = "row") -> "DebugStep":
        """Advances a session and reports what changed.

        ``step`` is ``row``, ``rows:N``, ``commit``, ``watermark:<nanos>`` or
        ``until:<column>:<op>:<value>``. The report carries the rows that entered, what
        each operator did with them, the view's changes with their weights, and where
        event time stands -- one answer, because a screen showing four would be showing
        four moments.
        """
        rows = self._act(_ACTION_DEBUG_STEP, [session_id, step])
        if not rows:
            raise _query_error("the server accepted the step and said nothing about what it did")
        return read_step(rows[0])

    def debug_sessions(self) -> "list[DebugSession]":
        """Every debug session on this server that you may administer."""
        return [read_session(row) for row in self._act(_ACTION_DEBUG_SESSION, [""])]

    def debug_session(self, session_id: str) -> "Optional[DebugSession]":
        """One session, or ``None`` when the server does not know it."""
        rows = self._act(_ACTION_DEBUG_SESSION, [session_id])
        return read_session(rows[0]) if rows else None

    def debug_state(self, session_id: str) -> "list[StateSlot]":
        """What state this session's fork holds, and how much of each."""
        return [read_state(row) for row in self._act(_ACTION_DEBUG_STATE, [session_id])]

    def debug_inspect(
        self,
        session_id: str,
        operator: str,
        key: "Optional[str]" = None,
        offset: int = 0,
        limit: int = 50,
    ) -> "StatePage":
        """One page of one operator's state, read without changing it.

        Bounded on purpose: ``PRV-8015`` past the page ceiling rather than a request
        that builds a million rendered rows.
        """
        rows = self._act(
            _ACTION_DEBUG_INSPECT,
            [session_id, operator, key or "", str(int(offset)), str(int(limit))],
        )
        if not rows:
            raise _query_error(f"the server answered no page for {operator!r}")
        return read_page(rows[0])

    def debug_view(self, session_id: str) -> "list[ViewDelta]":
        """The fork's own view, which nothing outside the session can read."""
        return [read_view(row) for row in self._act(_ACTION_DEBUG_VIEW, [session_id])]

    def debug_export(self, session_id: str, name: str) -> "Fixture":
        """Writes the session out as a JUnit test the repository can run offline.

        ``name`` is turned into a class name: "bob goes negative" becomes
        ``BobGoesNegativeFixtureTest``. The generated file replays the rows the session
        stepped, from empty state, and asserts the view -- which is what makes a
        production incident a permanent regression test.
        """
        rows = self._act(_ACTION_DEBUG_EXPORT, [session_id, name])
        if not rows:
            raise _query_error("the server accepted the export and returned no fixture")
        row = rows[0]
        return Fixture(class_name=row[0], path=row[1] if len(row) > 1 else "", source=row[2] if len(row) > 2 else "")

    def debug_end(self, session_id: str) -> None:
        """Ends a session and releases its fork."""
        self._act(_ACTION_DEBUG_END, [session_id])


def _query_error(message: str) -> Exception:
    """The client's own ``QueryError``, imported where it is raised.

    At module level the import would be circular -- ``client`` imports these types -- and a
    circular import that only breaks when the debugger is used is worse than a local import.
    """
    from pravaha.client import QueryError

    return QueryError(message)
