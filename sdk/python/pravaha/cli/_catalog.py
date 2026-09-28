"""The ``pravaha`` catalogue commands (ADR-059): objects, namespaces, owners, tags and grants.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The engine is the authority for every one of these; the CLI only asks, and shows what the
engine answered. A revocation and a change of owner take something away from somebody, so each
prints what it would do and changes nothing without ``--yes``.
"""

from __future__ import annotations

from typing import Any, Optional, Sequence, Union

from pravaha.cli._common import EXIT_OK, Context, UsageError, csv


def _owner(value: Any) -> str:
    if isinstance(value, dict):
        return f"{value.get('type', '')} {value.get('name', '')}".strip()
    return str(value or "")


def _tags(value: Any) -> str:
    if not isinstance(value, dict) or not value:
        return ""
    return ", ".join(key if not tag else f"{key}={tag}" for key, tag in value.items())


def _rows(objects: "list[dict[str, Any]]") -> "list[dict[str, Any]]":
    return [
        dict(item, ownerText=_owner(item.get("owner")), tagText=_tags(item.get("tags")))
        for item in objects
    ]


_OBJECT_COLUMNS: "Sequence[Union[str, tuple[str, str]]]" = [
    "name",
    "kind",
    ("ownerText", "OWNER"),
    ("tagText", "TAGS"),
    "description",
]


def _grantee(ctx: Context) -> "tuple[str, str]":
    role, user = ctx.arg("role"), ctx.arg("user")
    if bool(role) == bool(user):
        raise UsageError("say who: --role <name> or --user <name>, one of them")
    return ("ROLE", str(role)) if role else ("USER", str(user))


def _target(ctx: Context, usage: str) -> str:
    value = ctx.arg("object")
    if not value:
        raise UsageError(f"usage: pravaha {usage}")
    return str(value)


# ---------------------------------------------------------------------------------- reading


def catalog(ctx: Context) -> int:
    verb = ctx.args.verb or "ls"
    api = ctx.api
    out = ctx.out
    if verb == "ls":
        listed = api.catalog_objects(namespace=ctx.arg("namespace"), kind=ctx.arg("kind"))
        out.result(_rows(listed), columns=_OBJECT_COLUMNS, empty="nothing in the catalogue you may see")
        return EXIT_OK
    if verb == "search":
        text = _target(ctx, "catalog search <text>")
        found = api.catalog_search(text)
        out.result(_rows(found), columns=_OBJECT_COLUMNS, empty=f"nothing you may see matches {text!r}")
        return EXIT_OK
    if verb == "namespaces":
        out.result(
            _rows(api.catalog_namespaces()),
            columns=["name", ("ownerText", "OWNER"), ("tagText", "TAGS"), "description"],
            empty="no namespaces you may use",
        )
        return EXIT_OK
    if verb == "show":
        return _show(ctx, _target(ctx, "catalog show <object>"))
    if verb == "create-namespace":
        name = _target(ctx, "catalog create-namespace <name>")
        made = api.create_namespace(
            name, description=str(ctx.arg("comment", "")), if_not_exists=bool(ctx.arg("if_not_exists"))
        )
        _changed(ctx, made, f"created {made.get('name', name)}, owned by {_owner(made.get('owner'))}")
        return EXIT_OK
    name = _target(ctx, f"catalog {verb} <object>")
    if verb == "comment":
        text = ctx.arg("text")
        if text is None:
            raise UsageError("usage: pravaha catalog comment <object> <text>  (\"\" clears it)")
        changed = api.change_catalog_object(name, description=str(text))
        _changed(ctx, changed, f"{changed.get('name')}: {changed.get('description') or '(no description)'}")
        return EXIT_OK
    if verb == "tag":
        return _tag(ctx, name)
    if verb == "move":
        changed = api.change_catalog_object(name, namespace=str(ctx.require("namespace")))
        _changed(ctx, changed, f"moved to {changed.get('name')}, with its grants")
        return EXIT_OK
    # owner
    owner = _grantee(ctx)
    if not ctx.confirmed():
        if out.json_mode:
            out.json({"object": name, "action": "owner", "to": list(owner), "done": False})
        else:
            out.line(
                f"would give {name} to {owner[0]} {owner[1]}: you keep only what your grants give you"
            )
            flag = "--role" if owner[0] == "ROLE" else "--user"
            out.note(
                f"nothing was changed; run `pravaha catalog owner {name} {flag} {owner[1]} --yes` to do it"
            )
        return EXIT_OK
    changed = api.change_catalog_object(name, owner=owner)
    _changed(ctx, changed, f"{changed.get('name')} is now owned by {_owner(changed.get('owner'))}")
    return EXIT_OK


def _show(ctx: Context, name: str) -> int:
    detail = ctx.api.catalog_object(name)
    out = ctx.out
    if out.json_mode:
        out.json(detail)
        return EXIT_OK
    item = dict(detail.get("object") or {})
    out.fields(
        [
            ("name", item.get("name")),
            ("kind", item.get("kind")),
            ("engine name", item.get("engineName")),
            ("owner", _owner(item.get("owner"))),
            ("description", item.get("description")),
            ("tags", _tags(item.get("tags"))),
            ("version", item.get("version")),
            ("created", f"{item.get('createdAt') or '-'} by {item.get('createdBy') or '-'}"),
            ("updated", f"{item.get('updatedAt') or '-'} by {item.get('updatedBy') or '-'}"),
        ]
    )
    grants = list(detail.get("grants") or [])
    out.line()
    out.line(out.bold("grants:"))
    if grants:
        out.table(grants, ["privilege", ("granteeType", "TO"), "grantee", ("grantedBy", "BY")])
    else:
        out.line("  none you may see")
    access = detail.get("access") or {}
    lines = [line for line in access.get("privileges") or [] if line.get("allowed")]
    out.line()
    out.line(out.bold("you may:") + " " + (", ".join(str(line.get("privilege")) for line in lines) or "nothing"))
    return EXIT_OK


def _tag(ctx: Context, name: str) -> int:
    pairs = list(ctx.arg("tags") or [])
    set_tags: "dict[str, str]" = {}
    for pair in pairs:
        key, _, value = str(pair).partition("=")
        if not key.strip():
            raise UsageError(f"a tag is key or key=value, not {pair!r}")
        set_tags[key.strip()] = value.strip()
    unset = csv(ctx.arg("unset"))
    if not set_tags and not unset:
        raise UsageError("usage: pravaha catalog tag <object> key[=value] ... [--unset k1,k2]")
    changed = ctx.api.change_catalog_object(name, set_tags=set_tags or None, unset_tags=unset or None)
    _changed(ctx, changed, f"{changed.get('name')}: {_tags(changed.get('tags')) or '(no tags)'}")
    return EXIT_OK


def _changed(ctx: Context, answer: "dict[str, Any]", text: str) -> None:
    if ctx.out.json_mode:
        ctx.out.json(answer)
    else:
        ctx.out.line(text)


# ---------------------------------------------------------------------------------- grants


def _privileges(ctx: Context) -> "list[str]":
    written = csv(ctx.arg("privileges"))
    if not written:
        raise UsageError("name the privileges: SELECT,SUBSCRIBE (or ALL)")
    return [p.upper().replace(" ", "_") for p in written]


def grant(ctx: Context) -> int:
    privileges = _privileges(ctx)
    name = _target(ctx, "grant <privileges> <object> --role <name> | --user <name>")
    who = _grantee(ctx)
    made = ctx.api.grant(name, privileges, who[0], who[1])
    if ctx.out.json_mode:
        ctx.out.json(made)
        return EXIT_OK
    granted = ", ".join(str(g.get("privilege")) for g in made)
    target = made[0].get("object") if made else name
    ctx.out.line(f"granted {granted or 'nothing'} on {target} to {who[0]} {who[1]}")
    return EXIT_OK


def revoke(ctx: Context) -> int:
    privileges = _privileges(ctx)
    name = _target(ctx, "revoke <privileges> <object> --role <name> | --user <name> --yes")
    who = _grantee(ctx)
    if not ctx.confirmed():
        if ctx.out.json_mode:
            ctx.out.json(
                {"object": name, "privileges": privileges, "from": list(who), "done": False}
            )
        else:
            ctx.out.line(
                f"would revoke {','.join(privileges)} on {name} from {who[0]} {who[1]}: "
                "it takes effect at their next read, and ends their open subscriptions"
            )
            flag = "--role" if who[0] == "ROLE" else "--user"
            ctx.out.note(
                "nothing was changed; run "
                f"`pravaha revoke {','.join(privileges)} {name} {flag} {who[1]} --yes` to do it"
            )
        return EXIT_OK
    ctx.api.revoke(name, privileges, who[0], who[1])
    if ctx.out.json_mode:
        ctx.out.json({"object": name, "privileges": privileges, "from": list(who), "done": True})
    else:
        ctx.out.line(f"revoked {','.join(privileges)} on {name} from {who[0]} {who[1]}")
    return EXIT_OK


def grants(ctx: Context) -> int:
    on: Optional[str] = ctx.arg("on")
    role, user = ctx.arg("role"), ctx.arg("user")
    if not on and not role and not user:
        raise UsageError("say which grants: --on <object>, or --role <name>, or --user <name>")
    listed = ctx.api.grants(
        on=on,
        grantee_type="ROLE" if role else "USER" if user else None,
        grantee=role or user,
    )
    ctx.out.result(
        listed,
        columns=["object", "privilege", ("granteeType", "TO"), "grantee", ("grantedBy", "BY")],
        empty="no grants you may see",
    )
    return EXIT_OK


def access(ctx: Context) -> int:
    verb = ctx.args.verb or "why"
    if verb != "why":  # pragma: no cover - argparse offers only why
        raise UsageError("usage: pravaha access why <user> <object>")
    user = ctx.arg("user_name")
    name = ctx.arg("object")
    if not user or not name:
        raise UsageError("usage: pravaha access why <user> <object>")
    answer = ctx.api.access(str(user), str(name))
    out = ctx.out
    if out.json_mode:
        out.json(answer)
        return EXIT_OK
    out.line(f"{answer.get('user')} on {answer.get('object')}:")
    rows = []
    for line in answer.get("privileges") or []:
        rows.append(
            {
                "privilege": line.get("privilege"),
                "allowed": bool(line.get("allowed")),
                "why": "; ".join(line.get("via") or []) if line.get("allowed") else line.get("refusal"),
            }
        )
    out.table(rows, ["privilege", "allowed", "why"])
    return EXIT_OK
