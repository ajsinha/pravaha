"""``--dry-run``: what a destructive or hard-to-undo command would do, from reads alone.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

``main`` sends a command given ``--dry-run`` here instead of to the command itself, so no path
through this module can reach a call that changes anything: every call below is an HTTP ``GET``,
or ``validate``/``explain``, which plan and keep nothing. ``--yes`` changes nothing about it.

A plan says what the engine was asked and what it answered (``facts``), what the command would do
(``effects``), the refusal the engine would give when the reads show one (``refusal``: exit 1),
and what cannot be known without doing it (``unknown``) -- said, never guessed. Exit 0 means the
reads show no reason for a refusal; it is not a promise, since the node may change in between.
"""

from __future__ import annotations

import difflib
from typing import Any, Callable, Optional

from pravaha.cli._common import EXIT_OK, EXIT_REFUSED, Context, UsageError, csv, ints
from pravaha.rest import ApiError

#: Every command and verb ``--dry-run`` is offered on, as ``command`` or ``command verb``.
COVERED = (
    "drop", "replace", "cutover", "rollback", "abandon", "finish", "grant", "revoke",
    "policy bind", "policy unbind", "policy drop", "user disable", "user enable", "user roles",
    "key revoke", "alert drop",
)

_ACTIVE = ("BACKFILLING", "CAUGHT_UP", "CUT_OVER")


class Plan:
    """What a dry run found, and how it prints."""

    def __init__(self, command: str, target: str) -> None:
        self.command = command
        self.target = target
        self.facts: "list[tuple[str, Any]]" = []
        self.effects: "list[str]" = []
        self.unknown: "list[str]" = []
        self.refusal: "Optional[dict[str, Any]]" = None
        self.details: "dict[str, Any]" = {}

    def fact(self, label: str, value: Any) -> None:
        self.facts.append((label, value))

    def refuse(self, code: Optional[str], message: str) -> None:
        if self.refusal is None:  # the first reason is the one the engine meets first
            self.refusal = {"code": code, "message": message}

    def read(self, call: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
        """A read whose refusal is the plan's: a missing object is what the command would meet.
        Nothing answering is not a refusal, and exits 3 as it does anywhere else."""
        try:
            return call(*args, **kwargs)
        except ApiError as exc:
            if exc.status == 0:
                raise
            self.refuse(exc.engine_code, exc.message)
            return None

    def maybe(self, what: str, call: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
        """A read that only adds detail: when it is refused, the plan says what is not known."""
        try:
            return call(*args, **kwargs)
        except ApiError as exc:
            if exc.status == 0:
                raise
            self.unknown.append(f"{what}: the node did not say ({exc.engine_code or exc.status} "
                                f"{exc.message})")
            return None

    def to_dict(self) -> "dict[str, Any]":
        return {
            "dryRun": True,
            "command": self.command,
            "target": self.target,
            "wouldSucceed": self.refusal is None,
            "refusal": self.refusal,
            "facts": {label: value for label, value in self.facts},
            "effects": self.effects,
            "unknown": self.unknown,
            **self.details,
        }

    def print(self, ctx: Context) -> int:
        out = ctx.out
        if out.json_mode:
            out.json(self.to_dict())
        else:
            out.line(f"dry run: {self.command} {self.target} -- nothing was changed")
            if self.facts:
                out.fields([(label, value) for label, value in self.facts])
            for name, value in self.details.items():
                if isinstance(value, str) and "\n" in value:
                    out.line(out.bold(name + ":"))
                    out.line(value.rstrip("\n"))
            if self.refusal is not None:
                code = self.refusal.get("code")
                out.line(out.bad("would be refused: ") + (f"{code}  " if code else "")
                         + str(self.refusal.get("message")))
            elif self.effects:
                out.line("would:")
                for effect in self.effects:
                    out.line(f"  - {effect}")
            if self.unknown:
                out.line("not known without doing it:")
                for item in self.unknown:
                    out.line(f"  - {item}")
        return EXIT_REFUSED if self.refusal is not None else EXIT_OK


def run(ctx: Context) -> int:
    command = ctx.args.command
    verb = getattr(ctx.args, "verb", None)
    path = f"{command} {verb}" if verb else command
    planner = _PLANNERS.get(path)
    if planner is None:  # pragma: no cover - the parser offers --dry-run only where it is planned
        raise UsageError(f"pravaha {path} has no --dry-run")
    return planner(ctx).print(ctx)


# ---------------------------------------------------------------------------------- queries


def _administer(ctx: Context, plan: Plan, name: str) -> None:
    """Whether the node's policy lets this principal administer ``name``, as ``/me/permissions``
    says; a node that does not say leaves it to the command."""
    permissions = plan.maybe("whether you may administer it", ctx.api.permissions)
    if permissions is None:
        return
    view = next((v for v in permissions.get("views") or [] if v.get("name") == name), None)
    administer = (view or {}).get("administer") or {}
    if administer.get("allowed") is False:
        plan.refuse(None, f"you may not administer {name}: {administer.get('reason') or 'policy'}")


def _drop(ctx: Context) -> Plan:
    name = str(ctx.require("name"))
    plan = Plan("drop", name)
    query = plan.read(ctx.api.describe_query, name)
    if query is None:
        return plan
    shared = list(query.get("sharedWith") or [])
    dependants = list(query.get("dependants") or [])
    sink = query.get("sink") or {}
    plan.fact("state", query.get("state"))
    plan.fact("fingerprint", query.get("fingerprint"))
    plan.fact("owner", query.get("owner"))
    plan.fact("shared with", shared or "nothing")
    plan.fact("reads from", list(query.get("readsFrom") or []) or list(query.get("reads") or []))
    plan.fact("dependants", dependants or "none")
    plan.fact("sink", sink.get("name"))
    graph = plan.maybe("live subscriptions", ctx.api.query_plan, name)
    subscribers = ((graph or {}).get("query") or {}).get("subscribers")
    if graph is not None and subscribers is None:
        plan.unknown.append("live subscriptions: this node does not report them")
    plan.fact("subscribers", subscribers)
    if dependants:
        plan.refuse("PRV-8024", f"{', '.join(dependants)} follow{'s' if len(dependants) == 1 else ''} "
                    f"{name}'s answer; drop or replace them first")
    _administer(ctx, plan, name)
    plan.effects.append(f"the name {name} goes: a read or subscription of {name} is refused after it")
    if shared:
        plan.effects.append(f"the computation (fingerprint {query.get('fingerprint')}) keeps running: "
                            f"{', '.join(shared)} still name{'s' if len(shared) == 1 else ''} it")
    else:
        plan.effects.append("it is the computation's last name: the computation stops and its view "
                            "and state are released")
        if sink:
            plan.effects.append(f"the sink {sink.get('name')} is sent nothing more")
    if subscribers:
        plan.effects.append(f"{subscribers} live subscription{'s' if subscribers != 1 else ''} to "
                            f"{name} end{'s' if subscribers == 1 else ''}")
    plan.details.update(sharedWith=shared, dependants=dependants, releasesComputation=not shared,
                        subscribers=subscribers)
    return plan


def _plan_text(ctx: Context, plan: Plan, sql: str, keys: "list[int]") -> "tuple[str, Any]":
    explained = plan.maybe("the plan", ctx.api.explain, sql, keys=keys) or {}
    return str(explained.get("plan") or ""), explained


def _replace(ctx: Context) -> Plan:
    name = str(ctx.require("name"))
    sql = ctx.sql()
    keys = ints(ctx.arg("keys", "0"), "--keys")
    plan = Plan("replace", name)
    query = plan.read(ctx.api.describe_query, name)
    if query is None:
        return plan
    plan.fact("state", query.get("state"))
    plan.fact("fingerprint now", query.get("fingerprint"))
    shared = list(query.get("sharedWith") or [])
    try:
        current = ctx.api.replacement(name)
    except ApiError as exc:
        if exc.status == 0:
            raise
        current = None  # refused: not being replaced, which is what a replace needs
    if current and current.get("state") in _ACTIVE:
        plan.refuse("PRV-4017", f"'{name}' is already being replaced ({current.get('state')}): "
                    "cut over, roll back or abandon that one first")
    if shared:
        plan.refuse("PRV-8003", f"'{name}' shares its computation with {', '.join(shared)}, and a "
                    "replacement moves one name")
    if query.get("state") != "RUNNING":
        plan.refuse("PRV-8003", f"'{name}' is {query.get('state')}; a replacement needs it RUNNING "
                    "(resume it first)")
    _administer(ctx, plan, name)
    checked = ctx.api.validate(sql)
    diagnostics = list(checked.get("diagnostics") or [])
    plan.details["diagnostics"] = diagnostics
    if not checked.get("valid", False):
        first = diagnostics[0] if diagnostics else {}
        plan.refuse(first.get("code"), str(first.get("message") or "the new SQL does not plan"))
        return plan
    old_keys = [int(k.get("ordinal", 0)) for k in query.get("keyColumns") or []] or [0]
    new_text, explained = _plan_text(ctx, plan, sql, keys)
    old_text, _ = _plan_text(ctx, plan, str(query.get("sql") or ""), old_keys)
    fingerprint = explained.get("fingerprint")
    refused = explained.get("fingerprintRefusal") or {}
    if refused:
        plan.refuse(refused.get("code"), str(refused.get("message")))
    plan.fact("fingerprint new", fingerprint or "(this node does not say before registering)")
    diff = "".join(difflib.unified_diff(
        old_text.splitlines(keepends=True), new_text.splitlines(keepends=True),
        "running", "replacement", n=1))
    plan.details["planDiff"] = diff or "(the plans are the same)\n"
    plan.details["fingerprint"] = fingerprint
    if fingerprint and fingerprint == query.get("fingerprint"):
        plan.effects.append("the new version is the same computation as the running one: the view "
                            "would answer exactly as it does now")
    backfill = ctx.arg("backfill") or "history"
    rate = ctx.arg("rate_limit")
    plan.effects.append("a candidate version starts beside the running one; "
                        f"'{name}' keeps answering the running version meanwhile")
    if backfill == "history":
        plan.effects.append("a backfill starts: the candidate replays the streams' retained history"
                            + (f" at up to {rate} records a second" if rate else "")
                            + " before it can cut over")
    else:
        plan.effects.append("no backfill: the candidate starts at the live stream with empty state")
    if (ctx.arg("cutover") or "manual") == "auto":
        plan.effects.append("the name moves to the candidate on its own once it has caught up")
    else:
        plan.effects.append(f"the name moves only on `pravaha cutover --name {name}`")
    if backfill == "history":
        plan.unknown.append("whether every stream it reads can be replayed is decided when the "
                            "backfill starts")
    return plan


_STEPS = {
    "cutover": ("CAUGHT_UP", "the name moves to the candidate; the running version is retained "
                "for a rollback"),
    "rollback": ("CUT_OVER", "the name moves back to the version it replaced, at once; the "
                 "candidate is released"),
    "finish": ("CUT_OVER", "the version it replaced is released; there is no rollback after this"),
}


def _replacement_step(ctx: Context) -> Plan:
    verb = ctx.args.command
    name = str(ctx.require("name"))
    plan = Plan(verb, name)
    replacement = plan.read(ctx.api.replacement, name)
    if replacement is None:
        return plan
    state = str(replacement.get("state") or "")
    plan.fact("state", state)
    plan.fact("candidate", replacement.get("candidate"))
    plan.fact("replacing", replacement.get("replacing"))
    plan.fact("rollback until", replacement.get("rollbackUntil") if replacement.get(
        "rollbackAvailable") else None)
    plan.details["replacement"] = replacement
    if verb == "abandon":
        if state == "CUT_OVER":
            plan.refuse("PRV-8003", f"'{name}' has already cut over: roll it back, or finish it")
        elif state not in _ACTIVE:
            plan.unknown.append(f"the replacement has already ended ({state}); what abandoning it "
                                "again answers is the engine's to say")
        plan.effects.append("the candidate version is released, and its backfill with it; "
                            f"'{name}' goes on answering the running version")
        return plan
    needed, effect = _STEPS[verb]
    if state != needed:
        if verb == "cutover" and state == "BACKFILLING":
            plan.refuse("PRV-4014", f"'{name}' is still backfilling: the candidate has not caught up")
        else:
            plan.refuse("PRV-8003" if verb != "cutover" else None,
                        f"'{name}' is {state}; {verb} needs {needed}")
    elif verb == "rollback" and not replacement.get("rollbackAvailable"):
        plan.refuse("PRV-8003", "the version it replaced is no longer retained")
    plan.effects.append(effect)
    return plan


# ---------------------------------------------------------------------------------- grants


def _grant_or_revoke(ctx: Context) -> Plan:
    from pravaha.cli._catalog import _grantee, _privileges, _target

    verb = ctx.args.command
    privileges = _privileges(ctx)
    name = _target(ctx, f"{verb} <privileges> <object> --role <name> | --user <name> --dry-run")
    who = _grantee(ctx)
    plan = Plan(verb, f"{','.join(privileges)} on {name} {'to' if verb == 'grant' else 'from'} "
                      f"{who[0]} {who[1]}")
    detail = plan.read(ctx.api.catalog_object, name)
    if detail is None:
        return plan
    held = sorted({str(g.get("privilege")) for g in detail.get("grants") or []
                   if str(g.get("granteeType")).upper() == who[0] and g.get("grantee") == who[1]})
    mine = {str(p.get("privilege")): p.get("allowed")
            for p in (detail.get("access") or {}).get("privileges") or []}
    if mine.get("MANAGE") is False:
        plan.refuse(None, f"you do not hold MANAGE on {name}, which {verb} needs")
    plan.fact("object", (detail.get("object") or {}).get("name") or name)
    plan.fact("held now", held or "nothing you may see")
    if "ALL" in privileges:
        after: Any = "every privilege that applies" if verb == "grant" else "nothing"
        plan.unknown.append("which privileges ALL names on this kind of object is the engine's to say")
    elif verb == "grant":
        after = sorted(set(held) | set(privileges))
    else:
        after = sorted(set(held) - set(privileges))
        absent = sorted(set(privileges) - set(held))
        if absent:
            plan.effects.append(f"{','.join(absent)} {'is' if len(absent) == 1 else 'are'} not granted "
                                "to them here (that you may see): revoking changes nothing for it")
    plan.fact("held after", after or "nothing")
    plan.details.update(held=held, after=after)
    if verb == "grant":
        plan.effects.append(f"{who[0]} {who[1]} may {','.join(privileges)} on {name}")
    else:
        plan.effects.append(f"{who[0]} {who[1]} loses {','.join(privileges)} on {name} at their next "
                            "read, and their open subscriptions to it end")
    plan.unknown.append("grants reaching them through a role or a namespace are not changed, and "
                        "are listed only as far as you may see them")
    return plan


# ---------------------------------------------------------------------------------- policies


def _policy(ctx: Context) -> Plan:
    from pravaha.cli._policy import _name, _place, _where

    verb = ctx.args.verb
    name = _name(ctx, f"{verb} <policy> --dry-run")
    on: Optional[str] = None
    tag: Optional[str] = None
    if verb != "drop":
        on, tag = _place(ctx, verb)
    where = on if on else f"TAG '{tag}'" if tag else ""
    plan = Plan(f"policy {verb}", f"{name}{' ' + ('to' if verb == 'bind' else 'from') + ' ' + where if where else ''}")
    policy = plan.read(ctx.api.policy, name)
    if policy is None:
        return plan
    bound = [_where(b) for b in policy.get("bindings") or []]
    plan.fact("kind", policy.get("kind"))
    plan.fact("expression", policy.get("expression"))
    plan.fact("bound to", bound or "nothing")
    plan.details["boundTo"] = bound
    if verb == "drop":
        if bound:
            plan.refuse("PRV-7040", f"{name} is still bound to {', '.join(bound)}: unbind it first")
        plan.effects.append(f"{name} is dropped; nothing it narrowed is narrowed any more")
        return plan
    if verb == "bind":
        if where in bound:
            plan.refuse("PRV-7040", f"{name} is already bound to {where}")
        if on:
            plan.read(ctx.api.catalog_object, on)
        plan.effects.append(f"every read of {where} by a role it does not except is narrowed by "
                            f"{name}, from the next read")
        plan.unknown.append("a conflict with another mask on the same column for the same reader is "
                            "found when it is bound")
        return plan
    if where not in bound:
        plan.effects.append(f"nothing: {name} is not bound to {where}; the engine answers "
                            "'was not bound'")
    else:
        plan.effects.append(f"{name} stops narrowing {where}: what it hid there is shown again")
    return plan


# ---------------------------------------------------------------------------------- identity


def _user(ctx: Context) -> Plan:
    verb = ctx.args.verb
    name = str(ctx.require("target", "<name>"))
    plan = Plan(f"user {verb}", name)
    users = plan.read(ctx.api.users)
    if users is None:
        return plan
    found = next((u for u in users if u.get("username") == name), None)
    if found is None:
        plan.refuse("PRV-7021", f"no user named {name!r}")
        return plan
    roles = list(found.get("roles") or [])
    plan.fact("status", found.get("status"))
    plan.fact("roles", roles)
    plan.fact("last login", found.get("lastLoginAt"))
    if verb == "roles":
        wanted = csv(ctx.require("roles"))
        plan.fact("roles after", wanted)
        added = sorted(set(wanted) - set(roles))
        removed = sorted(set(roles) - set(wanted))
        plan.details.update(added=added, removed=removed)
        plan.effects.append(f"roles become {','.join(wanted)}"
                            + (f"; gains {','.join(added)}" if added else "")
                            + (f"; loses {','.join(removed)}" if removed else "")
                            + ("" if added or removed else "; no change"))
        plan.unknown.append("whether their open sessions keep the old roles until they sign in "
                            "again is the engine's to say")
        return plan
    wanted_status = "disabled" if verb == "disable" else "active"
    if str(found.get("status") or "").lower() == wanted_status:
        plan.effects.append(f"nothing: {name} is already {wanted_status}")
        return plan
    if verb == "enable":
        plan.effects.append(f"{name} may sign in again")
        return plan
    sessions = plan.maybe("their sessions", ctx.api.sessions, all_sessions=True)
    theirs = [s for s in sessions or [] if name in (s.get("username"), s.get("user"))]
    if sessions is not None:
        plan.fact("sessions", len(theirs))
    plan.effects.append(f"{name} can no longer sign in, and every session of theirs ends"
                        + (f" ({len(theirs)} now)" if sessions is not None else ""))
    return plan


def _key(ctx: Context) -> Plan:
    target = str(ctx.require("target", "<keyId>"))
    plan = Plan("key revoke", target)
    every = plan.maybe("keys other than yours", ctx.api.keys, all_keys=True)
    listed = every if every is not None else plan.read(ctx.api.keys)
    if listed is None:
        return plan
    found = next((k for k in listed if k.get("keyId") == target), None)
    if found is None:
        if every is not None:
            plan.refuse("PRV-7021", f"no API key {target!r}")
        else:
            plan.unknown.append(f"{target} is not one of your keys, and only an administrator may "
                                "list everyone's")
        return plan
    for label in ("name", "holder", "roles", "status", "expiresAt", "lastUsedAt"):
        plan.fact(label, found.get(label))
    if str(found.get("status") or "").lower() == "revoked":
        plan.effects.append(f"nothing: {target} is already revoked")
    else:
        plan.effects.append(f"{target} stops working at once, and cannot be restored")
    return plan


# ---------------------------------------------------------------------------------- alerts


def _alert_drop(ctx: Context) -> Plan:
    name = str(ctx.require("name"))
    plan = Plan("alert drop", name)
    try:
        detail = ctx.api.alert(name)
    except ApiError as exc:
        if exc.status == 0:
            raise
        if ctx.arg("if_exists"):
            plan.effects.append(f"nothing: no alert {name} ({exc.engine_code}); IF EXISTS makes "
                                "that a success")
            return plan
        plan.refuse(exc.engine_code, exc.message)
        return plan
    alert = detail.get("alert") or {}
    keys = list(detail.get("keys") or [])
    plan.fact("view", alert.get("view"))
    plan.fact("state", alert.get("state"))
    plan.fact("channels", alert.get("channels"))
    plan.fact("firing", alert.get("firing"))
    plan.fact("keys", len(keys))
    plan.effects.append(f"the alert {name} stops: the state of its {len(keys)} key"
                        f"{'' if len(keys) == 1 else 's'} and its notification history are dropped")
    return plan


_PLANNERS: "dict[str, Callable[[Context], Plan]]" = {
    "drop": _drop,
    "replace": _replace,
    "cutover": _replacement_step,
    "rollback": _replacement_step,
    "abandon": _replacement_step,
    "finish": _replacement_step,
    "grant": _grant_or_revoke,
    "revoke": _grant_or_revoke,
    "policy bind": _policy,
    "policy unbind": _policy,
    "policy drop": _policy,
    "user disable": _user,
    "user enable": _user,
    "user roles": _user,
    "key revoke": _key,
    "alert drop": _alert_drop,
}
