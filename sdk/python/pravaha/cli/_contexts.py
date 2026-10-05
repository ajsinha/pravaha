"""``pravaha context``: named connections, so ``--url``, ``--http``, the token and the TLS options
are said once per node rather than on every command.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Kept in ``contexts.json`` beside the token file, mode 0600, rewritten atomically
(:class:`pravaha.cli._settings.ContextStore`). Nothing here contacts a node, and nothing here
prints a token or a store password: ``show`` says ``<set>``. A mistake -- a context that does not
exist, one that already does -- exits 2, like any other usage error.
"""

from __future__ import annotations

import argparse
from typing import Any

from pravaha.cli._common import EXIT_OK, Context, UsageError
from pravaha.cli._settings import (
    CONTEXT_KEYS,
    CONTEXT_SWITCHES,
    ContextStore,
    mask,
    selected_context,
)


def _given(args: argparse.Namespace) -> "dict[str, Any]":
    """The connection flags on this command line, as a context stores them."""
    given: "dict[str, Any]" = {}
    for key in CONTEXT_KEYS:
        value = getattr(args, key.replace("-", "_"), None)
        if key in CONTEXT_SWITCHES:
            if value:
                given[key] = True
        elif key == "timeout":
            if value is not None:
                given[key] = float(value)
        elif value not in (None, ""):
            given[key] = str(value)
    return given


def _name(ctx: Context, usage: str) -> str:
    name = ctx.arg("context_name")
    if not name or not str(name).strip():
        raise UsageError(f"usage: pravaha context {usage}")
    return str(name)


def _shown(entry: "dict[str, Any]") -> "dict[str, Any]":
    return {key: mask(key, entry[key]) for key in CONTEXT_KEYS if key in entry}


def context(ctx: Context) -> int:
    verb = ctx.args.verb or "list"
    store = ContextStore()
    out = ctx.out
    if verb == "list":
        return _list(ctx, store)
    if verb == "current":
        name, source = selected_context(ctx.args)
        if out.json_mode:
            out.json({"name": name, "source": source})
        elif name:
            out.line(name)
            if source != "current":
                out.note(f"chosen by {'--context' if source == 'flag' else 'PRAVAHA_CONTEXT'}")
        else:
            out.note("no context is in use: the flags, the environment and the defaults decide")
        return EXIT_OK
    if verb == "show":
        name = ctx.arg("context_name") or selected_context(ctx.args)[0]
        if not name:
            raise UsageError("no context is in use; name one: pravaha context show <name>")
        entry = store.get(str(name))
        if out.json_mode:
            out.json({"name": name, "current": name == store.current, "settings": _shown(entry)})
        else:
            out.fields([("name", name), ("current", name == store.current)]
                       + [(key, value) for key, value in _shown(entry).items()])
        return EXIT_OK
    if verb == "add":
        name = _name(ctx, "add <name> --url URL --http URL [--tls-ca PEM ...]")
        if name in store.contexts:
            raise UsageError(f"a context named {name!r} exists; change it with "
                             f"`pravaha context set {name} ...`")
        store.contexts[name] = _given(ctx.args)
        if ctx.arg("use"):
            store.current = name
        path = store.save()
        _said(ctx, name, f"added {name} to {path}"
              + ("; it is the current context" if ctx.arg("use") else
                 f"; `pravaha context use {name}` makes it the current one"))
        return EXIT_OK
    if verb == "set":
        name = _name(ctx, "set <name> [--url URL ...] [--unset KEY]")
        entry = store.get(name)
        changes = _given(ctx.args)
        unset = list(ctx.arg("unset") or [])
        if not changes and not unset:
            raise UsageError("say what to change: a connection flag (--url, --http, --tls-ca ...) "
                             "or --unset KEY")
        entry.update(changes)
        for key in unset:
            entry.pop(key, None)
        path = store.save()
        _said(ctx, name, f"changed {', '.join(sorted(set(changes) | set(unset)))} of {name} in {path}")
        return EXIT_OK
    if verb == "use":
        name = _name(ctx, "use <name>")
        store.get(name)
        store.current = name
        store.save()
        _said(ctx, name, f"now using {name}")
        return EXIT_OK
    # remove
    name = _name(ctx, "remove <name>")
    store.get(name)
    del store.contexts[name]
    was_current = store.current == name
    if was_current:
        store.current = None
    store.save()
    _said(ctx, name, f"removed {name}" + ("; no context is in use now" if was_current else ""))
    return EXIT_OK


def _list(ctx: Context, store: ContextStore) -> int:
    out = ctx.out
    names = sorted(store.contexts)
    if ctx.arg("names"):
        for name in names:
            out.out.write(name + "\n")
        return EXIT_OK
    rows = [
        {
            "current": "*" if name == store.current else "",
            "name": name,
            "url": store.contexts[name].get("url"),
            "http": store.contexts[name].get("http"),
            "token": "<set>" if store.contexts[name].get("token") else None,
        }
        for name in names
    ]
    if out.json_mode:
        out.json({"current": store.current,
                  "contexts": [dict(r, current=r["current"] == "*") for r in rows]})
        return EXIT_OK
    if not rows:
        out.note("no contexts: `pravaha context add NAME --url URL --http URL` makes one")
        return EXIT_OK
    out.table(rows, [("current", " "), "name", "url", "http", "token"])
    return EXIT_OK


def _said(ctx: Context, name: str, text: str) -> None:
    if ctx.out.json_mode:
        ctx.out.json({"name": name, "done": True})
    else:
        ctx.out.line(text)
