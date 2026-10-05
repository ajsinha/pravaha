"""``pravaha top``: the node's continuous queries, live -- rows a second, watermark delay, state and
subscribers -- redrawn every ``--interval`` seconds.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Every number is the HTTP API's: ``GET /api/v1/queries`` for name, state, lane and rows in, and each
query's ``GET /api/v1/queries/{name}/plan`` for what the engine measures for it as a whole (``query``:
state held and its ceiling, view rows, watermark, subscribers, inbox) and its operators' state bytes.
A rate is the difference of two samples over the time between them, so the first frame of a live
view has none; ``--once`` takes two samples ``--interval`` apart and prints one frame (as JSON with
``--json``). The watermark delay is how far the query's event-time watermark trails this machine's
clock: data that is late or a source that has stopped shows as a delay that grows.

On a terminal the screen is cleared and redrawn with plain ANSI and the cursor hidden; anywhere else
each frame is printed after the last with no escape codes, and ``--json`` prints one JSON line a
frame. Ctrl-C ends it with exit 0 and the cursor shown again.
"""

from __future__ import annotations

import datetime
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from pravaha.cli._common import EXIT_OK, Context, UsageError
from pravaha.cli._output import format_table

#: What ``--sort`` takes, and how: by name ascending, every number largest first.
SORTS = ("name", "rate", "rows", "lag", "state", "subs")
_CLEAR = "\033[H\033[2J"
_HIDE = "\033[?25l"
_SHOW = "\033[?25h"

# Indirections a test replaces, so two samples need no real wait.
_sleep: Callable[[float], None] = time.sleep
_clock: Callable[[], float] = time.monotonic


def _now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


@dataclass
class Sample:
    """One reading of every query, and when it was taken (monotonic seconds)."""

    at: float
    rows: "dict[str, dict[str, Any]]" = field(default_factory=dict)


def _instant(text: Any) -> Optional[datetime.datetime]:
    if not text:
        return None
    try:
        trimmed = str(text).replace("Z", "+00:00")
        if "." in trimmed:  # Python 3.9 takes at most six fractional digits; Java writes nine
            head, _, tail = trimmed.partition(".")
            digits = "".join(c for c in tail if c.isdigit())
            trimmed = f"{head}.{digits[:6]}{tail[len(digits):]}"
        return datetime.datetime.fromisoformat(trimmed)
    except ValueError:
        return None


def sample(ctx: Context) -> Sample:
    """Reads the node once: the query list, then each query's plan for its numbers."""
    at = _clock()
    now = _now()
    taken = Sample(at)
    for query in ctx.api.describe_queries():
        name = str(query.get("name"))
        withheld = bool(query.get("countsWithheld"))
        row: "dict[str, Any]" = {
            "name": name,
            "state": query.get("state"),
            "lane": query.get("lane") if query.get("sharedLane") is None
            else f"{query.get('lane')} {query.get('sharedLane')}",
            "rowsIn": None if withheld else query.get("rowsIn"),
            "rowsPerSecond": None,
            "watermark": None,
            "watermarkDelaySeconds": None,
            "stateHeld": None,
            "stateCeiling": None,
            "stateBytes": None,
            "viewRows": None,
            "subscribers": None,
        }
        try:
            plan = ctx.api.query_plan(name)
        except Exception:  # dropped between the two calls, or not ours to see: the list row stands
            plan = {}
        numbers = plan.get("query") or {}
        watermark = numbers.get("watermark")
        row["watermark"] = watermark
        instant = _instant(watermark)
        if instant is not None:
            row["watermarkDelaySeconds"] = round((now - instant).total_seconds(), 1)
        for key in ("stateHeld", "stateCeiling", "viewSize", "subscribers"):
            value = numbers.get(key)
            if isinstance(value, (int, float)) and value >= 0:
                row["viewRows" if key == "viewSize" else key] = value
        operators = plan.get("operatorMetrics") or {}
        sizes = [m.get("stateBytes") for m in operators.values() if m.get("stateBytes") is not None]
        if sizes:
            row["stateBytes"] = sum(sizes)
        taken.rows[name] = row
    return taken


def with_rates(previous: Optional[Sample], current: Sample) -> Sample:
    """``current`` with each query's rows a second since ``previous``, where both counted it."""
    if previous is None or current.at <= previous.at:
        return current
    elapsed = current.at - previous.at
    for name, row in current.rows.items():
        before = previous.rows.get(name, {}).get("rowsIn")
        now = row.get("rowsIn")
        if isinstance(before, int) and isinstance(now, int) and now >= before:
            row["rowsPerSecond"] = round((now - before) / elapsed, 1)
    return current


def _order(rows: "list[dict[str, Any]]", by: str) -> "list[dict[str, Any]]":
    if by == "name":
        return sorted(rows, key=lambda r: r["name"])
    key = {"rate": "rowsPerSecond", "rows": "rowsIn", "lag": "watermarkDelaySeconds",
           "state": "stateHeld", "subs": "subscribers"}[by]
    return sorted(rows, key=lambda r: (r.get(key) is None, -(r.get(key) or 0), r["name"]))


def _bytes(value: Any) -> Optional[str]:
    if value is None:
        return None
    size = float(value)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if size < 1024 or unit == "GiB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return None  # pragma: no cover


def _delay(seconds: Any) -> Optional[str]:
    if seconds is None:
        return None
    seconds = float(seconds)
    if abs(seconds) < 120:
        return f"{seconds:.1f}s"
    if abs(seconds) < 7200:
        return f"{seconds / 60:.0f}m"
    if abs(seconds) < 172800:
        return f"{seconds / 3600:.0f}h"
    return f"{seconds / 86400:.0f}d"


def frame_lines(current: Sample, by: str) -> "list[str]":
    """The table, a row per query, as text lines."""
    shown = []
    for row in _order(list(current.rows.values()), by):
        held, ceiling = row.get("stateHeld"), row.get("stateCeiling")
        state = None if held is None else (f"{held}/{ceiling}" if ceiling else str(held))
        shown.append({**row, "rate": row.get("rowsPerSecond"), "delay": _delay(row.get("watermarkDelaySeconds")),
                      "held": state, "bytes": _bytes(row.get("stateBytes"))})
    return format_table(shown, [
        ("name", "NAME"), ("state", "STATE"), ("lane", "LANE"), ("rowsIn", "ROWS IN"),
        ("rate", "ROWS/S"), ("delay", "WM DELAY"), ("held", "STATE ROWS"), ("bytes", "STATE BYTES"),
        ("viewRows", "VIEW ROWS"), ("subscribers", "SUBS"),
    ])


def _header(ctx: Context, current: Sample, interval: float, live: bool) -> str:
    count = len(current.rows)
    noun = "query" if count == 1 else "queries"
    words = f"pravaha top  {ctx.settings.http}  {count} {noun}  sorted by {ctx.arg('sort', 'name')}"
    if live:
        words += f"  every {interval:g}s  {_now().strftime('%H:%M:%S')}  (Ctrl-C to stop)"
    return words


def top(ctx: Context) -> int:
    interval = float(ctx.arg("interval", 2.0))
    if interval <= 0:
        raise UsageError(f"--interval must be more than 0 seconds, got {interval:g}")
    by = str(ctx.arg("sort", "name"))
    out = ctx.out
    if ctx.arg("once", False):
        first = sample(ctx)
        _sleep(interval)
        current = with_rates(first, sample(ctx))
        if out.json_mode:
            out.json({"intervalSeconds": interval, "sort": by,
                      "queries": _order(list(current.rows.values()), by)})
        else:
            out.line(_header(ctx, current, interval, live=False))
            lines = frame_lines(current, by)
            out.line(out.bold(lines[0]))
            for line in lines[1:]:
                out.line(line)
        return EXIT_OK
    terminal = not out.json_mode and hasattr(out.out, "isatty") and out.out.isatty()
    previous: Optional[Sample] = None
    try:
        if terminal:
            out.out.write(_HIDE)
        while True:
            current = with_rates(previous, sample(ctx))
            if out.json_mode:
                out.json_line({"intervalSeconds": interval, "sort": by,
                               "queries": _order(list(current.rows.values()), by)})
            else:
                lines = [_header(ctx, current, interval, live=True), *frame_lines(current, by)]
                if terminal:
                    out.out.write(_CLEAR + "\n".join(lines) + "\n")
                    out.out.flush()
                else:
                    out.line("\n".join(lines))
                    out.line()
            previous = current
            _sleep(interval)
    except KeyboardInterrupt:
        return EXIT_OK
    finally:
        if terminal:
            out.out.write(_SHOW)
            out.out.flush()
