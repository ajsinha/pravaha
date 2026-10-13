"""The ``pravaha streams create|edit|delete|enable|disable`` and ``pravaha connectors
create|edit|delete|enable|disable|test`` commands: the declaration files a person would write,
written through the node (ADR-064).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The node writes ``config/streams/<name>.yaml`` or ``config/connectors/<name>.yaml`` atomically
and applies it at once. ``--dry-run`` writes nothing and prints the file that would be written,
whether the node accepts it, and what uses the object. A refusal -- in use (PRV-1065), a literal
secret (PRV-1063), a name already declared (PRV-1069) -- writes nothing and exits 1.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

from pravaha.cli._common import EXIT_OK, EXIT_REFUSED, Context, UsageError

#: The command's collection, as the API names it.
_STREAMS = "streams"
_CONNECTORS = "connectors"


def options_of(pairs: "Optional[list[str]]") -> "dict[str, str]":
    """``--option key=value``, repeated, as a map; a pair without ``=`` is a usage error."""
    out: "dict[str, str]" = {}
    for pair in pairs or []:
        key, sep, value = pair.partition("=")
        if not sep or not key.strip():
            raise UsageError(f"--option takes key=value, got {pair!r}")
        out[key.strip()] = value
    return out


def _yaml_file(ctx: Context) -> Optional[str]:
    path = ctx.arg("file")
    if not path:
        return None
    try:
        return Path(path).read_text(encoding="utf-8")
    except OSError as e:
        raise UsageError(f"--file {path}: {e.strerror or e}") from None


def _stream_body(ctx: Context) -> "dict[str, Any]":
    text = _yaml_file(ctx)
    if text is not None:
        return {"yaml": text}
    body: "dict[str, Any]" = {"schema": ctx.require("schema", "--schema (or --file)")}
    for flag, key in (("event_time", "eventTime"), ("out_of_orderness", "outOfOrderness"),
                      ("allowed_lateness", "allowedLateness")):
        if ctx.arg(flag):
            body[key] = ctx.arg(flag)
    if ctx.arg("disabled"):
        body["enabled"] = False
    plugin, connector = ctx.arg("plugin"), ctx.arg("connector")
    if plugin or connector:
        source: "dict[str, Any]" = {"options": options_of(ctx.arg("option"))}
        if plugin:
            source["plugin"] = plugin
        if connector:
            source["connector"] = connector
        body["source"] = source
    elif ctx.arg("option"):
        raise UsageError("--option is a source option: name the source with --plugin or --connector")
    return body


def _connector_body(ctx: Context) -> "dict[str, Any]":
    text = _yaml_file(ctx)
    if text is not None:
        return {"yaml": text}
    body: "dict[str, Any]" = {
        "plugin": ctx.require("plugin", "--plugin (or --file)"),
        "options": options_of(ctx.arg("option")),
    }
    if ctx.arg("disabled"):
        body["enabled"] = False
    return body


def _show(ctx: Context, change: "dict[str, Any]") -> int:
    """Prints what a write did -- or, on a dry run, would do -- and exits 1 when it is refused."""
    if ctx.out.json_mode:
        ctx.out.json(change)
        return EXIT_OK if change.get("valid", True) else EXIT_REFUSED
    kind, name = change.get("kind"), change.get("name")
    action = str(change.get("action") or "")
    dependents = change.get("dependents") or []
    if not change.get("valid", True):
        ctx.out.warn(f"{change.get('code')}  {change.get('message')}")
        if dependents:
            ctx.out.note("used by: " + ", ".join(dependents))
        ctx.out.note("dry run: nothing was written")
        return EXIT_REFUSED
    if change.get("dryRun"):
        ctx.out.line(ctx.out.good("would " + action) + f" {kind} {name}: {change.get('path')}")
    elif action == "unchanged":
        ctx.out.line(f"{kind} {name} unchanged: {change.get('path')} already says so")
    else:
        past = {"create": "created", "update": "updated", "delete": "deleted",
                "enable": "enabled", "disable": "disabled"}.get(action, action)
        ctx.out.line(ctx.out.good(past) + f" {kind} {name}: {change.get('path')}")
    if dependents:
        ctx.out.line("used by: " + ", ".join(dependents))
    if change.get("dryRun") and change.get("yaml"):
        ctx.out.line()
        ctx.out.line(str(change["yaml"]).rstrip("\n"))
    return EXIT_OK


def write(ctx: Context, collection: str) -> int:
    """create, edit, delete, enable, disable -- for ``streams`` or ``connectors``."""
    verb = ctx.args.verb
    name = ctx.require("object_name", "the name")
    # Not dest=dry_run: that routes to the planner of reads; the node itself answers this dry run.
    dry_run = bool(ctx.arg("node_dry_run"))
    api = ctx.api
    if verb in ("create", "edit"):
        body = _stream_body(ctx) if collection == _STREAMS else _connector_body(ctx)
        change = api.write_declaration(collection, name, body, update=verb == "edit", dry_run=dry_run)
    elif verb == "delete":
        change = api.delete_declaration(collection, name, dry_run=dry_run)
    elif verb in ("enable", "disable"):
        change = api.switch_declaration(collection, name, verb == "enable", dry_run=dry_run)
    else:  # pragma: no cover - argparse admits only the verbs above
        raise UsageError(f"unknown verb {verb!r}")
    return _show(ctx, change)


def streams(ctx: Context) -> int:
    return write(ctx, _STREAMS)


def connectors(ctx: Context) -> int:
    return write(ctx, _CONNECTORS)


def test(ctx: Context) -> int:
    """``pravaha connectors test NAME``: the plugin configured, opened and asked its health."""
    name = ctx.require("object_name", "the connection's name")
    outcome = ctx.api.test_connector(name, options_of(ctx.arg("option")) or None, ctx.arg("timeout_s"))
    ok = bool(outcome.get("ok"))
    if ctx.out.json_mode:
        ctx.out.json(outcome)
        return EXIT_OK if ok else EXIT_REFUSED
    word = ctx.out.good("ok") if ok else ctx.out.bad("failed")
    ctx.out.line(f"{name}: {word}  {outcome.get('state')} in {outcome.get('latencyMillis')} ms")
    ctx.out.fields(
        [
            ("plugin", outcome.get("plugin")),
            ("tested as", outcome.get("role")),
            ("detail", outcome.get("detail")),
            ("code", outcome.get("code")),
        ]
    )
    return EXIT_OK if ok else EXIT_REFUSED


def add_verbs(add: Any, collection: str) -> None:
    """Adds the write verbs to ``pravaha streams`` or ``pravaha connectors``."""
    run = streams if collection == _STREAMS else connectors
    what = "stream" if collection == _STREAMS else "connection"
    folder = f"config/{collection}/<name>.yaml"
    for verb, summary in (
        ("create", f"Declare a {what} by writing {folder}; in force at once."),
        ("edit", f"Replace a {what}'s file; refused while "
                 + ("a query reads it" if collection == _STREAMS else "a binding names it") + "."),
    ):
        v = add(verb, summary)
        v.set_defaults(run=run)
        v.add_argument("object_name", metavar="<name>")
        v.add_argument("--file", metavar="PATH", help="the whole YAML file, written as given")
        if collection == _STREAMS:
            v.add_argument("--schema", help="name:TYPE,name:TYPE (suffix ? for nullable)")
            v.add_argument("--event-time", metavar="COLUMN")
            v.add_argument("--out-of-orderness", metavar="10s")
            v.add_argument("--allowed-lateness", metavar="1m")
            v.add_argument("--plugin", help="the source plugin (pravaha plugins lists them)")
            v.add_argument("--connector", help="a named connection to take plugin and options from")
        else:
            v.add_argument("--plugin", help="the plugin (pravaha plugins lists them)")
        v.add_argument("--option", action="append", metavar="KEY=VALUE",
                       help="an option, repeatable; a secret is ${ENV_NAME}, never its value")
        v.add_argument("--disabled", action="store_true",
                       help="declare it switched off"
                            + (": read from nothing until enabled" if collection == _STREAMS
                               else ": the stream sources naming it stop"))
        v.add_argument("--dry-run", dest="node_dry_run", action="store_true",
                       help="write nothing; print the file and whether the node would accept it")
    for verb, summary in (
        ("delete", f"Remove a {what}'s file and the {what}; refused while anything uses it."),
        ("enable", f"Switch a {what} back on."),
        ("disable", f"Switch a {what} off"
                    + (": declared, read from nothing; its queries keep running." if collection == _STREAMS
                       else ": the stream sources naming it stop.")),
    ):
        v = add(verb, summary)
        v.set_defaults(run=run)
        v.add_argument("object_name", metavar="<name>")
        v.add_argument("--dry-run", dest="node_dry_run", action="store_true", help="change nothing; say what would happen")
    if collection == _CONNECTORS:
        v = add("test", "Test a connection through its plugin, in bounded time; never shows a secret.")
        v.set_defaults(run=test)
        v.add_argument("object_name", metavar="<name>")
        v.add_argument("--option", action="append", metavar="KEY=VALUE",
                       help="laid over the connection's for the test: a topic, a table")
        # Not --timeout: that is every command's per-request timeout, and the request must outlast this.
        v.add_argument("--within", dest="timeout_s", type=int, metavar="S",
                       help="seconds the test may take, 1 to 60 (default 10)")
