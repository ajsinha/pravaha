"""The ``pravaha`` commands of the assistant (ADR-058): ``explain-sql``, ``why``, and ``assist``
(``models``, ``providers``, ``check``, ``use``, ``enable``, ``disable``).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The configuration is the JSON file :func:`pravaha.assist.default_config_path` names
(``$PRAVAHA_ASSIST_CONFIG``, else ``assist.json`` beside the saved token). A model failure exits
``1`` with the normalised error (``ModelUnavailable``, ``ModelRateLimited``, ``ModelRefused``,
``ModelOutputError``, ``BudgetExceeded``); a configuration that is wrong exits ``2`` and sent
nothing; an engine that cannot be reached exits ``3``, as everywhere.
"""

from __future__ import annotations

import getpass
from typing import Any, Optional

from pravaha.assist import (
    AssistAdmin,
    Assistant,
    AuditRecord,
    FileConfigStore,
    ModelRouter,
    default_config_path,
)
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
