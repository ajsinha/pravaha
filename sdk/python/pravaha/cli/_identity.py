"""The ``pravaha`` identity commands (ADR-052): signing in and out, passwords, users, API keys
and sessions.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The engine is the authority for every one of these; the CLI only asks. A password is read from
the terminal without echo when it is not given, so it need not sit in shell history, and a token
saved by ``login --save`` is written readable by its owner alone.
"""

from __future__ import annotations

import getpass
import sys
from typing import Any, Optional

from pravaha.cli._common import EXIT_OK, Context, UsageError, csv
from pravaha.cli._settings import forget_token, save_token, token_file
from pravaha.errors import PravahaError


def _secret(ctx: Context, name: str, prompt: str) -> str:
    """``--<name>``, or ``--<name>-stdin``'s first line, or a no-echo prompt on a terminal."""
    value = ctx.arg(name)
    if value:
        return str(value)
    if ctx.arg(name + "_stdin"):
        line = sys.stdin.readline().rstrip("\n")
        if not line:
            raise UsageError(f"--{name.replace('_', '-')}-stdin read nothing")
        return line
    if sys.stdin.isatty():
        return getpass.getpass(prompt)
    raise UsageError(
        f"--{name.replace('_', '-')} is required (or --{name.replace('_', '-')}-stdin, "
        "or run on a terminal to be asked)"
    )


def _positional(ctx: Context, usage: str) -> str:
    value = ctx.arg("target")
    if not value:
        raise UsageError(f"usage: pravaha {usage}")
    return str(value)


# ---------------------------------------------------------------------------------- signing in


def login(ctx: Context) -> int:
    user = ctx.require("user")
    password = _secret(ctx, "password", f"password for {user}: ")
    answer = ctx.settings.api(token=None).login(user, password)
    token = str(answer.get("token") or "")
    if answer.get("mustChangePassword"):
        ctx.out.warn(
            "this account must change its password first: pravaha password --current ... --new ..."
        )
    saved: Optional[str] = None
    if ctx.arg("save"):
        saved = str(save_token(token))
    if ctx.out.json_mode:
        shown = dict(answer)
        if saved:
            # Saved, so not printed: a token on stdout lands in logs and scrollback.
            shown.pop("token", None)
            shown["saved"] = saved
        ctx.out.json(shown)
        return EXIT_OK
    if saved:
        ctx.out.line(
            f"signed in as {user}; the session token is saved to {saved} (mode 0600), "
            f"expires {answer.get('expiresAt') or '-'}"
        )
    else:
        ctx.out.line(token)
        ctx.out.note(
            f"signed in as {user}; pass this as --token or PRAVAHA_TOKEN, or sign in with --save"
        )
    return EXIT_OK


def logout(ctx: Context) -> int:
    ended = False
    problem: Optional[str] = None
    if ctx.settings.token:
        try:
            ctx.api.logout()
            ended = True
        except PravahaError as exc:
            # The file goes either way: a token the engine will not end is still not one to keep.
            code = getattr(exc, "engine_code", None) or f"PRV-{exc.code}"
            problem = f"{code}  {getattr(exc, 'message', None) or exc}"
    removed = forget_token()
    if ctx.out.json_mode:
        ctx.out.json({"sessionEnded": ended, "tokenFileRemoved": removed, "error": problem})
    else:
        ctx.out.line(
            ("session ended" if ended else "no session was ended")
            + (f"; removed {token_file()}" if removed else "; no saved token to remove")
        )
        if problem:
            ctx.out.warn(problem)
    return EXIT_OK


def whoami(ctx: Context) -> int:
    me = ctx.api.me()
    if ctx.out.json_mode:
        ctx.out.json(me)
        return EXIT_OK
    ctx.out.fields(
        [
            ("user", me.get("username")),
            ("principal", me.get("principal")),
            ("tenant", me.get("tenant")),
            ("roles", sorted(me.get("roles") or [])),
            ("via", me.get("via")),
            ("name", me.get("displayName")),
            ("email", me.get("email")),
            ("password expires", me.get("passwordExpiresAt")),
            ("token from", ctx.settings.token_source),
        ]
    )
    if me.get("mustChangePassword"):
        ctx.out.warn("this account must change its password: pravaha password --current ... --new ...")
    return EXIT_OK


def password(ctx: Context) -> int:
    new = _secret(ctx, "new", "new password: ")
    reset = ctx.arg("reset_token")
    if reset:
        ctx.settings.api(token=None).redeem_reset(str(reset), new)
        message = "password set; every session of that account has ended"
    else:
        current = _secret(ctx, "current", "current password: ")
        ctx.api.change_password(current, new)
        message = "password changed; your other sessions have ended"
    if ctx.out.json_mode:
        ctx.out.json({"changed": True})
    else:
        ctx.out.line(message)
    return EXIT_OK


# ---------------------------------------------------------------------------------- users


def user(ctx: Context) -> int:
    verb = ctx.args.verb or "list"
    api = ctx.api
    out = ctx.out
    if verb == "list":
        listed = api.users()
        out.result(
            listed,
            columns=["username", "roles", "status", "tenant", ("lastLoginAt", "LAST LOGIN")],
            empty="no users",
        )
        return EXIT_OK
    name = _positional(ctx, f"user {verb} <name>")
    answer: Any
    if verb == "create":
        roles = csv(ctx.require("roles"))
        answer = api.create_user(
            name,
            roles=roles,
            password=_secret(ctx, "password", f"password for {name}: "),
            tenant=ctx.arg("tenant"),
            email=ctx.arg("email"),
            display_name=ctx.arg("display_name"),
            service=bool(ctx.arg("service")),
        )
        text = f"created {name}"
    elif verb == "disable" and not ctx.confirmed():
        if out.json_mode:
            out.json({"username": name, "action": "disable", "done": False})
        else:
            out.line(f"would disable {name}: they can no longer sign in, and every session of theirs ends")
            out.note(f"nothing was changed; run `pravaha user disable {name} --yes` to do it")
        return EXIT_OK
    elif verb in ("disable", "enable"):
        answer = api.update_user(name, status="active" if verb == "enable" else "disabled")
        text = f"{name} enabled" if verb == "enable" else f"{name} disabled; their sessions have ended"
    elif verb == "roles":
        roles = csv(ctx.require("roles"))
        answer = api.set_roles(name, roles)
        text = f"{name} now has {','.join(roles)}"
    elif verb == "attrs":
        answer, text = _attributes(ctx, name)
    else:  # reset
        answer = api.issue_password_reset(name)
        text = (
            f"reset token for {name} (shown once, until {answer.get('expiresAt')}):\n"
            f"{answer.get('resetToken')}\n"
            f"they set a password with: pravaha password --reset-token <token> --new <password>"
        )
    if out.json_mode:
        out.json(answer)
    else:
        out.line(text)
    return EXIT_OK


def _attributes(ctx: Context, name: str) -> "tuple[dict[str, Any], str]":
    """``user attrs``: the user's attributes, with ``KEY=VALUE`` pairs set and ``--unset`` ones
    removed. The engine replaces the whole set, so this reads it first and sends it back changed;
    with nothing to change it only shows it (STORECLAIMS-1)."""
    pairs = list(ctx.arg("pairs") or [])
    unset = list(ctx.arg("unset") or [])
    listed = [u for u in ctx.api.users() if u.get("username") == name]
    if not listed:
        raise UsageError(f"no user named {name!r}")
    current = dict(listed[0].get("attributes") or {})
    if not pairs and not unset:
        answer = listed[0]
    else:
        wanted = dict(current)
        for pair in pairs:
            key, sep, value = str(pair).partition("=")
            if not sep or not key:
                raise UsageError(f"{pair!r} is not KEY=VALUE; to remove an attribute use --unset KEY")
            wanted[key] = value
        for key in unset:
            if key not in wanted:
                raise UsageError(f"{name} has no attribute {key!r} to unset")
            wanted.pop(key)
        answer = ctx.api.set_attributes(name, wanted)
    shown = dict(answer.get("attributes") or {})
    text = (
        f"{name}: " + ", ".join(f"{k}={v}" for k, v in sorted(shown.items()))
        if shown
        else f"{name} has no attributes"
    )
    return answer, text


# ---------------------------------------------------------------------------------- API keys


def _issued(ctx: Context, key: "dict[str, Any]") -> None:
    if ctx.out.json_mode:
        ctx.out.json(key)
        return
    old = key.get("oldExpiresAt")
    ctx.out.note(
        f"key {key.get('keyId')}, expires {key.get('expiresAt')}"
        + (f"; the old key works until {old}" if old else "")
        + ". Shown once:"
    )
    ctx.out.line(str(key.get("key")))


def key(ctx: Context) -> int:
    verb = ctx.args.verb or "list"
    api = ctx.api
    out = ctx.out
    if verb == "list":
        out.result(
            api.keys(all_keys=bool(ctx.arg("all"))),
            columns=[
                ("keyId", "KEY ID"),
                "name",
                "holder",
                "roles",
                "status",
                ("expiresAt", "EXPIRES"),
                ("lastUsedAt", "LAST USED"),
            ],
            empty="no API keys",
        )
        return EXIT_OK
    if verb == "report":
        report = api.key_report()
        if out.json_mode:
            out.json(report)
            return EXIT_OK
        for part in ("unused", "expiring", "superseded"):
            out.line(out.bold(part + ":"))
            listed = report.get(part) or []
            if listed:
                out.table(
                    listed,
                    [("keyId", "KEY ID"), "name", "holder", ("expiresAt", "EXPIRES"),
                     ("lastUsedAt", "LAST USED")],
                )
            else:
                out.line("  none")
        return EXIT_OK
    target = _positional(ctx, f"key {verb} <{'name' if verb == 'create' else 'keyId'}>")
    if verb == "create":
        days = ctx.arg("days")
        _issued(
            ctx,
            api.create_key(
                target,
                roles=csv(ctx.arg("roles")) if ctx.arg("roles") else None,
                expires_days=int(days) if days is not None else None,
                for_user=ctx.arg("for_user"),
            ),
        )
        return EXIT_OK
    if verb == "rotate":
        _issued(ctx, api.rotate_key(target))
        return EXIT_OK
    # revoke
    if not ctx.confirmed():
        if out.json_mode:
            out.json({"keyId": target, "action": "revoke", "done": False})
        else:
            out.line(f"would revoke {target}: it stops working at once, and cannot be restored")
            out.note(f"nothing was changed; run `pravaha key revoke {target} --yes` to do it")
        return EXIT_OK
    api.revoke_key(target)
    if out.json_mode:
        out.json({"keyId": target, "action": "revoke", "done": True})
    else:
        out.line(f"revoked {target}")
    return EXIT_OK


# ---------------------------------------------------------------------------------- sessions


def session(ctx: Context) -> int:
    verb = ctx.args.verb or "list"
    if verb == "list":
        ctx.out.result(
            ctx.api.sessions(all_sessions=bool(ctx.arg("all"))),
            columns=[
                "id",
                "username",
                ("createdAt", "CREATED"),
                ("lastSeenAt", "LAST SEEN"),
                ("expiresAt", "EXPIRES"),
                "current",
            ],
            empty="no sessions",
        )
        return EXIT_OK
    target = _positional(ctx, "session end <id>")
    ctx.api.end_session(target)
    if ctx.out.json_mode:
        ctx.out.json({"session": target, "ended": True})
    else:
        ctx.out.line(f"ended {target}")
    return EXIT_OK
