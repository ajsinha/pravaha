"""The ``pravaha`` alert commands (ADR-057): list, show, pause, resume, snooze, acknowledge -- and
create and drop, which are statements.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

``pravaha alerts ...`` asks the node's HTTP API (``/api/v1/alerts``). ``pravaha alert create`` and
``pravaha alert drop`` write the ``CREATE ALERT`` / ``DROP ALERT`` statement and send it over Arrow
Flight, exactly as ``pravaha query --sql 'CREATE ALERT ...'`` would, so the two spellings cannot
decide differently. A drop takes the alert's state and history with it, so it changes nothing
without ``--yes``.
"""

from __future__ import annotations

import re
from typing import Any, Optional, Sequence, Union

from pravaha.cli._common import EXIT_OK, Context, UsageError, csv

_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_$]*$")


def _ident(name: str) -> str:
    """A name as SQL: bare when it can be, double-quoted otherwise."""
    return name if _NAME.match(name) else '"' + name.replace('"', '""') + '"'


def _literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def create_sql(
    name: str,
    on: str,
    notify: Sequence[str],
    where: Optional[str] = None,
    options: "Optional[dict[str, str]]" = None,
) -> str:
    """The ``CREATE ALERT`` statement ``pravaha alert create`` sends."""
    if not notify:
        raise UsageError("name at least one channel: --notify <channel>[,<channel>...]")
    view = ".".join(_ident(part) for part in on.split("."))
    sql = f"CREATE ALERT {_ident(name)} ON {view}"
    if where and where.strip():
        sql += f" WHERE {where.strip()}"
    sql += " NOTIFY " + ", ".join(_ident(channel) for channel in notify)
    if options:
        written = []
        for key, value in options.items():
            if key == "include":
                columns = ", ".join(_ident(c.strip()) for c in value.split(",") if c.strip())
                written.append(f"include = ({columns})")
            else:
                written.append(f"{key} = {_literal(value)}")
        sql += " WITH (" + ", ".join(written) + ")"
    return sql


def _options(ctx: Context) -> "dict[str, str]":
    options: "dict[str, str]" = {}
    for flag, key in (
        ("severity", "severity"),
        ("fire_after", "fire_after"),
        ("clear_after", "clear_after"),
        ("dedupe", "dedupe"),
        ("resend_every", "resend_every"),
        ("include", "include"),
    ):
        value = ctx.arg(flag)
        if value:
            options[key] = str(value)
    return options


_LIST_COLUMNS: "Sequence[Union[str, tuple[str, str]]]" = [
    "name",
    "view",
    "state",
    "firing",
    "pending",
    "severity",
    ("channelText", "CHANNELS"),
    ("problem", "PROBLEM"),
]


def _rows(alerts: "list[dict[str, Any]]") -> "list[dict[str, Any]]":
    rows = []
    for item in alerts:
        problem = item.get("deliveryError") or item.get("problem") or ""
        if item.get("following") and item.get("following") != "FOLLOWING" and not problem:
            problem = str(item.get("following"))
        rows.append(dict(item, channelText=",".join(item.get("channels") or []), problem=problem))
    return rows


def _key_text(key: Any) -> str:
    if not isinstance(key, dict) or not key:
        return "(the whole answer)"
    return ", ".join(f"{k}={v}" for k, v in key.items())


# ---------------------------------------------------------------------------------- alerts ...


def alerts(ctx: Context) -> int:
    verb = ctx.args.verb or "ls"
    api = ctx.api
    out = ctx.out
    if verb == "ls":
        out.result(_rows(api.alerts()), columns=_LIST_COLUMNS, empty="no alerts you may see")
        return EXIT_OK
    if verb == "channels":
        out.result(api.alert_channels(), columns=["name", "plugin"], empty="no notifier channels are bound")
        return EXIT_OK
    name = ctx.arg("name")
    if not name:
        raise UsageError(f"usage: pravaha alerts {verb} <alert>")
    name = str(name)
    if verb == "show":
        return _show(ctx, name)
    if verb == "pause":
        _changed(ctx, api.pause_alert(name), f"{name} paused: it keeps following its view and says nothing")
        return EXIT_OK
    if verb == "resume":
        _changed(ctx, api.resume_alert(name), f"{name} resumed: what changed meanwhile is sent now")
        return EXIT_OK
    if verb == "snooze":
        duration = ctx.arg("duration")
        if not duration:
            raise UsageError("usage: pravaha alerts snooze <alert> <duration>  (30m, 2h, PT2H)")
        snoozed = api.snooze_alert(name, str(duration))
        _changed(ctx, snoozed, f"{name} snoozed until {snoozed.get('snoozedUntil') or '-'}")
        return EXIT_OK
    # ack
    key = ctx.arg("key")
    acked = api.ack_alert(name, str(key) if key else None)
    count = int(acked.get("acknowledged") or 0)
    _changed(ctx, acked, f"{name}: {count} firing key{'' if count == 1 else 's'} acknowledged")
    return EXIT_OK


def _show(ctx: Context, name: str) -> int:
    detail = ctx.api.alert(name)
    out = ctx.out
    if out.json_mode:
        out.json(detail)
        return EXIT_OK
    alert = dict(detail.get("alert") or {})
    options = alert.get("options") or {}
    out.fields(
        [
            ("name", alert.get("name")),
            ("view", alert.get("view")),
            ("state", str(alert.get("state") or "")
             + (f" until {alert.get('snoozedUntil')}" if alert.get("snoozedUntil") else "")),
            ("following", alert.get("following")),
            ("condition", alert.get("condition") or "(every row of the view)"),
            ("channels", ", ".join(alert.get("channels") or [])),
            ("severity", alert.get("severity")),
            ("options", ", ".join(f"{k}={v}" for k, v in options.items()) or "-"),
            ("owner", alert.get("owner")),
            ("firing", alert.get("firing")),
            ("pending", alert.get("pending")),
            ("delivery", alert.get("deliveryError") or "up to date"),
        ]
    )
    keys = [
        dict(k, keyText=_key_text(k.get("key")), owedText=k.get("owed") or "-",
             ackText=k.get("acknowledgedBy") or "-")
        for k in detail.get("keys") or []
    ]
    out.line()
    out.line(out.bold("keys:"))
    if keys:
        out.table(keys, [("keyText", "KEY"), "state", "episode", ("since", "SINCE"), "notified",
                         ("owedText", "OWED"), ("ackText", "ACK")])
    else:
        out.line("  none in the condition")
    sent = [dict(n, keyText=_key_text(n.get("key"))) for n in detail.get("notifications") or []]
    out.line()
    out.line(out.bold("recent notifications:"))
    if sent:
        out.table(sent[:20], ["at", "kind", ("keyText", "KEY"), "episode", "outcome", "detail"])
    else:
        out.line("  none yet")
    return EXIT_OK


def _changed(ctx: Context, answer: "dict[str, Any]", text: str) -> None:
    if ctx.out.json_mode:
        ctx.out.json(answer)
    else:
        ctx.out.line(text)


# ---------------------------------------------------------------------------------- alert ...


def alert(ctx: Context) -> int:
    verb = ctx.args.verb
    name = str(ctx.require("name"))
    out = ctx.out
    if verb == "create":
        sql = create_sql(
            name,
            str(ctx.require("on")),
            csv(ctx.arg("notify")),
            ctx.arg("where"),
            _options(ctx),
        )
        return _statement(ctx, sql)
    # drop
    sql = f"DROP ALERT {'IF EXISTS ' if ctx.arg('if_exists') else ''}{_ident(name)}"
    if not ctx.confirmed():
        if out.json_mode:
            out.json({"alert": name, "action": "drop", "sql": sql, "done": False})
        else:
            out.line(f"would drop the alert {name}, with the state of every key and its notification history")
            out.note(f"nothing was changed; run `pravaha alert drop {name} --yes` to do it")
        return EXIT_OK
    return _statement(ctx, sql)


def _statement(ctx: Context, sql: str) -> int:
    if ctx.arg("print_sql"):
        ctx.out.line(sql)
        return EXIT_OK
    result = ctx.client.query(sql)
    columns = result.columns
    rows = [dict(zip(columns, row)) for row in result]
    if ctx.out.json_mode:
        ctx.out.json(rows)
        return EXIT_OK
    for row in rows:
        detail = row.get("detail")
        ctx.out.line(f"{row.get('name')}: {row.get('state')}" + (f" -- {detail}" if detail else ""))
    return EXIT_OK
