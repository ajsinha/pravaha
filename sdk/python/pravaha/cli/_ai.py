"""The ``pravaha`` commands on the engine's plain-English features (docs/guides/AI.md): ``query --ask``,
``chat``, ``ask --engine``, ``alert --ask``, ``explain --why-changed``, ``explain-sql --engine``,
``why --engine`` and ``subscribe --narrate``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The model writes names and SQL, never answers: each command asks the engine's ``/api/v1/ai/*`` as
you (``--http`` and your token), the engine judges what the model wrote and runs it under your
grants, row filters and masks, and the command prints **the SQL (or the statement) the model chose**
beside what the engine made of it. Nothing is registered or created unless you confirm it --
``--yes``, or ``y`` at a terminal -- and then over Flight (``--url``), as ``register`` and ``alert
create`` do. The exit codes are the CLI's: ``1`` the engine refused (its code on stderr; ``PRV-1088``
when the model's answer could not be used), ``2`` usage, ``3`` unreachable.
"""

from __future__ import annotations

import datetime
import sys
from typing import Any, Optional

from pravaha.cli._common import EXIT_OK, Context, UsageError
from pravaha.cli._output import as_text
from pravaha.rest import ApiError

#: Earlier turns ``chat`` sends with a follow-up; the engine reads the last three.
_TURNS = 3


def _note(ctx: Context, text: str) -> None:
    """A remark on stderr, after what is already on stdout: the answer reads in order in a terminal
    and in a log that captures both."""
    ctx.out.out.flush()
    ctx.out.note(text)


def _said(exc: ApiError) -> str:
    """A refusal as one line: its code once, then its words."""
    code = exc.engine_code or str(exc.status)
    message = str(exc.message or "")
    return message if message.startswith(code) else f"{code}  {message}"


def _words(ctx: Context, flag: str, what: str) -> str:
    value = ctx.arg(flag)
    text = " ".join(value) if isinstance(value, list) else str(value or "")
    if not text.strip():
        raise UsageError(f"{what} is required")
    return text.strip()


# ---------------------------------------------------------------------------------- ask your data


def _shown(value: Any, sql_type: str) -> Any:
    """A value as ``query`` prints it (AIASKTS-1): the answer's JSON carries a TIMESTAMP as epoch
    nanoseconds, and ``query`` reads the same column from Flight as a UTC instant."""
    if sql_type.upper().startswith("TIMESTAMP") and isinstance(value, int) and not isinstance(value, bool):
        seconds, nanos = divmod(value, 1_000_000_000)
        return (datetime.datetime.fromtimestamp(seconds, tz=datetime.timezone.utc)
                + datetime.timedelta(microseconds=nanos // 1000))
    return value


def _print_answer(ctx: Context, answer: "dict[str, Any]", *, chart: bool) -> None:
    out = ctx.out
    out.line(out.bold("the SQL the model chose"))
    for line in str(answer.get("sql") or "").splitlines():
        out.line("  " + line)
    if answer.get("explanation"):
        _note(ctx, str(answer["explanation"]))
    out.line()
    columns = [str(c.get("name")) for c in answer.get("columns") or []]
    types = [str(c.get("type") or "") for c in answer.get("columns") or []]
    rows = [{c: ("NULL" if v is None else _shown(v, t)) for c, t, v in zip(columns, types, row)}
            for row in answer.get("rows") or []]
    if rows:
        out.table(rows, [(c, c) for c in columns])
    if chart:
        c = answer.get("chart") or {}
        parts = [f"{k}={c[k]}" for k in ("x", "y", "series") if c.get(k)]
        if rows:
            out.line()
        out.line(out.bold("chart") + "  " + str(c.get("kind")) + ("  " + " ".join(parts) if parts else ""))
    if answer.get("summary"):
        out.line()
        out.line(str(answer["summary"]))
    count = int(answer.get("rowCount") or 0)
    _note(ctx, f"{count} row{'' if count == 1 else 's'}"
             + (f", {len(rows)} shown (pravaha.ai.features.ask-rows)" if answer.get("truncated") else "")
             + f"; run as you; values sent to the model: {answer.get('values')}; nothing saved")


def query_ask(ctx: Context) -> int:
    """``pravaha query --ask "question"``: the SQL the model chose, then the rows, as ``query`` prints them."""
    if ctx.arg("sql") or ctx.arg("sql_file") or ctx.arg("params") or ctx.arg("tsv"):
        raise UsageError("--ask takes the question instead of --sql/--sql-file, --params and --tsv")
    answer = ctx.api.ai_ask(_words(ctx, "ask", "--ask's question"))
    if ctx.out.json_mode:
        ctx.out.json(answer)
        return EXIT_OK
    _print_answer(ctx, answer, chart=bool(ctx.arg("chart")))
    return EXIT_OK


def chat(ctx: Context) -> int:
    """``pravaha chat``: ask your data, with follow-ups that see the earlier questions and their SQL."""
    once = ctx.arg("once")
    turns: "list[dict[str, Any]]" = []

    def one(question: str) -> None:
        answer = ctx.api.ai_ask(question, turns[-_TURNS:])
        turns.append({"question": question, "sql": answer.get("sql")})
        if ctx.out.json_mode:
            ctx.out.json_line(answer)
        else:
            _print_answer(ctx, answer, chart=bool(ctx.arg("chart")))

    if once is not None:
        if not str(once).strip():
            raise UsageError("--once takes the question")
        one(str(once).strip())
        return EXIT_OK
    interactive = sys.stdin.isatty()
    if interactive and not ctx.out.json_mode:
        _note(ctx, "ask your data; a follow-up sees the questions before it. Empty line, exit or Ctrl-D ends.")
    try:
        while True:
            if interactive:
                ctx.out.err.write("ask> ")
                ctx.out.err.flush()
            line = sys.stdin.readline()
            if not line:
                break
            question = line.strip()
            if not question or question.lower() in ("exit", "quit"):
                if interactive or question:
                    break
                continue
            try:
                one(question)
            except ApiError as exc:
                if exc.status == 0:
                    raise
                ctx.out.warn(_said(exc))
            if not ctx.out.json_mode:
                ctx.out.line()
    except KeyboardInterrupt:
        pass
    return EXIT_OK


# ---------------------------------------------------------------------------------- drafts


def _confirm(ctx: Context, question: str) -> bool:
    if ctx.confirmed():
        return True
    if not sys.stdin.isatty():
        return False
    ctx.out.err.write(question + " [y/N] ")
    ctx.out.err.flush()
    return sys.stdin.readline().strip().lower() in ("y", "yes")


def _run_statement(ctx: Context, statement: str) -> "list[dict[str, Any]]":
    """The statement over Flight, as you: what ``pravaha query --sql`` would answer."""
    result = ctx.client.query(statement)
    return [dict(zip(result.columns, row)) for row in result]


def draft(ctx: Context) -> int:
    """``pravaha ask --engine``: the engine drafts, judges and dry-runs; ``--register`` asks first."""
    for flag in ("profile", "model"):
        if ctx.arg(flag):
            raise UsageError(f"--{flag} chooses the assistant's model; with --engine the engine's AI layer "
                             "answers (pravaha.ai.features.model)")
    if ctx.arg("yes") and not ctx.arg("register"):
        raise UsageError("--yes confirms --register; without --register nothing is registered")
    description = _words(ctx, "description", "a description")
    answer = ctx.api.ai_draft(description, ctx.arg("name"))
    registered: Optional["list[dict[str, Any]]"] = None
    note: Optional[str] = None
    if not ctx.out.json_mode:
        _print_draft(ctx, answer)
    if ctx.arg("register"):
        if _confirm(ctx, f"Register {answer.get('name')} under your own credentials?"):
            registered = _run_statement(ctx, str(answer.get("statement")))
        else:
            note = ("not registered: nobody confirmed it (run again with --register --yes, or run the "
                    "statement above with `pravaha query --sql`)")
    if ctx.out.json_mode:
        ctx.out.json({**answer, "registered": registered, "registerNote": note})
        return EXIT_OK
    if registered is not None:
        for row in registered:
            ctx.out.line(ctx.out.good("registered ") + str(row.get("name"))
                         + ctx.out.dim(f"  state={row.get('state')}  fingerprint={row.get('fingerprint')}"))
    if note:
        _note(ctx, note)
    elif not ctx.arg("register"):
        _note(ctx, "nothing was registered; add --register to register it after you confirm")
    return EXIT_OK


def _print_draft(ctx: Context, answer: "dict[str, Any]") -> None:
    out = ctx.out
    turns = int(answer.get("turns") or 1)
    out.line(out.good("the engine accepts it" + (" after one repair turn" if turns > 1 else "")))
    out.line()
    for line in str(answer.get("statement") or "").splitlines():
        out.line("  " + line)
    if answer.get("explanation"):
        out.line()
        out.line(out.bold("What it does"))
        out.line("  " + str(answer["explanation"]))
    dry = answer.get("dryRun") or {}
    out.line()
    out.line(out.bold("Dry run (nothing registered)"))
    out.fields([
        ("fingerprint", dry.get("fingerprint")),
        ("reads", ", ".join(dry.get("reads") or []) or "-"),
        ("lane", dry.get("lane")),
        ("shares with", ", ".join(dry.get("sharesWith") or []) or "none: a new computation"),
        ("columns", ", ".join(f"{f.get('name')} {f.get('type')}" for f in dry.get("outputFields") or [])),
    ])
    if dry.get("plan"):
        out.line()
        out.line(out.bold("The engine's plan (physical)"))
        for line in str(dry["plan"]).rstrip().splitlines():
            out.line("  " + line)


def alert_ask(ctx: Context) -> int:
    """``pravaha alert --ask``: the engine drafts and judges a CREATE ALERT; it is created on confirm."""
    if ctx.args.verb:
        raise UsageError("--ask drafts an alert from words; it goes without create or drop")
    answer = ctx.api.ai_alert_draft(_words(ctx, "ask", "--ask's description"), ctx.arg("name"))
    created: Optional["list[dict[str, Any]]"] = None
    if not ctx.out.json_mode:
        out = ctx.out
        out.line(out.good("the engine accepts it" + (" after one repair turn" if int(answer.get("turns") or 1) > 1
                                                    else "")))
        out.line()
        for line in str(answer.get("statement") or "").splitlines():
            out.line("  " + line)
        if answer.get("explanation"):
            _note(ctx, str(answer["explanation"]))
    if _confirm(ctx, f"Create the alert {answer.get('name')} under your own credentials?"):
        created = _run_statement(ctx, str(answer.get("statement")))
    if ctx.out.json_mode:
        ctx.out.json({**answer, "createdRows": created})
        return EXIT_OK
    if created is not None:
        for row in created:
            detail = row.get("detail")
            ctx.out.line(f"{row.get('name')}: {row.get('state')}" + (f" -- {detail}" if detail else ""))
    else:
        _note(ctx, "nothing was created; run again with --yes, or run the statement with `pravaha query --sql`")
    return EXIT_OK


# ---------------------------------------------------------------------------------- explain


def why_changed(ctx: Context) -> int:
    """``pravaha explain --why-changed VIEW``: the view's recent commits as you may see them, in words."""
    if ctx.arg("sql") or ctx.arg("sql_file"):
        raise UsageError("--why-changed names a view; it takes no --sql")
    answer = ctx.api.ai_explain("change", view=str(ctx.arg("why_changed")).strip())
    if ctx.out.json_mode:
        ctx.out.json(answer)
        return EXIT_OK
    change = answer.get("change") or {}
    ctx.out.line(str(answer.get("explanation") or ""))
    ctx.out.line()
    ctx.out.fields([
        ("view", change.get("view")),
        ("kept since", change.get("since")),
        ("commits", change.get("commits")),
        ("rows entered", change.get("entered")),
        ("rows left", change.get("left")),
        ("key", ", ".join(change.get("keyColumns") or []) or "-"),
        ("keys entered", "; ".join(", ".join(as_text(v) for v in k) for k in change.get("enteredKeys") or []) or "-"),
        ("keys left", "; ".join(", ".join(as_text(v) for v in k) for k in change.get("leftKeys") or []) or "-"),
    ])
    _note(ctx, f"values sent to the model: {answer.get('values')}; your row filter and masks applied")
    return EXIT_OK


def explain_sql(ctx: Context, sql: Optional[str], query: Optional[str]) -> int:
    """``pravaha explain-sql --engine``: the engine's AI layer explains, grounded in its own plan."""
    answer = ctx.api.ai_explain("sql", sql=sql, query=query)
    if ctx.out.json_mode:
        ctx.out.json(answer)
        return EXIT_OK
    ctx.out.line(str(answer.get("explanation") or ""))
    if ctx.arg("show_plan"):
        ctx.out.line()
        ctx.out.line(ctx.out.bold("The engine's plan (physical)"))
        ctx.out.line(str(answer.get("plan") or ""))
    return EXIT_OK


def why(ctx: Context, code: str, sql: Optional[str]) -> int:
    """``pravaha why --engine``: the engine's AI layer explains a refusal; the engine re-checks the SQL."""
    answer = ctx.api.ai_explain("refusal", code=code, sql=sql)
    if ctx.out.json_mode:
        ctx.out.json(answer)
        return EXIT_OK
    ctx.out.line(ctx.out.bold(code) + "  " + str(answer.get("explanation") or ""))
    if answer.get("valid") is True:
        _note(ctx, "the engine accepts this statement now: it raised no refusal for it")
    elif answer.get("valid") is False:
        _note(ctx, "the engine raised " + (", ".join(answer.get("engineCodes") or []) or "no code")
                     + " for this statement")
    return EXIT_OK


# ---------------------------------------------------------------------------------- subscribe --narrate


def _plain(value: Any) -> Any:
    """A cell as JSON carries it: numbers and text as they are, anything else (a decimal, an instant) as text."""
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    return as_text(value)


class Narrator:
    """Words each committed batch of a subscription through ``POST /api/v1/ai/narrate``. A narrative is
    never a reason to stop following: a refusal is said once, and the rows keep printing."""

    def __init__(self, ctx: Context, view: str) -> None:
        self.ctx = ctx
        self.view = view
        self.said: Optional[str] = None

    def batch(self, rows: "list[Any]") -> None:
        changes = [{"weight": int(r.weight), "values": [_plain(v) for v in r]} for r in rows if r.weight]
        if not changes:
            return
        try:
            answer = self.ctx.api.ai_narrate(self.view, changes)
        except ApiError as exc:
            if exc.status == 0:
                raise
            why = "no narrative: " + _said(exc)
            if why != self.said:
                _note(self.ctx, why)
                self.said = why
            return
        text = str(answer.get("narrative") or "")
        if not text:
            return
        if self.ctx.out.json_mode:
            self.ctx.out.json_line({"type": "narrative", "narrative": text, "entered": answer.get("entered"),
                                    "left": answer.get("left"), "values": answer.get("values")})
        else:
            self.ctx.out.line(self.ctx.out.dim("-- " + text))


__all__ = ["Narrator", "alert_ask", "chat", "draft", "explain_sql", "query_ask", "why", "why_changed"]
