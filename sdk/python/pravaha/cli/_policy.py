"""The ``pravaha policy`` commands (ADR-059 section 4): row filters and column masks.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The engine checks every expression and every binding; the CLI only asks, and shows what the engine
answered. Unbinding widens what an object shows and dropping removes a policy for good, so each
prints what it would do and changes nothing without ``--yes``.
"""

from __future__ import annotations

from typing import Any, Sequence, Union

from pravaha.cli._common import EXIT_OK, Context, UsageError


def _where(binding: "dict[str, Any]") -> str:
    if binding.get("tag"):
        return f"TAG '{binding['tag']}'"
    return str(binding.get("object") or "")


def _rows(policies: "list[dict[str, Any]]") -> "list[dict[str, Any]]":
    rows = []
    for policy in policies:
        owner = policy.get("owner") or {}
        rows.append(
            dict(
                policy,
                columnText=policy.get("column") or "",
                exceptText=", ".join(policy.get("exceptRoles") or []),
                boundText=", ".join(_where(b) for b in policy.get("bindings") or []) or "(unbound)",
                ownerText=f"{owner.get('type', '')} {owner.get('name', '')}".strip(),
            )
        )
    return rows


_COLUMNS: "Sequence[Union[str, tuple[str, str]]]" = [
    "name",
    "kind",
    ("columnText", "COLUMN"),
    "expression",
    ("exceptText", "EXCEPT ROLE"),
    ("boundText", "BOUND TO"),
]


def _name(ctx: Context, usage: str) -> str:
    value = ctx.arg("name")
    if not value:
        raise UsageError(f"usage: pravaha policy {usage}")
    return str(value)


def _place(ctx: Context, usage: str) -> "tuple[str | None, str | None]":
    on, tag = ctx.arg("on"), ctx.arg("tag")
    if bool(on) == bool(tag):
        raise UsageError(f"say where: --on <stream or view> or --tag <key[=value]>, one of them ({usage})")
    return (str(on) if on else None, str(tag) if tag else None)


def policy(ctx: Context) -> int:
    verb = ctx.args.verb or "ls"
    api = ctx.api
    out = ctx.out
    if verb == "ls":
        on = ctx.arg("on")
        listed = api.policies(on=str(on) if on else None)
        empty = f"no policy you may see reaches {on}" if on else "no row filters or masks you may see"
        out.result(_rows(listed), columns=_COLUMNS, empty=empty)
        return EXIT_OK
    if verb == "show":
        return _show(ctx, _name(ctx, "show <policy>"))
    if verb in ("create-filter", "create-mask"):
        return _create(ctx, verb)
    if verb == "bind":
        name = _name(ctx, "bind <policy> --on <object> | --tag <key[=value]>")
        on, tag = _place(ctx, "bind")
        bound = api.bind_policy(name, on=on, tag=tag)
        _said(ctx, bound, f"bound {name} to {_where(bound)}")
        return EXIT_OK
    if verb == "unbind":
        return _unbind(ctx)
    return _drop(ctx)


def _create(ctx: Context, verb: str) -> int:
    mask = verb == "create-mask"
    name = _name(ctx, f"{verb} <policy> {'--column <c> ' if mask else ''}--as <expression>")
    expression = ctx.arg("as_expression")
    if not expression:
        raise UsageError(f"say what it is: pravaha policy {verb} {name} --as '<expression>'")
    column = ctx.arg("column")
    if mask and not column:
        raise UsageError("a mask names the column it masks: --column <name>")
    made = ctx.api.create_policy(
        name,
        "MASK" if mask else "ROW_FILTER",
        str(expression),
        column=str(column) if mask else None,
        except_roles=list(ctx.arg("except_role") or []),
        description=str(ctx.arg("comment") or ""),
    )
    _said(
        ctx,
        made,
        f"created {made.get('name', name)} ({made.get('kind')}); nothing is narrowed until it is bound: "
        f"pravaha policy bind {made.get('name', name)} --on <object>",
    )
    return EXIT_OK


def _unbind(ctx: Context) -> int:
    name = _name(ctx, "unbind <policy> --on <object> | --tag <key[=value]> --yes")
    on, tag = _place(ctx, "unbind")
    where = on if on else f"TAG '{tag}'"
    if not ctx.confirmed():
        if ctx.out.json_mode:
            ctx.out.json({"policy": name, "from": where, "done": False})
        else:
            ctx.out.line(f"would unbind {name} from {where}: what it narrowed there is shown again")
            flag = f"--on {on}" if on else f"--tag {tag}"
            ctx.out.note(f"nothing was changed; run `pravaha policy unbind {name} {flag} --yes` to do it")
        return EXIT_OK
    answer = ctx.api.unbind_policy(name, on=on, tag=tag)
    said = "unbound" if answer.get("unbound") else "was not bound to"
    _said(ctx, answer, f"{answer.get('policy', name)} {said} {answer.get('target', where)}")
    return EXIT_OK


def _drop(ctx: Context) -> int:
    name = _name(ctx, "drop <policy> --yes")
    if not ctx.confirmed():
        if ctx.out.json_mode:
            ctx.out.json({"policy": name, "action": "drop", "done": False})
        else:
            ctx.out.line(f"would drop {name}; the engine refuses while it is still bound anywhere")
            ctx.out.note(f"nothing was changed; run `pravaha policy drop {name} --yes` to do it")
        return EXIT_OK
    ctx.api.drop_policy(name)
    _said(ctx, {"policy": name, "action": "drop", "done": True}, f"dropped {name}")
    return EXIT_OK


def _show(ctx: Context, name: str) -> int:
    shown = ctx.api.policy(name)
    out = ctx.out
    if out.json_mode:
        out.json(shown)
        return EXIT_OK
    row = _rows([shown])[0]
    out.fields(
        [
            ("name", row.get("name")),
            ("kind", row.get("kind")),
            ("column", row.get("columnText") or "-"),
            ("expression", row.get("expression")),
            ("except role", row.get("exceptText") or "-"),
            ("owner", row.get("ownerText")),
            ("description", row.get("description")),
            ("version", row.get("version")),
            ("bound to", row.get("boundText")),
        ]
    )
    return EXIT_OK


def _said(ctx: Context, answer: "dict[str, Any]", text: str) -> None:
    if ctx.out.json_mode:
        ctx.out.json(answer)
    else:
        ctx.out.line(text)
