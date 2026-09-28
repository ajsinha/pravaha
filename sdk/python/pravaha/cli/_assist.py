"""The ``pravaha`` commands of the assistant (ADR-058): ``ask``, ``explain-sql``, ``why``, and
``assist`` (``models``, ``providers``, ``check``, ``use``, ``enable``, ``disable``, ``eval``).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The configuration is the JSON file :func:`pravaha.assist.default_config_path` names
(``$PRAVAHA_ASSIST_CONFIG``, else ``assist.json`` beside the saved token). A model failure exits
``1`` with the normalised error (``ModelUnavailable``, ``ModelRateLimited``, ``ModelRefused``,
``ModelOutputError``, ``BudgetExceeded``); a configuration that is wrong exits ``2`` and sent
nothing; an engine that cannot be reached exits ``3``, as everywhere. ``ask`` exits ``1`` when
the engine still refuses the draft after its repair turns, and ``assist eval`` when a scored case
failed.
"""

from __future__ import annotations

import getpass
import sys
from typing import Any, Optional

from pravaha.assist import (
    AssistAdmin,
    Assistant,
    AuditRecord,
    FileConfigStore,
    ModelRouter,
    default_config_path,
)
from pravaha.assist.drafting import Draft
from pravaha.assist.evaluate import CaseResult, EvalReport, Evaluator
from pravaha.cli._common import EXIT_OK, EXIT_REFUSED, Context, UsageError, csv

_CODE_HELP = "a refusal code, such as PRV-2050"


def _store() -> FileConfigStore:
    return FileConfigStore(default_config_path())


def _actor() -> str:
    try:
        return getpass.getuser()
    except Exception:  # pragma: no cover - no login name at all
        return "unknown"


def _router() -> ModelRouter:
    return ModelRouter.from_store(_store())


def _answered(ctx: Context, answered_by: dict[str, Any], prompt: str) -> None:
    usage = answered_by.get("usage") or {}
    tokens = int(usage.get("input_tokens", 0)) + int(usage.get("output_tokens", 0))
    note = (
        f"answered by {answered_by.get('modelId')} ({answered_by.get('provider')} "
        f"{answered_by.get('model')}) in {answered_by.get('latencyMs', 0):.0f} ms, {tokens} tokens; "
        f"prompt {prompt}"
    )
    tried = answered_by.get("attempts") or []
    if tried:
        note += "; tried first: " + ", ".join(f"{a['model_id']} ({a['kind']})" for a in tried)
    ctx.out.note(note)


def _optional_sql(ctx: Context) -> Optional[str]:
    if getattr(ctx.args, "sql_file", None) or getattr(ctx.args, "sql", None):
        return ctx.sql()
    return None


# ---------------------------------------------------------------------------------- tasks


def explain_sql(ctx: Context) -> int:
    name = ctx.arg("query")
    sql = _optional_sql(ctx)
    if not name and not sql:
        raise UsageError("--sql, --sql-file or --query is required")
    level = ctx.arg("level", "physical")
    if level not in ("physical", "logical"):
        raise UsageError(f"--level must be physical or logical; got '{level}'")
    router = _router()
    result = Assistant(router, ctx.api).explain_query(
        sql, query_name=name, level=level, profile=ctx.arg("profile"), model=ctx.arg("model")
    )
    if ctx.out.json_mode:
        ctx.out.json(result.to_dict())
        return EXIT_OK
    ctx.out.line(result.summary)
    if result.steps:
        ctx.out.line()
        ctx.out.line(ctx.out.bold("How it works"))
        for i, step in enumerate(result.steps, 1):
            ctx.out.line(f"  {i}. {step}")
    if result.notes:
        ctx.out.line()
        ctx.out.line(ctx.out.bold("Notes"))
        for note in result.notes:
            ctx.out.line(f"  - {note}")
    if ctx.arg("show_plan"):
        ctx.out.line()
        ctx.out.line(ctx.out.bold(f"The engine's {result.plan_level} plan"))
        ctx.out.line(result.plan)
    _answered(ctx, result.answered_by, result.prompt)
    return EXIT_OK


def why(ctx: Context) -> int:
    code = str(ctx.require("code", _CODE_HELP)).strip().upper()
    if not code.startswith("PRV-") or len(code) != 8 or not code[4:].isdigit():
        raise UsageError(f"{code!r} is not a code: codes look like PRV-2050")
    sql = _optional_sql(ctx)
    router = _router()
    assistant = Assistant(router, ctx.api if sql else None)
    result = assistant.explain_refusal(
        code, sql, profile=ctx.arg("profile"), model=ctx.arg("model"),
        check_rewrite=not ctx.arg("no_check"),
    )
    if ctx.out.json_mode:
        ctx.out.json(result.to_dict())
        return EXIT_OK
    ctx.out.line(ctx.out.bold(result.code) + "  " + result.meaning)
    if result.engine is not None:
        if result.engine["valid"]:
            ctx.out.note("the engine accepts this statement: it raised no refusal for it")
        elif code not in result.engine["codes"]:
            ctx.out.note(
                f"the engine raised {', '.join(result.engine['codes']) or 'no code'} for this "
                f"statement, not {code}"
            )
    ctx.out.line()
    ctx.out.fields([("why", result.cause), ("fix", result.fix)])
    if result.rewrite:
        verdict = result.rewrite_verdict
        if not verdict.get("checked"):
            state = "not checked by the engine -- run `pravaha validate` on it"
        elif verdict.get("valid"):
            state = ctx.out.good("the engine accepts it")
        else:
            codes = sorted({str(d.get("code")) for d in verdict.get("diagnostics") or []
                            if isinstance(d, dict) and d.get("code")})
            state = ctx.out.bad(f"the engine refuses it too ({', '.join(codes) or 'no code'})")
        ctx.out.line()
        ctx.out.line(ctx.out.bold("rewrite") + f"  ({state})")
        for line in result.rewrite.splitlines():
            ctx.out.line("  " + line)
    _answered(ctx, result.answered_by, result.prompt)
    return EXIT_OK


# ---------------------------------------------------------------------------------- ask


def confirm_on_terminal(ctx: Context, question: str) -> bool:
    """``question`` answered ``y`` at a terminal; ``False`` with no terminal to ask at."""
    if not sys.stdin.isatty():
        return False
    ctx.out.err.write(question + " [y/N] ")
    ctx.out.err.flush()
    return sys.stdin.readline().strip().lower() in ("y", "yes")


def _turns_word(count: int) -> str:
    return f"{count} repair turn{'s' if count != 1 else ''}"


def _verdict_line(ctx: Context, draft: Draft) -> str:
    if draft.status == "accepted":
        after = f" after {_turns_word(draft.repairs)}" if draft.repairs else ""
        return ctx.out.good(f"the engine accepts it{after}")
    if draft.status == "questions":
        return ctx.out.bold("the model needs answers before it can draft this")
    who = "the engine" if draft.verdict.by == "engine" else "the assistant"
    code = f"{draft.verdict.code}  " if draft.verdict.code else ""
    return ctx.out.bad(f"refused by {who} after {_turns_word(draft.repairs)}: "
                       f"{code}{draft.verdict.message or ''}")


def _print_draft(ctx: Context, draft: Draft) -> None:
    out = ctx.out
    out.line(_verdict_line(ctx, draft))
    if draft.sql:
        out.line()
        statement = draft.statement() if draft.keys else draft.sql
        for line in statement.splitlines():
            out.line("  " + line)
    if draft.explanation:
        out.line()
        out.line(out.bold("What it does"))
        out.line("  " + draft.explanation)
    if draft.plan:
        out.line()
        out.line(out.bold("The engine's plan (physical)"))
        for line in draft.plan.rstrip().splitlines():
            out.line("  " + line)
    if draft.guarantees:
        out.line()
        g = draft.guarantees
        if g.get("sink"):
            out.fields([("sink", g["sink"]), ("guarantee", g.get("guarantee")),
                        ("retractions", "accepted" if g.get("acceptsRetractions")
                         else "not accepted: append only")])
        else:
            out.fields([("sink", "none"), ("guarantee", g.get("note"))])
    for title, items in (("Assumptions", draft.assumptions), ("Questions", draft.questions)):
        if items:
            out.line()
            out.line(out.bold(title))
            for item in items:
                out.line(f"  - {item}")
    if draft.same_as:
        out.line()
        out.line(out.bold("Already running"))
        for same in draft.same_as:
            out.line(f"  - {same.get('name')} (fingerprint {same.get('fingerprint')}, same plan): "
                     f"{same.get('reuse')}")
    out.line()
    out.line(out.bold("Turns"))
    for turn in draft.turns:
        note = "" if turn.intent_kept else "  (changed what the query reads)"
        out.line(f"  {turn.number}. {turn.kind:<6}  {turn.verdict.words()}{note}")


def ask(ctx: Context) -> int:
    description = " ".join(ctx.arg("description", [])).strip()
    if not description:
        raise UsageError('describe the query you want: pravaha ask "..."')
    repairs = int(ctx.arg("repairs", 3))
    if not 0 <= repairs <= 3:
        raise UsageError("--repairs is 0 to 3")
    if ctx.arg("yes") and not ctx.arg("register"):
        raise UsageError("--yes confirms --register; without --register nothing is registered")
    router = _router()
    assistant = Assistant(router, ctx.api)
    draft = assistant.draft(description, name=ctx.arg("name"), profile=ctx.arg("profile"),
                            model=ctx.arg("model"), max_repairs=repairs)
    registered: Optional[dict[str, Any]] = None
    register_note: Optional[str] = None
    if ctx.arg("register"):
        if not draft.accepted:
            register_note = "not registered: the engine has not accepted the draft"
        elif ctx.confirmed() or confirm_on_terminal(
                ctx, f"Register {draft.name} under your own credentials?"):
            registered = assistant.register(draft, confirmed=True, client=ctx.client)
        else:
            register_note = ("not registered: nobody confirmed it (run again with --register "
                             "--yes, or run the statement above with `pravaha query`)")
    if ctx.out.json_mode:
        ctx.out.json({**draft.to_dict(), "registered": registered, "registerNote": register_note})
    else:
        _print_draft(ctx, draft)
        if ctx.arg("show_context"):
            ctx.out.line()
            ctx.out.line(ctx.out.bold("What the model was told"))
            for key, value in draft.context.items():
                ctx.out.line(f"  {key}: {value}")
        if registered is not None:
            ctx.out.line()
            ctx.out.line(ctx.out.good("registered ") + str(registered.get("name"))
                         + ctx.out.dim(f"  state={registered.get('state')}  "
                                       f"fingerprint={registered.get('fingerprint')}"))
        _answered(ctx, dict(draft.answered_by), draft.prompt)
        context = draft.context
        ctx.out.note(
            f"{draft.tokens} tokens over {len(draft.turns)} turn"
            f"{'s' if len(draft.turns) != 1 else ''}; context: "
            f"{len(context.get('streams', []))} streams, {len(context.get('views', []))} views, "
            f"{len(context.get('sinks', []))} sinks; examples: "
            f"{', '.join(context.get('examples', [])) or 'none'}")
        omitted = context.get("omitted") or {}
        if omitted:
            ctx.out.note("left out of the prompt for size: " + "; ".join(
                f"{kind}: {', '.join(names)}" for kind, names in omitted.items()))
        if register_note:
            ctx.out.note(register_note)
    if draft.status == "refused":
        return EXIT_REFUSED
    if ctx.arg("register") and draft.status == "questions":
        return EXIT_REFUSED
    return EXIT_OK


# ---------------------------------------------------------------------------------- assist


def assist(ctx: Context) -> int:
    verb = ctx.args.verb or "models"
    if verb == "models":
        return _models(ctx)
    if verb == "providers":
        return _providers(ctx)
    if verb == "check":
        return _check(ctx)
    if verb == "use":
        return _use(ctx)
    if verb in ("enable", "disable"):
        return _toggle(ctx, verb)
    if verb == "eval":
        return _eval(ctx)
    raise UsageError(f"unknown verb {verb!r}")  # pragma: no cover - argparse refuses it first


def _models(ctx: Context) -> int:
    store = _store()
    admin = AssistAdmin(store)
    config = admin.config()
    rows = admin.models()
    if ctx.out.json_mode:
        ctx.out.json({
            "path": str(store.path),
            "version": config.version,
            "changedAt": config.changed_at,
            "changedBy": config.changed_by,
            "defaultProfile": config.default_profile,
            "profiles": {k: list(v) for k, v in sorted(config.profiles.items())},
            "budgets": config.budgets.to_dict(),
            "models": rows,
        })
        return EXIT_OK
    ctx.out.note(f"{store.path} (version {config.version})")
    if not rows:
        ctx.out.note("no models are configured; docs/ASSIST.md shows the file")
        return EXIT_OK
    table = []
    for row in rows:
        key = row["key"]
        if key:
            key = f"{key} ({'set' if row['keySet'] else 'NOT SET'})"
        table.append({
            **row,
            "provider": f"{row['provider']} ({row['type']})",
            "key": key,
            "profiles": ", ".join(
                f"{p['profile']}#{p['position']}" + ("*" if p["default"] else "")
                for p in row["profiles"]
            ),
        })
    ctx.out.table(table, ["id", "provider", "model", "enabled", "key", "profiles"])
    ctx.out.line()
    ctx.out.line(ctx.out.bold("PROFILE") + "  chain, first answers first (* the default)")
    for name, chain in sorted(config.profiles.items()):
        marker = "*" if name == config.default_profile else " "
        ctx.out.line(f"{marker}{name}  " + " -> ".join(chain))
    budgets = config.budgets.to_dict()
    if budgets:
        ctx.out.note("budgets: " + ", ".join(f"{k}={v}" for k, v in budgets.items()))
    return EXIT_OK


def _providers(ctx: Context) -> int:
    found = AssistAdmin(_store()).providers()
    if ctx.out.json_mode:
        ctx.out.json([p.to_dict() for p in found])
        return EXIT_OK
    rows = []
    for info in found:
        caps = info.capabilities
        rows.append({
            "name": info.name,
            "source": info.source,
            "structured": caps.structured_output if caps else None,
            "target": info.target,
            "problem": info.problem,
        })
    ctx.out.table(rows, ["name", "source", ("structured", "SCHEMAS"), "target", "problem"])
    return EXIT_OK


def _check(ctx: Context) -> int:
    router = _router()
    chosen = csv(ctx.arg("model")) or None
    results = router.check(chosen)
    if ctx.out.json_mode:
        ctx.out.json([r.to_dict() for r in results])
    else:
        if not results:
            ctx.out.note("no enabled models to check")
        ctx.out.table(
            [{"model_id": r.model_id, "provider": r.provider, "model": r.model,
              "status": "ok" if r.ok else "FAILED",
              "latency": f"{r.latency_ms:.0f} ms",
              "detail": "" if r.ok else f"{(r.error or {}).get('kind')}: "
                                        f"{(r.error or {}).get('message')}"}
             for r in results],
            [("model_id", "ID"), "provider", "model", "status", "latency", "detail"],
        )
    return EXIT_OK if all(r.ok for r in results) else EXIT_REFUSED


def _print_change(ctx: Context, record: AuditRecord, applied: bool) -> None:
    if ctx.out.json_mode:
        ctx.out.json({**record.to_dict(), "applied": applied})
        return
    ctx.out.fields([
        ("change", f"{record.action} {record.target}"),
        ("before", record.before),
        ("after", record.after),
        ("version", record.version),
    ])
    if not applied:
        ctx.out.note("nothing changed: pass --yes to make this change")


def _use(ctx: Context) -> int:
    profile = ctx.require("profile", "the profile")
    chain = csv(ctx.require("chain", "the model ids, first to answer first"))
    if not chain:
        raise UsageError("name at least one model id")
    applied = ctx.confirmed()
    admin = AssistAdmin(_store(), actor=_actor(), dry_run=not applied)
    record = admin.set_chain(profile, chain, default=bool(ctx.arg("default")))
    _print_change(ctx, record, applied)
    return EXIT_OK


def _toggle(ctx: Context, verb: str) -> int:
    model_id = ctx.require("model_id", "the model id")
    applied = ctx.confirmed()
    admin = AssistAdmin(_store(), actor=_actor(), dry_run=not applied)
    record = admin.enable_model(model_id) if verb == "enable" else admin.disable_model(model_id)
    _print_change(ctx, record, applied)
    return EXIT_OK


def _progress(ctx: Context) -> Any:
    def report(result: CaseResult) -> None:
        mark = {True: "pass", False: "FAIL", None: "skip"}[result.passed]
        ctx.out.note(f"{mark}  {result.id}  {result.outcome}")
    return report


def _eval(ctx: Context) -> int:
    router = _router()
    run = bool(ctx.arg("register_and_compare"))
    assistant = Assistant(router, ctx.api)
    evaluator = Evaluator(
        assistant, profile=ctx.arg("profile"), model=ctx.arg("model"), run=run,
        client=ctx.client if run else None, prefix=ctx.arg("prefix", "assist_eval_"),
        settle_s=float(ctx.arg("settle", 5.0)),
        progress=None if ctx.out.json_mode else _progress(ctx),
    )
    limit = ctx.arg("limit")
    report = evaluator.run(limit=int(limit) if limit is not None else None,
                           only=csv(ctx.arg("case")))
    if ctx.out.json_mode:
        ctx.out.json(report.to_dict(full=bool(ctx.arg("full"))))
    else:
        _print_report(ctx, report)
    return EXIT_OK if report.all_passed else EXIT_REFUSED


def _print_report(ctx: Context, report: EvalReport) -> None:
    rows = []
    for r in report.results:
        rows.append({
            "id": r.id,
            "kind": r.kind,
            "outcome": r.outcome,
            "equal": r.equal or "-",
            "pass": {True: "yes", False: "NO", None: "skip"}[r.passed],
            "repairs": r.repairs,
            "tokens": r.tokens,
            "ms": f"{r.latency_ms:.0f}",
            "reason": r.reason if len(r.reason) <= 90 else r.reason[:89] + "…",
        })
    ctx.out.table(rows, [("id", "CASE"), "kind", "outcome", "equal", "pass", "repairs", "tokens",
                         ("ms", "MS"), "reason"])
    summary = report.summary()
    ctx.out.line()
    ctx.out.fields([
        ("passed", f"{summary['passed']} of {summary['scored']} scored "
                   f"({summary['skipped']} skipped, {summary['errors']} errors)"),
        ("references", f"{summary['referencesAccepted']} of {summary['references']} accepted, "
                       f"{summary['referencesEqual']} equal to the reference"),
        ("negatives", f"{summary['negativesCaught']} of {summary['negatives']} refused or asked"),
        ("cost", f"{summary['tokens']} tokens, {summary['repairs']} repair turns, "
                 f"{summary['latencyMs'] / 1000.0:.1f} s drafting"),
        ("models", ", ".join(summary["models"]) or "-"),
        ("mode", "--run: registered, compared and dropped" if report.run
                 else "plans compared, nothing registered"),
    ])
