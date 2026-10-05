"""How the ``pravaha`` command prints: aligned tables for a person, JSON for a script.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

One rule holds everywhere: **stdout carries the answer and stderr carries the notes.** A banner,
a hint or a warning goes to stderr, so ``pravaha queries --json | jq`` and
``pravaha query --tsv ... > out.tsv`` read only what was asked for.
"""

from __future__ import annotations

import base64
import dataclasses
import datetime
import decimal
import json
import os
import sys
from typing import Any, Iterable, Mapping, Optional, Sequence, TextIO, Tuple, Union

#: A column: the key to read from each row, and the header to print over it.
Column = Union[str, Tuple[str, str]]


def plain_decimal(value: decimal.Decimal) -> str:
    """A decimal as its digits at its scale: ``0.0000000000``, never ``0E-10``.

    A ``DECIMAL`` column arrives as :class:`decimal.Decimal` at the column's scale
    (FLIGHTDECIMAL-1), and ``str`` writes a small or zero one in exponent form -- the same
    number, in a form a ledger's reader does not expect and ``cut`` or a spreadsheet may not parse.
    """
    return format(value, "f")


def as_text(value: Any) -> str:
    """One value as plain text, for TSV: ``str``, except that a decimal keeps its digits."""
    return plain_decimal(value) if isinstance(value, decimal.Decimal) else str(value)


def _json_default(value: Any) -> Any:
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {field.name: getattr(value, field.name) for field in dataclasses.fields(value)}
    if isinstance(value, (bytes, bytearray, memoryview)):
        # A record's bytes may not be text; base64 is the one lossless way to put them in JSON.
        return base64.b64encode(bytes(value)).decode("ascii")
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return value.isoformat()
    if isinstance(value, datetime.timedelta):
        return value.total_seconds()
    if isinstance(value, decimal.Decimal):
        return plain_decimal(value)
    if isinstance(value, (set, frozenset, tuple)):
        return list(value)
    return str(value)


def to_json(value: Any, *, indent: Optional[int] = 2) -> str:
    """``value`` as JSON: dataclasses as objects, bytes as base64, instants as ISO-8601."""
    return json.dumps(value, indent=indent, default=_json_default, ensure_ascii=False)


def cell(value: Any) -> str:
    """How one value prints in a table: ``-`` for nothing, lists comma-joined."""
    if value is None or value == "":
        return "-"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, (list, tuple, set, frozenset)):
        return ",".join(cell(item) for item in value) or "-"
    if isinstance(value, dict):
        return json.dumps(value, default=_json_default, ensure_ascii=False)
    if isinstance(value, decimal.Decimal):
        return plain_decimal(value)
    return str(value)


def _reads(row: Any, key: str) -> Any:
    if isinstance(row, Mapping):
        return row.get(key)
    return getattr(row, key, None)


def format_table(rows: Iterable[Any], columns: Sequence[Column]) -> "list[str]":
    """Rows under upper-case headers, each column as wide as its widest cell.

    A row is a mapping or an object with the named attributes. A column is a key, whose header
    is the key upper-cased, or ``(key, header)``.
    """
    keys = [c if isinstance(c, str) else c[0] for c in columns]
    headers = [c.upper() if isinstance(c, str) else c[1] for c in columns]
    lines = [headers] + [[cell(_reads(row, key)) for key in keys] for row in rows]
    widths = [max(len(line[i]) for line in lines) for i in range(len(keys))]
    return [
        "  ".join(text.ljust(widths[i]) for i, text in enumerate(line)).rstrip() for line in lines
    ]


class Output:
    """Where a command writes, and in which form."""

    def __init__(
        self,
        *,
        json_mode: bool = False,
        out: Optional[TextIO] = None,
        err: Optional[TextIO] = None,
        color: Optional[bool] = None,
    ) -> None:
        self.json_mode = json_mode
        self.out = out if out is not None else sys.stdout
        self.err = err if err is not None else sys.stderr
        if color is None:
            color = (
                not os.environ.get("NO_COLOR")
                and hasattr(self.out, "isatty")
                and self.out.isatty()
            )
        self.color = bool(color)

    # ------------------------------------------------------------------ styling

    def _style(self, code: str, text: str) -> str:
        return f"\033[{code}m{text}\033[0m" if self.color else text

    def bold(self, text: str) -> str:
        return self._style("1", text)

    def dim(self, text: str) -> str:
        return self._style("2", text)

    def good(self, text: str) -> str:
        return self._style("32", text)

    def bad(self, text: str) -> str:
        return self._style("31", text)

    def caution(self, text: str) -> str:
        return self._style("33", text)

    # ------------------------------------------------------------------ writing

    def line(self, text: str = "") -> None:
        print(text, file=self.out)

    def note(self, text: str) -> None:
        """A remark for a person. Always stderr, so it never mixes into an answer."""
        print(self.dim(text) if self.color else text, file=self.err)

    def warn(self, text: str) -> None:
        print(self.bad(text) if self.color else text, file=self.err)

    def json(self, value: Any) -> None:
        print(to_json(value), file=self.out)

    def json_line(self, value: Any) -> None:
        print(to_json(value, indent=None), file=self.out, flush=True)

    def table(self, rows: Iterable[Any], columns: Sequence[Column]) -> None:
        lines = format_table(list(rows), columns)
        self.line(self.bold(lines[0]))
        for text in lines[1:]:
            self.line(text)

    def fields(self, pairs: Sequence[Tuple[str, Any]]) -> None:
        """A record as ``label  value`` lines, labels aligned."""
        width = max((len(label) for label, _ in pairs), default=0)
        for label, value in pairs:
            self.line(self.bold(label.ljust(width)) + "  " + cell(value))

    def result(
        self,
        value: Any,
        rows: Optional[Iterable[Any]] = None,
        columns: Optional[Sequence[Column]] = None,
        empty: str = "",
    ) -> None:
        """``value`` as JSON in ``--json`` mode; otherwise ``rows`` as a table, or ``empty``."""
        if self.json_mode:
            self.json(value)
            return
        listed = list(rows if rows is not None else value)
        if not listed and empty:
            self.note(empty)
            return
        self.table(listed, columns or [])
