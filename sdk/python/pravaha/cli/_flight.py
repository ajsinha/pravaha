"""The ``pravaha`` commands that speak Arrow Flight: reads, registrations, lifecycle,
replacement, subscriptions, dead letters and the debugger.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Every call goes through :class:`pravaha.client.Client`; nothing here frames a message of its
own. Names and flags are the ones the Java CLI had, so a page written for it still reads right.
"""

from __future__ import annotations

import dataclasses
import pathlib
import time
from typing import TYPE_CHECKING, Any, Optional

from pravaha.cli._common import (
    EXIT_OK,
    EXIT_REFUSED,
    Context,
    UsageError,
    csv,
    ints,
)
from pravaha.errors import docs_base_url, help_line

if TYPE_CHECKING:
    from pravaha.client import RegisteredQuery, Replacement


# ---------------------------------------------------------------------------------- reads


def coerce(value: str) -> Any:
    """A ``--params`` value: an integer if it reads as one, then a decimal, otherwise text."""
    try:
        return int(value)
    except ValueError:
        pass
    try:
        return float(value)
    except ValueError:
        return value


def query(ctx: Context) -> int:
    sql = ctx.sql()
    params = [coerce(v.strip()) for v in ctx.arg("params").split(",")] if ctx.arg("params") else []
    result = ctx.client.query(sql, params or None)
    columns = result.columns
    rows = [list(row) for row in result]
    if ctx.out.json_mode:
        ctx.out.json([dict(zip(columns, row)) for row in rows])
        return EXIT_OK
    if ctx.arg("tsv"):
        ctx.out.line("\t".join(columns))
        for row in rows:
            ctx.out.line("\t".join("NULL" if v is None else str(v) for v in row))
    else:
        text = [{c: ("NULL" if v is None else v) for c, v in zip(columns, row)} for row in rows]
        ctx.out.table(text, [(c, c) for c in columns])
    ctx.out.note(f"{len(rows)} row{'' if len(rows) == 1 else 's'}")
    return EXIT_OK


# ---------------------------------------------------------------------------------- registration


def register(ctx: Context) -> int:
    if ctx.arg("param") is not None or ctx.arg("params") is not None:
        raise UsageError(
            "register takes no parameters: the server's register action cannot carry bound "
            "values. Write the value into the SQL, or register once without it and filter by "
            "that column at read time (subscribe --filter, or query with --params)"
        )
    name = ctx.require("name")
    sql = ctx.sql()
    keys = ints(ctx.arg("keys", "0"), "--keys")
    sink = (ctx.arg("sink") or "").strip() or None
    retain = (ctx.arg("retain") or "").strip() or None
    registered = ctx.client.register(name, sql, keys, sink=sink, retention=retain)
    if ctx.out.json_mode:
        ctx.out.json(registered)
        return EXIT_OK
    extra = (f"  sink={sink}" if sink else "") + (f"  retain={retain}" if retain else "")
    ctx.out.line(
        ctx.out.good("registered ")
        + registered.name
        + ctx.out.dim(f"  state={registered.state}  fingerprint={registered.fingerprint}{extra}")
    )
    ctx.out.note("a query with the same fingerprint is the same computation, shared")
    return EXIT_OK


def _state_text(query: "RegisteredQuery") -> str:
    return f"{query.state} (source stopped)" if query.is_source_stopped else query.state


def _sink_text(query: "RegisteredQuery") -> str:
    if not query.sink:
        return "-"
    return f"{query.sink} (detached)" if query.is_sink_detached else query.sink


def stop_text(query: "RegisteredQuery") -> str:
    stop = query.feed_stop
    if stop is None:
        return "a source stopped, and this server did not say why"
    text = f"source stopped with {stop.code}"
    if stop.where:
        text += f" reading {stop.where}"
    if stop.at:
        text += f" at {stop.at}"
    if stop.message:
        text += f": {stop.message}"
    return text


def sink_failure_text(query: "RegisteredQuery") -> str:
    failure = query.sink_failure
    if failure is None:
        return f"the sink '{query.sink}' was detached, and this server did not say why"
    text = f"sink '{query.sink}' detached with {failure.code}"
    return text + (f": {failure.message}" if failure.message else "")


def _code_lookup_sentence() -> str:
    base = docs_base_url()
    if base:
        return f"Each code has a help page: {base}<code>"
    return "Look each code up in the console's help under Errors, or in docs/TROUBLESHOOTING.md."


def queries(ctx: Context) -> int:
    listed = ctx.client.queries()
    if ctx.out.json_mode:
        ctx.out.json(listed)
        return EXIT_OK
    if not listed:
        ctx.out.note("no continuous queries are registered")
        return EXIT_OK
    verbose = bool(ctx.arg("verbose"))
    rows = [
        {
            "name": q.name,
            "state": _state_text(q),
            "fingerprint": q.fingerprint,
            "rows_in": "-" if q.rows_in < 0 else q.rows_in,
            "sink": _sink_text(q),
            "feed": q.feed or "-",
        }
        for q in listed
    ]
    columns: list[Any] = ["name", "state", "fingerprint", ("rows_in", "ROWS IN"), "sink"]
    if verbose:
        columns.append("feed")
    ctx.out.table(rows, columns)
    if any(q.rows_in < 0 for q in listed):
        ctx.out.note(
            "a '-' under ROWS IN means the server did not disclose the count: your access to "
            "that view is a filtered subset of its rows, and its total is not part of what you "
            "may see"
        )
    stopped = [q for q in listed if q.is_source_stopped]
    for q in stopped:
        ctx.out.warn(f"{q.name}: {stop_text(q)}")
    if stopped:
        ctx.out.note(
            "a stopped source is not retried: the view keeps answering at the frontier it "
            "reached. Fix the cause, then drop the query and register it again, or restart the "
            "node. " + _code_lookup_sentence()
        )
    detached = [q for q in listed if q.is_sink_detached]
    for q in detached:
        ctx.out.warn(f"{q.name}: {sink_failure_text(q)}")
    if detached:
        ctx.out.note(
            "a detached sink is not retried either, and the query and its view carry on and stay "
            "right. Fix the cause, then drop the query and register it again: the sink is sent "
            "the view's whole contents first, so nothing written while it was detached is lost"
        )
    return EXIT_OK


# ---------------------------------------------------------------------------------- lifecycle

_PAST = {"drop": "dropped", "pause": "paused", "resume": "resumed"}


def lifecycle(ctx: Context) -> int:
    action = ctx.args.command
    name = ctx.require("name")
    if action == "drop" and not ctx.confirmed():
        return _drop_preview(ctx, name)
    getattr(ctx.client, action)(name)
    if ctx.out.json_mode:
        ctx.out.json({"name": name, "action": action, "done": True})
    else:
        ctx.out.line(ctx.out.good(_PAST[action] + " ") + name)
    return EXIT_OK


def _drop_preview(ctx: Context, name: str) -> int:
    """What ``drop`` would do, and nothing else: a name is not dropped without ``--yes``."""
    listed = ctx.client.queries()
    target = next((q for q in listed if q.name == name), None)
    if target is None:
        ctx.out.warn(
            f"no continuous query named '{name}' is visible to you here; `drop --yes` would be "
            "refused (PRV-8002)"
        )
        return EXIT_REFUSED
    sharing = [q.name for q in listed if q.fingerprint == target.fingerprint and q.name != name]
    plan = {
        "name": name,
        "action": "drop",
        "done": False,
        "state": target.state,
        "fingerprint": target.fingerprint,
        "sharedWith": sharing,
        "releasesComputation": not sharing,
    }
    if ctx.out.json_mode:
        ctx.out.json(plan)
        return EXIT_OK
    ctx.out.line(f"would drop {name}  state={target.state}  fingerprint={target.fingerprint}")
    if sharing:
        ctx.out.line(
            "  the computation keeps running: it is shared with " + ", ".join(sharing)
        )
    else:
        ctx.out.line("  it is the computation's last name, so the computation and its view go too")
    ctx.out.note(f"nothing was dropped; run `pravaha drop --name {name} --yes` to drop it")
    return EXIT_OK


# ---------------------------------------------------------------------------------- replacement


def _print_replacement(ctx: Context, r: "Replacement") -> None:
    out = ctx.out
    out.line(
        out.good(r.state.lower().replace("_", " "))
        + f" {r.name}"
        + out.dim(f"  candidate={r.candidate or '-'}  replacing={r.replacing or '-'}")
    )
    progress = f"  history {r.history_rows} rows"
    if r.partitions > 0:
        progress += f", {r.partitions_live} of {r.partitions} partitions on the live stream"
    progress += f", {r.rows_per_second} rows/s"
    if r.rate_limit > 0:
        progress += f" (limit {r.rate_limit})"
    if r.paused:
        progress += ", paused"
    progress += f", lag {r.lag_nanos // 1_000_000} ms"
    out.line(progress)
    if r.rollback_available:
        out.line(f"  the version it replaced is retained until {r.rollback_until}")
    if r.failure:
        out.warn(f"{r.failure_code or ''}  {r.failure}".strip())


def replace(ctx: Context) -> int:
    name = ctx.require("name")
    sql = ctx.sql()
    keys = ints(ctx.arg("keys", "0"), "--keys")
    rate = ctx.arg("rate_limit")
    replacement = ctx.client.replace(
        name,
        sql,
        keys,
        backfill=ctx.arg("backfill"),
        rate_limit=int(rate) if rate is not None else None,
        cutover=ctx.arg("cutover"),
        rollback_retention=ctx.arg("rollback_retention"),
    )
    shown = [replacement]
    if ctx.arg("wait"):
        replacement = _await_caught_up(ctx, name)
        shown.append(replacement)
    if ctx.out.json_mode:
        ctx.out.json(replacement)
        return EXIT_OK
    for each in shown:
        _print_replacement(ctx, each)
    ctx.out.note(
        f"'{name}' still answers the version it answered before; "
        f"`pravaha cutover --name {name}` is what moves it"
    )
    return EXIT_OK


def _await_caught_up(ctx: Context, name: str) -> "Replacement":
    deadline = time.monotonic() + 24 * 3600
    while True:
        current = ctx.client.replacement(name)
        if current is None:
            raise RuntimeError(f"'{name}' is no longer being replaced")
        if current.state != "BACKFILLING" or time.monotonic() > deadline:
            return current
        time.sleep(0.25)


_VERBS = {
    "cutover": "cut_over",
    "rollback": "roll_back",
    "abandon": "abandon_replacement",
    "finish": "finish_replacement",
    "pause-backfill": "pause_backfill",
    "resume-backfill": "resume_backfill",
}

_IRREVERSIBLE = {
    "abandon": "abandon the replacement of {name}: the candidate version is released and its "
    "backfill is lost",
    "finish": "finish the replacement of {name}: the version it replaced is released and there "
    "is no rollback after this",
}


def replacement_verb(ctx: Context) -> int:
    verb = ctx.args.command
    name = ctx.require("name")
    if verb in _IRREVERSIBLE and not ctx.confirmed():
        current = ctx.client.replacement(name)
        what = _IRREVERSIBLE[verb].format(name=name)
        if ctx.out.json_mode:
            ctx.out.json({"name": name, "action": verb, "done": False, "replacement": current})
            return EXIT_OK
        ctx.out.line(f"would {what}")
        if current is not None:
            _print_replacement(ctx, current)
        ctx.out.note(f"nothing was changed; run `pravaha {verb} --name {name} --yes` to do it")
        return EXIT_OK
    if verb == "throttle":
        rate = ctx.require("rate")
        result = ctx.client.throttle_backfill(name, int(rate))
    else:
        result = getattr(ctx.client, _VERBS[verb])(name)
    if ctx.out.json_mode:
        ctx.out.json(result)
    else:
        _print_replacement(ctx, result)
    return EXIT_OK


def replacements(ctx: Context) -> int:
    name = ctx.arg("name")
    if name:
        one = ctx.client.replacement(name)
        listed = [one] if one is not None else []
    else:
        listed = ctx.client.replacements()
    if ctx.out.json_mode:
        ctx.out.json(listed)
        return EXIT_OK
    if not listed:
        ctx.out.note("no query is being replaced")
        return EXIT_OK
    rows = [
        {
            "name": r.name,
            "state": r.state,
            "history": r.history_rows,
            "rate": r.rows_per_second,
            "live": f"{r.partitions_live}/{r.partitions}",
            "rollback": f"until {r.rollback_until}" if r.rollback_available else "-",
        }
        for r in listed
    ]
    ctx.out.table(
        rows,
        ["name", "state", "history", ("rate", "ROWS/S"), "live", "rollback"],
    )
    return EXIT_OK


# ---------------------------------------------------------------------------------- subscribe


def parse_filters(values: Optional["list[str]"]) -> "dict[str, str]":
    """``--filter a=1,b=2 --filter c=3`` as ``{"a": "1", "b": "2", "c": "3"}``."""
    filters: dict[str, str] = {}
    for text in values or []:
        for pair in text.split(","):
            if not pair.strip():
                continue
            if "=" not in pair:
                raise UsageError(f"--filter takes column=value pairs, got '{pair}'")
            column, value = pair.split("=", 1)
            filters[column.strip()] = value.strip()
    return filters


def weight_text(weight: int) -> str:
    return f"+{weight}" if weight > 0 else str(weight)


def subscribe(ctx: Context) -> int:
    view = ctx.require("view")
    filters = parse_filters(ctx.arg("filter"))
    limit = int(ctx.arg("limit", 0))
    snapshot = bool(ctx.arg("snapshot"))
    reconnect = bool(ctx.arg("reconnect"))
    # Seconds without a stream open before a reconnecting subscription gives up; 0 is never.
    timeout: Optional[float] = float(ctx.arg("reconnect_timeout", 300.0))
    if timeout is not None and timeout <= 0:
        timeout = None
    stream = ctx.client.subscribe(
        view,
        filters or None,
        snapshot=snapshot,
        buffer_rows=ctx.arg("buffer_rows"),
        overflow=ctx.arg("overflow"),
        reconnect=reconnect,
        reconnect_timeout=timeout,
        changes="answer" if ctx.arg("answer") else "changelog",
    )
    ctx.out.note(
        f"subscribing to {view}"
        + (" (its answer)" if ctx.arg("answer") else "")
        + (f" {filters}" if filters else "")
        + ("; the view's rows print first, then" if snapshot else ";")
        + " changes print as they are committed. Ctrl-C to stop."
    )
    header = False
    seen = 0
    try:
        for batch in stream:
            if batch.reconnected:
                ctx.out.note(
                    "-- reconnected"
                    + ("; the next rows are a fresh snapshot, replacing what came before"
                       if snapshot else "; commits made while disconnected were not delivered")
                )
            for row in batch:
                if ctx.out.json_mode:
                    ctx.out.json_line({"type": "change", "weight": row.weight, "row": row.to_dict()})
                else:
                    if not header:
                        ctx.out.line(ctx.out.bold("\t".join(["WEIGHT", *row.columns])))
                        header = True
                    ctx.out.line(
                        "\t".join(
                            [weight_text(row.weight)]
                            + ["NULL" if v is None else str(v) for v in row]
                        )
                    )
                seen += 1
            _close_batch(ctx, batch)
            if limit and seen >= limit:
                break
            ctx.out.out.flush()
    except KeyboardInterrupt:
        pass
    finally:
        close = getattr(stream, "close", None)
        if close is not None:
            close()
    return EXIT_OK


def _close_batch(ctx: Context, batch: Any) -> None:
    count = len(batch)
    if ctx.out.json_mode:
        ctx.out.json_line(
            {
                "type": "snapshot" if batch.snapshot else "commit",
                "rows": count,
                "frontier": batch.frontier,
                "droppedBefore": batch.dropped_before,
                "reconnected": batch.reconnected,
            }
        )
        return
    rows = f"{count} row{'' if count == 1 else 's'}"
    if batch.snapshot:
        ctx.out.line(ctx.out.dim(f"-- snapshot at frontier {batch.frontier}, {rows}"))
    else:
        ctx.out.line(ctx.out.dim(f"-- commit, {rows}"))
    if batch.dropped_before:
        ctx.out.warn(
            f"{batch.dropped_before} commit(s) were dropped before this one: the rows printed "
            "are not the whole view. Subscribe with --snapshot and --buffer-rows/--overflow FAIL "
            "to be told rather than skipped"
        )


# ---------------------------------------------------------------------------------- dead letters


def _number(ctx: Context, name: str, default: int) -> int:
    value = ctx.arg(name)
    if value is None:
        return default
    try:
        return int(str(value).strip())
    except ValueError:
        raise UsageError(f"--{name} must be a number, and '{value}' is not") from None


def dlq(ctx: Context) -> int:
    verb = ctx.args.verb
    name = ctx.require("name")
    if verb == "list":
        return _dlq_list(ctx, name)
    if verb == "show":
        return _dlq_show(ctx, name, ctx.require("id"))
    if verb == "count":
        count = ctx.api.dead_letter_count(name)
        if ctx.out.json_mode:
            ctx.out.json(count)
        else:
            ctx.out.fields([(key, value) for key, value in count.items()])
        return EXIT_OK
    return _dlq_replay(ctx, name, csv(ctx.require("id")))


def _dlq_list(ctx: Context, name: str) -> int:
    page = ctx.client.dead_letters(
        name, offset=_number(ctx, "offset", 0), limit=_number(ctx, "limit", 50)
    )
    if ctx.out.json_mode:
        ctx.out.json(page)
        return EXIT_OK
    if not page.configured:
        ctx.out.line(
            "this server has no pravaha.dlq.directory, so a record it cannot decode stops the "
            "source rather than being kept. That is not an empty queue."
        )
        return EXIT_OK
    if not page.entries:
        ctx.out.line(ctx.out.good(f"{name} has no dead letters"))
        return EXIT_OK
    rows = [
        {
            "id": e.id,
            "when": e.at,
            "code": e.code,
            "stream": e.stream,
            "offset": e.offset,
            "bytes": e.size,
            "state": e.replay,
            "reason": "withheld" if e.is_withheld else e.reason,
        }
        for e in page.entries
    ]
    ctx.out.table(rows, ["id", "when", "code", "stream", "offset", "bytes", "state", "reason"])
    ctx.out.line()
    summary = f"{page.total} dead letter{'' if page.total == 1 else 's'} ({page.bytes} bytes)"
    if page.replayed or page.failed_again:
        summary += f", {page.replayed} replayed, {page.failed_again} failed again"
    ctx.out.line(summary + f"; retention {page.retention}")
    if page.has_more:
        ctx.out.note(f"older entries follow: --offset {page.offset + len(page.entries)}")
    if page.evicted > 0:
        ctx.out.warn(
            f"{page.evicted} older entries ({page.evicted_bytes} bytes) have been evicted by "
            f"retention and are gone. The bound is {page.retention}; raise "
            "pravaha.dlq.max-bytes, or drain the queue more often."
        )
    return EXIT_OK


def _dlq_show(ctx: Context, name: str, letter_id: str) -> int:
    entry = ctx.client.dead_letter(name, letter_id)
    if ctx.out.json_mode:
        ctx.out.json(entry)
        return EXIT_OK
    ctx.out.fields(
        [
            ("id", entry.id),
            ("query", name),
            ("stream", entry.stream),
            ("offset", entry.offset),
            ("when", entry.at),
            ("code", entry.code + (f"  ({help_line(entry.code)})" if entry.code else "")),
            ("reason", "withheld" if entry.is_withheld else entry.reason),
            ("replay", entry.replay + (f" at {entry.replayed_at}" if entry.replayed_at else "")),
            ("bytes", entry.size),
        ]
    )
    if entry.is_withheld:
        ctx.out.note(entry.withheld)
        return EXIT_OK
    ctx.out.line(ctx.out.bold("record"))
    # As it arrived, not escaped: a decode failure is often a byte that does not survive quoting.
    ctx.out.line(entry.raw.decode("utf-8", errors="replace"))
    return EXIT_OK


def _dlq_replay(ctx: Context, name: str, ids: "list[str]") -> int:
    if not ids:
        raise UsageError("--id names the dead letters to replay, comma-separated")
    results = ctx.client.replay_dead_letters(name, ids)
    failed = sum(1 for r in results if not r.succeeded)
    if ctx.out.json_mode:
        ctx.out.json(results)
        return EXIT_OK if failed == 0 else EXIT_REFUSED
    for result in results:
        if result.succeeded:
            ctx.out.line(ctx.out.good("replayed ") + result.id)
        else:
            ctx.out.warn(
                "failed again "
                + result.id
                + (f" -> back on the queue as {result.new_id}" if result.new_id else "")
            )
        if result.detail:
            ctx.out.line("  " + result.detail)
    ctx.out.note(
        "a replayed record is a new row at the query's current frontier, not a rewind: nothing "
        "is re-read and no earlier answer is recomputed."
    )
    return EXIT_OK if failed == 0 else EXIT_REFUSED


# ---------------------------------------------------------------------------------- debug


def debug(ctx: Context) -> int:
    verb = ctx.args.verb
    client = ctx.client
    out = ctx.out
    if verb == "fork":
        checkpoint = ctx.arg("checkpoint")
        session = client.debug_fork(ctx.require("name"), int(checkpoint) if checkpoint else None)
        if out.json_mode:
            out.json(session)
            return EXIT_OK
        out.note(f"debug session on '{session.query}', checkpoint {session.checkpoint_id}")
        out.note("DEBUG -- sinks disabled, nothing reads this fork's view")
        out.line(session.id)
        return EXIT_OK
    if verb == "checkpoints":
        ids = client.debug_checkpoints(ctx.require("name"))
        if out.json_mode:
            out.json(ids)
        elif not ids:
            out.note(
                "no retained checkpoint: this node may not be checkpointing, or the query has "
                "not taken one yet"
            )
        else:
            for checkpoint_id in ids:
                out.line(str(checkpoint_id))
        return EXIT_OK
    if verb == "step":
        report = client.debug_step(ctx.require("session"), ctx.arg("step", "row"))
        if out.json_mode:
            out.json(report)
            return EXIT_OK
        out.line(out.bold(f"step {report.sequence} ({report.kind})") + "  " + report.stopped)
        for row in report.rows_in:
            sign = "-" if row.weight < 0 else "+"
            out.line(
                f"  in   {sign}{abs(row.weight)} {row.stream}#{row.partition}@{row.offset} "
                f"{list(row.values)}"
            )
        for op in report.operators:
            label = f" {op.label}" if op.label else ""
            out.line(f"  op   {op.id}{label}  in={op.rows_in} out={op.rows_out}")
        for change in report.view_changes:
            sign = "-" if change.weight < 0 else "+"
            out.line(f"  view {sign}{abs(change.weight)} {list(change.values)}")
        tail = f"  rows consumed {report.rows_consumed}, view {report.view_size} rows"
        if report.watermark_nanos is not None:
            tail += f", watermark {report.watermark_nanos}"
        if report.exhausted:
            tail += ", sources exhausted"
        out.line(tail)
        return EXIT_OK
    if verb == "sessions":
        sessions = client.debug_sessions()
        out.result(
            sessions,
            columns=[
                "id",
                "query",
                ("checkpoint_id", "CHECKPOINT"),
                "steps",
                ("rows_consumed", "ROWS"),
                ("last_used_at", "LAST USED"),
            ],
            empty="no debug session is open",
        )
        return EXIT_OK
    if verb == "state":
        slots = client.debug_state(ctx.require("session"))
        out.result(
            slots,
            columns=[("id", "OPERATOR"), "kind", ("label", "WHAT"), "entries"],
            empty="this query holds no operator state: it is a filter or a projection, and its "
            "view is its whole answer",
        )
        return EXIT_OK
    if verb == "inspect":
        page = client.debug_inspect(
            ctx.require("session"),
            ctx.require("operator"),
            ctx.arg("key"),
            _number(ctx, "offset", 0),
            _number(ctx, "limit", 20),
        )
        if out.json_mode:
            out.json(page)
            return EXIT_OK
        for entry in page.entries:
            values = "  ".join(f"{k}={v}" for k, v in entry.values.items())
            out.line(f"{entry.key}\t{values}")
        more = " -- more, raise --offset" if page.has_more else ""
        out.note(f"{page.offset + len(page.entries)} of {page.total}{more}")
        return EXIT_OK
    if verb == "view":
        deltas = client.debug_view(ctx.require("session"))
        if out.json_mode:
            out.json(deltas)
            return EXIT_OK
        for delta in deltas:
            sign = "-" if delta.weight < 0 else "+"
            out.line(f"{sign}{abs(delta.weight)} {list(delta.values)}")
        return EXIT_OK
    if verb == "fixture":
        fixture = client.debug_export(ctx.require("session"), ctx.require("name"))
        target = ctx.arg("out")
        if not target:
            if out.json_mode:
                out.json(fixture)
            else:
                out.line(fixture.source)
            return EXIT_OK
        into = pathlib.Path(target)
        path = into if into.name.endswith(".java") else into / f"{fixture.class_name}.java"
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(fixture.source, encoding="utf-8")
        except OSError as exc:
            raise UsageError(f"cannot write {path}: {exc.strerror or exc}") from exc
        if out.json_mode:
            out.json({**dataclasses.asdict(fixture), "written": str(path)})
        else:
            out.line(out.good(f"wrote {path}"))
            out.note(f"it belongs at {fixture.path}")
        return EXIT_OK
    # end
    session_id = ctx.require("session")
    client.debug_end(session_id)
    if out.json_mode:
        out.json({"session": session_id, "ended": True})
    else:
        out.line(out.good("ended ") + session_id)
    return EXIT_OK
