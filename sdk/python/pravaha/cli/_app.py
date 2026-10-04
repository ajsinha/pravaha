"""The ``pravaha`` command line: its parser, its dispatch, and what each failure exits with.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Exit codes are a contract scripts rely on:

* ``0`` -- done;
* ``1`` -- the engine refused, and its ``PRV-nnnn`` code and message are on stderr;
* ``2`` -- the command line or a setting was wrong, and nothing was sent;
* ``3`` -- nothing answered at the engine's address.

The assistant's commands (``ask``, ``explain-sql``, ``why``, ``assist``) keep the contract: a
model that failed exits ``1`` with the normalised error, a wrong assistant configuration exits
``2``; ``ask`` exits ``1`` when the engine still refuses the draft after its repair turns.

Every option that says how to reach the engine (``--url``, ``--http``, ``--token``, ``--json``,
the TLS options...) is accepted before the command or after it, because the Java CLI took them
after it and every page written for that should still work.
"""

from __future__ import annotations

import argparse
import contextlib
import os
import sys
import traceback
from typing import Any, NoReturn, Optional, Sequence, TextIO

import pravaha
from pravaha.assist.errors import AssistConfigError, AssistError
from pravaha.cli import _alerts, _assist, _catalog, _flight, _http, _identity, _policy
from pravaha.cli._common import (
    EXIT_OK,
    EXIT_REFUSED,
    EXIT_UNREACHABLE,
    EXIT_USAGE,
    Command,
    Context,
    UsageError,
)
from pravaha.cli._output import Output, to_json
from pravaha.cli._settings import Settings
from pravaha.errors import (
    InvalidDocsBaseUrlError,
    InvalidOptionsError,
    InvalidTlsOptionsError,
    MalformedEndpointError,
    MalformedTextError,
    PravahaError,
    configure_docs_base_from_environment,
    help_line,
)
from pravaha.rest import ApiError

PROG = "pravaha"

_OVERVIEW = """\
Transport: query, subscribe, register, queries, pause, resume, drop, the replacement commands,
dlq, debug and alert create/drop speak Arrow Flight to --url; every other command asks the node's
HTTP API at --http.

Destructive commands (drop, abandon, finish, lanes rebalance, key revoke, user disable, revoke,
catalog owner, policy unbind, policy drop, alert drop) say what they would do and change nothing
unless given --yes.

Offline -- planning or running SQL with no server -- is the Java tool `pravaha-engine`
(validate --schema, explain --schema, run).

The assistant (ask, explain-sql, why, assist) asks a configured model, with the engine as the
judge; see docs/guides/ASSIST.md. A model failure exits 1, a wrong assistant configuration 2; ask exits 1
when the engine still refuses the draft after its repair turns. Only --register registers.

pravaha <command> --help prints one command's flags without contacting anything.
Exit codes: 0 ok, 1 the engine refused (its PRV code on stderr), 2 usage, 3 cannot reach the engine.
"""


class _Parser(argparse.ArgumentParser):
    """An argparse parser whose mistakes are :class:`UsageError`, so ``main`` owns the exit."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        if sys.version_info >= (3, 14):
            # Help is text a person may paste into a ticket; escape codes in it are noise.
            kwargs.setdefault("color", False)
        super().__init__(*args, **kwargs)

    def error(self, message: str) -> NoReturn:
        where = " ".join(part for part in ("pravaha", self._command_path(), "--help") if part)
        raise UsageError(f"{message}\n({where} lists the flags)")

    def _command_path(self) -> str:
        return " ".join(self.prog.split()[1:])


def _global_options(parser: argparse.ArgumentParser, *, suppress: bool) -> None:
    """The options every command takes. On a command they default to *nothing*, so a value
    given before the command is not overwritten by the command's own default."""
    kwargs: dict[str, Any] = {"default": argparse.SUPPRESS} if suppress else {}
    group = parser.add_argument_group("connection")
    group.add_argument("--url", metavar="URL", **kwargs,
                       help="the node's Flight endpoint (env PRAVAHA_URL; grpc://localhost:19090)")
    group.add_argument("--http", metavar="URL", **kwargs,
                       help="the node's HTTP API (env PRAVAHA_HTTP or PRAVAHA_ENGINE_HTTP; "
                            "http://localhost:18080)")
    group.add_argument("--token", metavar="TOKEN", **kwargs,
                       help="a bearer token (env PRAVAHA_TOKEN; else the file login --save wrote)")
    group.add_argument("--insecure-token", action="store_true", **kwargs,
                       help="allow the token over plaintext grpc:// or http:// "
                            "(env PRAVAHA_INSECURE_TOKEN=true); for loopback or a local sidecar")
    group.add_argument("--timeout", type=float, metavar="SECONDS", **kwargs,
                       help="per-request timeout (env PRAVAHA_TIMEOUT; 60)")
    group.add_argument("--json", action="store_true", **kwargs,
                       help="machine output: JSON on stdout, and a JSON error on stderr")
    group.add_argument("--no-color", action="store_true", **kwargs,
                       help="no colour (also NO_COLOR, or when stdout is not a terminal)")
    tls = parser.add_argument_group("TLS (for grpc+tls:// and https://)")
    tls.add_argument("--tls-ca", metavar="PEM", **kwargs,
                     help="CA certificate to trust (env PRAVAHA_TLS_CA)")
    tls.add_argument("--tls-cert", metavar="PEM", **kwargs,
                     help="client certificate, for mutual TLS (env PRAVAHA_TLS_CERT)")
    tls.add_argument("--tls-key", metavar="PEM", **kwargs,
                     help="client private key (env PRAVAHA_TLS_KEY)")
    tls.add_argument("--tls-trust-store", metavar="FILE", **kwargs,
                     help="JKS/PKCS12 trust store (Flight only)")
    tls.add_argument("--tls-trust-store-password", metavar="P", **kwargs,
                     help="(env PRAVAHA_TLS_TRUST_STORE_PASSWORD)")
    tls.add_argument("--tls-trust-store-type", metavar="JKS|PKCS12", **kwargs)
    tls.add_argument("--tls-key-store", metavar="FILE", **kwargs,
                     help="JKS/PKCS12 key store (Flight only)")
    tls.add_argument("--tls-key-store-password", metavar="P", **kwargs,
                     help="(env PRAVAHA_TLS_KEY_STORE_PASSWORD)")
    tls.add_argument("--tls-key-store-type", metavar="JKS|PKCS12", **kwargs)
    tls.add_argument("--tls-override-hostname", metavar="HOST", **kwargs,
                     help="check the certificate against this name instead of the URL's host")
    tls.add_argument("--tls-no-verify", action="store_true", **kwargs,
                     help="do not verify the server's certificate at all; for a test only")


def _sql_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--sql", help="the SQL")
    parser.add_argument("--sql-file", metavar="PATH", help="read the SQL from a file")


def _yes(parser: argparse.ArgumentParser, what: str) -> None:
    parser.add_argument("--yes", "-y", action="store_true",
                        help=f"{what}; without it, print what would happen and change nothing")


class _Builder:
    """Adds a command with the global options on it, and remembers which function runs it."""

    def __init__(self, commands: "argparse._SubParsersAction[_Parser]") -> None:
        self.commands = commands
        #: Every command and verb parser, given the global options once all its own are added,
        #: so its --help lists what is particular to it first.
        self.made: list[_Parser] = []

    def add(self, name: str, run: Command, summary: str, **kwargs: Any) -> _Parser:
        parser: _Parser = self.commands.add_parser(
            name, help=summary, description=summary, allow_abbrev=False, **kwargs
        )
        parser.set_defaults(run=run)
        self.made.append(parser)
        return parser

    def verbs(self, parser: _Parser, run: Command, required: bool = False) -> Any:
        verbs = parser.add_subparsers(dest="verb", metavar="<verb>", parser_class=_Parser)
        verbs.required = required

        def add(name: str, summary: str) -> _Parser:
            verb: _Parser = verbs.add_parser(name, help=summary, description=summary,
                                             allow_abbrev=False)
            verb.set_defaults(run=run)
            self.made.append(verb)
            return verb

        return add


def build_parser() -> _Parser:
    parser = _Parser(
        prog=PROG,
        description="pravaha -- the command line for a running Pravaha engine. Ask once. "
                    "Answer always.",
        epilog=_OVERVIEW,
        formatter_class=argparse.RawDescriptionHelpFormatter,
        allow_abbrev=False,
    )
    parser.add_argument("--version", action="store_true", help="print the CLI's version and exit")
    _global_options(parser, suppress=False)
    commands = parser.add_subparsers(dest="command", metavar="<command>", parser_class=_Parser)
    b = _Builder(commands)

    # ------------------------------------------------------------------ Flight
    p = b.add("query", _flight.query, "Ask a question and print the rows.")
    _sql_options(p)
    p.add_argument("--params", metavar="A,B",
                   help="values for the ? placeholders, in order: integer, then decimal, else text")
    p.add_argument("--tsv", action="store_true",
                   help="tab-separated with a header, NULL for null (the Java CLI's format)")

    p = b.add("register", _flight.register, "Register a continuous query. It runs until dropped.")
    p.add_argument("--name", help="the view's name")
    _sql_options(p)
    p.add_argument("--keys", default="0", metavar="0,1",
                   help="the view's key as output-column ordinals (default 0)")
    p.add_argument("--sink", help="a sink the node binds under pravaha.sinks.<name>")
    p.add_argument("--retain", metavar="PT24H", help="how much event time the view keeps, or 'forever'")
    p.add_argument("--param", help=argparse.SUPPRESS)
    p.add_argument("--params", help=argparse.SUPPRESS)

    p = b.add("queries", _flight.queries, "List the continuous queries the node runs.")
    p.add_argument("--verbose", action="store_true", help="add each query's FEED")

    for verb, summary in (
        ("pause", "Stop a query without releasing it; its view keeps answering."),
        ("resume", "Start a paused query again."),
        ("drop", "Remove a query's name; the computation goes with its last name."),
    ):
        p = b.add(verb, _flight.lifecycle, summary)
        p.add_argument("--name", help="the query's name")
        if verb == "drop":
            _yes(p, "drop it")

    p = b.add("replace", _flight.replace, "Start a blue/green replacement of a running query.")
    p.add_argument("--name", help="the query being replaced")
    _sql_options(p)
    p.add_argument("--keys", default="0", metavar="0,1", help="the new version's key ordinals")
    p.add_argument("--backfill", choices=["history", "none"], help="replay history (default) or not")
    p.add_argument("--rate-limit", type=int, metavar="N", help="backfill ceiling, records a second")
    p.add_argument("--cutover", choices=["manual", "auto"], help="default manual")
    p.add_argument("--rollback-retention", metavar="PT1H",
                   help="how long the replaced version is kept for a rollback")
    p.add_argument("--wait", action="store_true", help="wait until the backfill has caught up")

    p = b.add("replacements", _flight.replacements, "How replacements are getting on.")
    p.add_argument("--name", help="only this query's")

    for verb, summary in (
        ("cutover", "Move the name to the new version."),
        ("rollback", "Put the replaced version back while it is retained."),
        ("abandon", "End a replacement that has not cut over."),
        ("finish", "Confirm a cutover: the replaced version is released; no rollback after."),
        ("throttle", "Set the backfill's rate, up to its ceiling."),
        ("pause-backfill", "Stop the backfill reading, keeping what it has read."),
        ("resume-backfill", "Start the backfill reading again."),
    ):
        p = b.add(verb, _flight.replacement_verb, summary)
        p.add_argument("--name", help="the query being replaced")
        if verb == "throttle":
            p.add_argument("--rate", type=int, metavar="N", help="records a second")
        if verb in ("abandon", "finish"):
            _yes(p, verb + " it")

    p = b.add("subscribe", _flight.subscribe, "Print a view's committed changes as they happen.")
    p.add_argument("--view", help="the view to follow")
    p.add_argument("--filter", action="append", metavar="COL=VAL[,COL=VAL]",
                   help="equality filters applied on the server; repeatable")
    p.add_argument("--snapshot", action="store_true",
                   help="print what the view holds first, then every commit after it, none missed: "
                        "its changelog (on a keyed view that upserts, every version of a key), or "
                        "with --answer the rows a read returns")
    p.add_argument("--answer", action="store_true",
                   help="how the view's answer moves (rows leaving -1, entering +1), not its changelog")
    p.add_argument("--reconnect", action="store_true",
                   help="reopen the stream after a restart instead of ending")
    p.add_argument("--reconnect-timeout", type=float, metavar="SECONDS",
                   help="give up after this long without a stream (default 300; 0 never)")
    p.add_argument("--limit", type=int, metavar="N",
                   help="stop once N rows (N >= 1) have printed, at the end of the commit or snapshot "
                        "that reaches N: a commit is never cut in half")
    p.add_argument("--buffer-rows", type=int, metavar="N", help="the server's buffer for you")
    p.add_argument("--overflow", choices=["CONFLATE", "DROP_OLDEST", "FAIL"], type=str.upper,
                   help="what the server does when you fall behind")

    p = b.add("dlq", _flight.dlq, "Dead letters: the records a query's feed could not decode.")
    add = b.verbs(p, _flight.dlq, required=True)
    for verb, summary in (
        ("list", "List them, newest first."),
        ("show", "Print one whole, with its bytes."),
        ("replay", "Feed chosen ones back through the query, at its current frontier."),
        ("count", "How deep the queue is, without the records (HTTP)."),
    ):
        v = add(verb, summary)
        v.add_argument("--name", help="the query")
        if verb == "list":
            v.add_argument("--offset", type=int, default=0)
            v.add_argument("--limit", type=int, default=50)
        if verb in ("show", "replay"):
            v.add_argument("--id", help="the dead letter's id" + (
                "; comma-separated for several" if verb == "replay" else ""))

    p = b.add("debug", _flight.debug, "Fork a query from a checkpoint and step it (ADR-048).")
    add = b.verbs(p, _flight.debug, required=True)
    v = add("fork", "Open a session; prints its id.")
    v.add_argument("--name", help="the query")
    v.add_argument("--checkpoint", type=int, help="a checkpoint id; default the newest")
    v = add("checkpoints", "Which checkpoints a session could be forked from.")
    v.add_argument("--name", help="the query")
    v = add("step", "Advance a session and say what changed.")
    v.add_argument("--session")
    v.add_argument("--step", default="row",
                   help="row | rows:N | commit | watermark:<nanos> | until:<column>:<op>:<value>")
    v = add("state", "What state the fork holds.")
    v.add_argument("--session")
    v = add("inspect", "One page of one operator's state.")
    v.add_argument("--session")
    v.add_argument("--operator")
    v.add_argument("--key")
    v.add_argument("--offset", type=int, default=0)
    v.add_argument("--limit", type=int, default=20)
    v = add("view", "The fork's own answer.")
    v.add_argument("--session")
    v = add("fixture", "Write the session out as a JUnit test.")
    v.add_argument("--session")
    v.add_argument("--name", help="what it reproduces; becomes the class name")
    v.add_argument("--out", metavar="PATH", help="a .java file, or a directory for one")
    add("sessions", "Every session you may see.")
    v = add("end", "Release the fork.")
    v.add_argument("--session")

    # ------------------------------------------------------------------ HTTP: the node
    b.add("status", _http.status, "The node: version, engine state, plugins.")
    b.add("health", _http.health, "The node's health; exit 1 unless UP or DEGRADED.")
    p = b.add("version", _http.version, "The CLI's version and the node's.")
    p.add_argument("--client", action="store_true", help="the CLI's only; ask no node")
    p = b.add("metrics", _http.metrics, "The node's Prometheus metrics, raw.")
    p.add_argument("--grep", metavar="TEXT", help="only lines containing this")
    b.add("plugins", _http.plugins, "Every plugin the node can load.")
    b.add("sinks", _http.sinks, "The sinks the node binds.")

    p = b.add("streams", _http.streams, "List, describe or declare streams.")
    add = b.verbs(p, _http.streams)
    add("list", "Every stream you may read.")
    v = add("describe", "One stream and its columns.")
    v.add_argument("stream_name", metavar="<stream>")
    v = add("declare", "Declare a stream (an administrative act).")
    v.add_argument("stream_name", metavar="<stream>")
    v.add_argument("--schema", help="name:TYPE,name:TYPE (suffix ? for nullable)")
    v.add_argument("--event-time", metavar="COLUMN")
    v.add_argument("--out-of-orderness", metavar="PT10S")

    p = b.add("views", _http.views, "List views, or describe one.")
    add = b.verbs(p, _http.views)
    add("list", "Every view you may see.")
    v = add("describe", "One view's schema, key, retention and sink.")
    v.add_argument("view_name", metavar="<view>")

    for name, run, summary in (
        ("describe", _http.describe, "One registered query in full, including its lane."),
        ("plan", _http.plan, "The plan a registered query runs, with operator metrics."),
    ):
        p = b.add(name, run, summary)
        p.add_argument("query_name", metavar="<query>", nargs="?")
        p.add_argument("--name", help="the query, if not given as an argument")

    p = b.add("validate", _http.validate, "Plan SQL against the node without running it.")
    _sql_options(p)
    p.add_argument("--schema", help=argparse.SUPPRESS)
    p = b.add("explain", _http.explain, "The plan the node would run for SQL.")
    _sql_options(p)
    p.add_argument("--level", default="physical", help="physical (default), logical or codegen")
    p.add_argument("--graph", action="store_true", help="with --json, the plan as nodes and edges")
    p.add_argument("--schema", help=argparse.SUPPRESS)
    for p in (commands.choices["validate"], commands.choices["explain"]):
        # The offline Java CLI's flags, accepted only so they are refused with directions.
        p.add_argument("--stream", help=argparse.SUPPRESS)
        p.add_argument("--event-time", help=argparse.SUPPRESS)
    b.add("run", _offline_run, "Offline: moved to `pravaha-engine run`.")

    p = b.add("lanes", _http.lanes, "Where every query runs, and an administrator's rebalance.")
    add = b.verbs(p, _http.lanes)
    add("list", "The lane settings and every query's lane.")
    v = add("rebalance", "Move shared queries onto lanes of their own while there is room.")
    v.add_argument("rebalance_what", nargs="?", choices=["status"], metavar="status",
                   help="the last or running rebalance, changing nothing")
    _yes(v, "start the rebalance")

    p = b.add("audit", _http.audit, "The node's recorded authorization decisions, newest first.")
    p.add_argument("--since", metavar="INSTANT", help="ISO-8601")
    p.add_argument("--until", metavar="INSTANT", help="ISO-8601")
    p.add_argument("--principal")
    p.add_argument("--view")
    p.add_argument("--action")
    p.add_argument("--decision", choices=["allow", "deny"])
    p.add_argument("--limit", type=int)
    p.add_argument("--cursor", help="the page after this one: the last page's nextCursor")
    b.add("tenants", _http.tenants, "Admission quotas and each tenant's use.")
    b.add("permissions", _http.permissions, "What this principal may do.")

    # ------------------------------------------------------------------ identity
    p = b.add("login", _identity.login, "Sign in and print (or --save) a session token.")
    p.add_argument("--user", help="the user name")
    p.add_argument("--password", help="omit to be asked without echo")
    p.add_argument("--password-stdin", action="store_true", help="read the password from stdin")
    p.add_argument("--save", action="store_true",
                   help="save the token to ~/.config/pravaha/token (mode 0600) instead of printing it")
    b.add("logout", _identity.logout, "End this session and delete the saved token.")
    b.add("whoami", _identity.whoami, "Who the engine says you are.")
    p = b.add("password", _identity.password, "Change your password, or redeem a reset token.")
    p.add_argument("--current", help="your current password; omit to be asked")
    p.add_argument("--current-stdin", action="store_true", help=argparse.SUPPRESS)
    p.add_argument("--new", help="the new password; omit to be asked")
    p.add_argument("--new-stdin", action="store_true", help="read the new password from stdin")
    p.add_argument("--reset-token", metavar="TOKEN",
                   help="a reset token an administrator issued, instead of --current")

    p = b.add("user", _identity.user, "Administer users (admin role).")
    add = b.verbs(p, _identity.user)
    add("list", "Every user.")
    v = add("create", "Create a user.")
    v.add_argument("target", metavar="<name>")
    v.add_argument("--roles", metavar="A,B")
    v.add_argument("--password", help="omit to be asked")
    v.add_argument("--password-stdin", action="store_true")
    v.add_argument("--tenant")
    v.add_argument("--email")
    v.add_argument("--display-name")
    v.add_argument("--service", action="store_true", help="a service account")
    v = add("disable", "Disable a user; their sessions end.")
    v.add_argument("target", metavar="<name>")
    _yes(v, "disable them")
    v = add("enable", "Enable a user.")
    v.add_argument("target", metavar="<name>")
    v = add("roles", "Replace a user's roles.")
    v.add_argument("target", metavar="<name>")
    v.add_argument("--roles", metavar="A,B")
    v = add("reset", "Issue a single-use password reset token.")
    v.add_argument("target", metavar="<name>")
    v = add("attrs", "Show, set or unset a user's attributes, the claims their credentials carry.")
    v.add_argument("target", metavar="<name>")
    v.add_argument("pairs", nargs="*", metavar="KEY=VALUE", help="attributes to set")
    v.add_argument("--unset", action="append", default=[], metavar="KEY",
                   help="an attribute to remove; repeat for more")

    p = b.add("key", _identity.key, "API keys: shown once, scoped to a subset of your roles.")
    add = b.verbs(p, _identity.key)
    v = add("list", "Your keys, or --all (admin).")
    v.add_argument("--all", action="store_true")
    v = add("create", "Issue a key.")
    v.add_argument("target", metavar="<name>")
    v.add_argument("--roles", metavar="A,B")
    v.add_argument("--days", type=int, help="days until it expires")
    v.add_argument("--for", dest="for_user", metavar="USER", help="a service account's (admin)")
    v = add("rotate", "A successor with the same scope; the old one works for an overlap.")
    v.add_argument("target", metavar="<keyId>")
    v = add("revoke", "Revoke a key at once.")
    v.add_argument("target", metavar="<keyId>")
    _yes(v, "revoke it")
    add("report", "Keys unused, expiring or superseded (admin).")

    p = b.add("session", _identity.session, "Sessions: yours, or everyone's (admin).")
    add = b.verbs(p, _identity.session)
    v = add("list", "Your sessions, or --all (admin).")
    v.add_argument("--all", action="store_true")
    v = add("end", "End a session.")
    v.add_argument("target", metavar="<id>")

    # ------------------------------------------------------------------ the assistant (ADR-058)
    p = b.add("ask", _assist.ask,
              "Draft a continuous query from a description (asks a model; the engine judges it).")
    p.add_argument("description", nargs="+", metavar="<description>",
                   help="what the query should answer, in plain English")
    p.add_argument("--name", help="the view's name (default: the model's suggestion)")
    p.add_argument("--repairs", type=int, default=3, metavar="N",
                   help="repair turns after a refusal, 0 to 3 (default 3)")
    p.add_argument("--register", action="store_true",
                   help="register the draft if the engine accepts it: asks to confirm on a "
                        "terminal, or pass --yes (needs --url: registration is a Flight call)")
    p.add_argument("--yes", "-y", action="store_true",
                   help="with --register, register without asking")
    p.add_argument("--show-context", action="store_true",
                   help="also print what the model was told about the catalogue")
    _assist_options(p, "draft")

    p = b.add("explain-sql", _assist.explain_sql,
              "A query in plain English, grounded in the engine's plan (asks a model).")
    _sql_options(p)
    p.add_argument("--query", metavar="NAME", help="a registered query, instead of --sql")
    p.add_argument("--level", default="physical", help="the plan given to the model: physical "
                                                       "(default) or logical")
    p.add_argument("--show-plan", action="store_true", help="print the engine's plan too")
    _assist_options(p)

    p = b.add("why", _assist.why,
              "What a refusal means and what to change (asks a model; the engine checks any rewrite).")
    p.add_argument("code", metavar="<PRV-nnnn>", help="the refusal code, such as PRV-2050")
    _sql_options(p)
    p.add_argument("--no-check", action="store_true",
                   help="do not ask the engine to validate a rewrite the model proposes")
    _assist_options(p)

    p = b.add("assist", _assist.assist, "The assistant's models, profiles and providers.")
    add = b.verbs(p, _assist.assist)
    add("models", "Every configured model, its key, and the profiles whose chains name it.")
    add("providers", "Every provider type: built-in and installed by entry point.")
    v = add("check", "Ping each enabled model cheaply (a models endpoint; no tokens where possible).")
    v.add_argument("--model", metavar="ID[,ID]", help="only these configured models")
    v = add("use", "Set a profile's fallback chain (first answers first).")
    v.add_argument("profile", metavar="<profile>")
    v.add_argument("chain", metavar="<model-id>[,<fallback>...]")
    v.add_argument("--default", action="store_true", help="also make it the default profile")
    _yes(v, "store the change")
    for verb in ("enable", "disable"):
        v = add(verb, f"{verb.capitalize()} a configured model.")
        v.add_argument("model_id", metavar="<model-id>")
        _yes(v, "store the change")
    v = add("eval", "Score a model on the golden set: drafts judged by the engine, compared with "
                    "the case studies' reference SQL.")
    v.add_argument("--profile", help="the profile whose chain answers (default: draft)")
    v.add_argument("--model", metavar="ID", help="ask only this configured model")
    v.add_argument("--limit", type=int, metavar="N", help="only the first N cases")
    v.add_argument("--case", metavar="ID[,ID]",
                   help="only these cases, or every case of a study (adtech-click-attribution)")
    # dest is not "run": that is the attribute every parser's set_defaults names its command by.
    v.add_argument("--run", dest="register_and_compare", action="store_true",
                   help="also register each draft and its reference under --prefix, compare the "
                        "engine's fingerprints and answers, and drop them (a node with the case "
                        "studies' data; needs --url)")
    v.add_argument("--prefix", default="assist_eval_", help="name prefix for --run's registrations")
    v.add_argument("--settle", type=float, default=5.0, metavar="S",
                   help="seconds --run waits before comparing answers (default 5)")
    v.add_argument("--full", action="store_true", help="with --json, include every draft")

    # ------------------------------------------------------------------ the catalogue (ADR-059)
    p = b.add("catalog", _catalog.catalog, "The catalogue: objects, namespaces, owners, tags.")
    add = b.verbs(p, _catalog.catalog)
    v = add("ls", "Objects you may see.")
    v.add_argument("--namespace", metavar="TENANT.NS")
    v.add_argument("--kind", help="NAMESPACE, VIEW, STREAM, SINK, SOURCE or LOOKUP")
    v = add("search", "Objects whose name, description, owner or a tag matches.")
    v.add_argument("object", metavar="<text>")
    add("namespaces", "Namespaces you may use.")
    v = add("show", "One object: owner, description, tags, grants and what you may do.")
    v.add_argument("object", metavar="<object>")
    v = add("create-namespace", "Create a namespace; you own it (CREATE on the tenant).")
    v.add_argument("object", metavar="<name>")
    v.add_argument("--comment", help="its description")
    v.add_argument("--if-not-exists", action="store_true")
    v = add("comment", "Describe an object (MANAGE).")
    v.add_argument("object", metavar="<object>")
    v.add_argument("text", metavar="<text>", nargs="?")
    v = add("tag", "Set or unset tags on an object (MANAGE).")
    v.add_argument("object", metavar="<object>")
    v.add_argument("tags", metavar="key[=value]", nargs="*")
    v.add_argument("--unset", metavar="K1,K2", help="tags to remove")
    v = add("move", "Move a view into a namespace, with its grants.")
    v.add_argument("object", metavar="<view>")
    v.add_argument("--namespace", metavar="NS")
    v = add("owner", "Give an object to another role or user (its owner only).")
    v.add_argument("object", metavar="<object>")
    v.add_argument("--role")
    v.add_argument("--user")
    _yes(v, "give it away")

    for name, run, summary in (
        ("grant", _catalog.grant, "Grant privileges on an object to a role or user (MANAGE)."),
        ("revoke", _catalog.revoke, "Revoke privileges on an object from a role or user (MANAGE)."),
    ):
        p = b.add(name, run, summary)
        p.add_argument("privileges", metavar="<privileges>", nargs="?",
                       help="SELECT,SUBSCRIBE,... or ALL")
        p.add_argument("object", metavar="<object>", nargs="?")
        p.add_argument("--role")
        p.add_argument("--user")
        if name == "revoke":
            _yes(p, "revoke them")
    p = b.add("grants", _catalog.grants, "Grants on an object, or to a role or user.")
    p.add_argument("--on", metavar="OBJECT")
    p.add_argument("--role")
    p.add_argument("--user")
    p = b.add("access", _catalog.access, "Why a user may (or may not) do something to an object.")
    add = b.verbs(p, _catalog.access, required=True)
    v = add("why", "Which grant, through which role, namespace or ownership.")
    v.add_argument("user_name", metavar="<user>")
    v.add_argument("object", metavar="<object>")

    # ---------------------------------------------------- row filters and masks (ADR-059 s4)
    p = b.add("policy", _policy.policy, "Row filters and column masks: define, bind, unbind, drop.")
    add = b.verbs(p, _policy.policy)
    v = add("ls", "Row filters and masks you may see, and where each is bound.")
    v.add_argument("--on", metavar="OBJECT", help="only those reaching this stream or view")
    v = add("show", "One policy: its expression, EXCEPT ROLEs, owner and bindings.")
    v.add_argument("name", metavar="<policy>")
    for verb, summary in (
        ("create-filter", "Define a row filter (CREATE on the namespace); bind it to take effect."),
        ("create-mask", "Define a column mask (CREATE on the namespace); bind it to take effect."),
    ):
        v = add(verb, summary)
        v.add_argument("name", metavar="<policy>")
        if verb == "create-mask":
            v.add_argument("--column", metavar="C", help="the column it masks")
        v.add_argument("--as", dest="as_expression", metavar="EXPR",
                       help="the predicate or expression, e.g. \"region = session_attribute('region')\"")
        v.add_argument("--except-role", action="append", metavar="ROLE",
                       help="a role it does not narrow (repeatable)")
        v.add_argument("--comment", help="its description")
    for verb, summary in (
        ("bind", "Bind to a stream or view (MANAGE on it) or a tag (MANAGE on the tenant)."),
        ("unbind", "Unbind from a stream, view or tag."),
    ):
        v = add(verb, summary)
        v.add_argument("name", metavar="<policy>")
        v.add_argument("--on", metavar="OBJECT")
        v.add_argument("--tag", metavar="KEY[=VALUE]")
        if verb == "unbind":
            _yes(v, "unbind it")
    v = add("drop", "Drop a policy (MANAGE; refused while bound).")
    v.add_argument("name", metavar="<policy>")
    _yes(v, "drop it")

    # ------------------------------------------------------------------ alerts (ADR-057)
    p = b.add("alerts", _alerts.alerts, "Alerts: what is firing, and pause, snooze or acknowledge one.")
    add = b.verbs(p, _alerts.alerts)
    add("ls", "The alerts you may see, with how many keys each has firing.")
    add("channels", "The notifier channels the node binds (pravaha.notifiers.*).")
    v = add("show", "One alert: every key's state and its recent notifications (SELECT).")
    v.add_argument("name", metavar="<alert>")
    for verb, summary in (
        ("pause", "Pause: keep following, say nothing until resumed (MODIFY)."),
        ("resume", "Resume, ending a pause or a snooze; what changed is sent (MODIFY)."),
    ):
        v = add(verb, summary)
        v.add_argument("name", metavar="<alert>")
    v = add("snooze", "Say nothing for a while; what changed is sent when it ends (MODIFY).")
    v.add_argument("name", metavar="<alert>")
    v.add_argument("duration", metavar="<duration>", nargs="?", help="30m, 2h or PT2H")
    v = add("ack", "Acknowledge firing keys, which stops their reminders (MODIFY).")
    v.add_argument("name", metavar="<alert>")
    v.add_argument("--key", metavar="'COL=V, COL=V'", help="one key as `alerts show` prints it; default all")

    p = b.add("alert", _alerts.alert, "Create or drop an alert (a CREATE ALERT statement, over Flight).")
    add = b.verbs(p, _alerts.alert, required=True)
    v = add("create", "Notify when a key's row enters a view (fired) and when it leaves (cleared).")
    v.add_argument("name", metavar="<alert>")
    v.add_argument("--on", metavar="VIEW", help="the continuous query's view it watches")
    v.add_argument("--where", metavar="COND", help="column op literal [AND ...], over the view's columns")
    v.add_argument("--notify", metavar="CH1,CH2", help="channels bound under pravaha.notifiers.<name>")
    v.add_argument("--severity", help="info, warning (default) or critical")
    v.add_argument("--fire-after", dest="fire_after", metavar="DUR", help="in the condition this long first")
    v.add_argument("--clear-after", dest="clear_after", metavar="DUR", help="out of it this long first")
    v.add_argument("--dedupe", metavar="DUR", help="least time between two notifications about one key")
    v.add_argument("--resend-every", dest="resend_every", metavar="DUR", help="remind until acknowledged")
    v.add_argument("--include", metavar="COL,COL", help="the columns a notification carries")
    v.add_argument("--print-sql", dest="print_sql", action="store_true", help="print the statement, send nothing")
    v = add("drop", "Drop an alert, its state and its history (MANAGE).")
    v.add_argument("name", metavar="<alert>")
    v.add_argument("--if-exists", dest="if_exists", action="store_true")
    v.add_argument("--print-sql", dest="print_sql", action="store_true", help=argparse.SUPPRESS)
    _yes(v, "drop it")

    for made in b.made:
        _global_options(made, suppress=True)
    return parser


def _assist_options(parser: argparse.ArgumentParser, profile: str = "explain") -> None:
    parser.add_argument("--profile", help=f"the profile whose chain answers (default: {profile}, "
                                          "else the configured default)")
    parser.add_argument("--model", metavar="ID", help="ask only this configured model")


def _offline_run(ctx: Context) -> int:
    raise UsageError(
        "`run` executes SQL over a file with no server; it is `pravaha-engine run` now, the "
        "offline Java tool (bin/pravaha-engine). Its flags are unchanged."
    )


# ---------------------------------------------------------------------------------- failures


def _code_of(exc: PravahaError) -> str:
    engine = getattr(exc, "engine_code", None)
    return str(engine) if engine else f"PRV-{exc.code}"


def _words_of(exc: BaseException) -> str:
    message = getattr(exc, "message", None)
    if isinstance(message, str) and message:
        return message
    text = str(exc)
    if isinstance(exc, PravahaError) and text.startswith(f"PRV-{exc.code}  "):
        return text[len(f"PRV-{exc.code}  "):]
    return text


def classify(exc: BaseException) -> int:
    """The exit code a failure means."""
    if isinstance(exc, UsageError):
        return EXIT_USAGE
    if isinstance(exc, AssistConfigError):
        # The assistant's configuration is wrong: no model was asked.
        return EXIT_USAGE
    if isinstance(exc, AssistError):
        # A model failed, or a budget refused the request: the normalised error is on stderr.
        return EXIT_REFUSED
    if isinstance(exc, (InvalidOptionsError, InvalidTlsOptionsError, MalformedEndpointError,
                        InvalidDocsBaseUrlError, MalformedTextError)):
        return EXIT_USAGE
    if isinstance(exc, ApiError):
        return EXIT_UNREACHABLE if exc.status == 0 else EXIT_REFUSED
    if isinstance(exc, PravahaError):
        # ConnectError is 1040: nothing answered. Everything else the engine said.
        return EXIT_UNREACHABLE if exc.code == 1040 else EXIT_REFUSED
    if isinstance(exc, ValueError):
        # Refused by the SDK before anything was sent: a wrong number of parameters, say.
        return EXIT_USAGE
    return EXIT_REFUSED


def _report(exc: BaseException, out: Output, settings: Optional[Settings]) -> int:
    code = classify(exc)
    if isinstance(exc, PravahaError):
        prv = _code_of(exc)
        words = _words_of(exc)
        if code == EXIT_UNREACHABLE and settings is not None:
            http = isinstance(exc, ApiError)
            where = settings.http if http else settings.url
            words += (
                f" (talking to {where} -- pass {'--http' if http else '--url'} if that is not "
                f"the node you meant)"
            )
    elif isinstance(exc, AssistError):
        prv = None
        words = f"{exc.kind}: {exc}"
        retry = getattr(exc, "retry_after", None)
        if retry is not None:
            words += f" (retry after {retry:g} s)"
    else:
        prv = None
        words = str(exc) if isinstance(exc, (UsageError, ValueError)) else (
            f"{type(exc).__name__}: {exc}"
        )
    if out.json_mode:
        body: dict[str, Any] = {"code": prv, "message": words, "exit": code}
        if isinstance(exc, ApiError):
            body["status"] = exc.status
        if isinstance(exc, AssistError):
            body.update(exc.to_dict())
            body["message"] = words
        print(to_json({"error": body}, indent=None), file=out.err)
    else:
        line = words if prv is None or words.startswith(prv) else f"{prv}  {words}"
        out.warn(line)
        if prv is not None and code == EXIT_REFUSED:
            out.note("  " + help_line(prv))
    if os.environ.get("PRAVAHA_CLI_TRACE"):
        traceback.print_exception(type(exc), exc, exc.__traceback__, file=out.err)
    return code


# ---------------------------------------------------------------------------------- main


def main(
    argv: Optional[Sequence[str]] = None,
    *,
    stdout: Optional[TextIO] = None,
    stderr: Optional[TextIO] = None,
    environ: Optional[dict[str, str]] = None,
) -> int:
    """Runs one ``pravaha`` command and returns its exit code, rather than exiting, so it is
    testable. ``python -m pravaha.cli`` and the ``pravaha`` script call it."""
    arguments = list(sys.argv[1:] if argv is None else argv)
    raw_json = "--json" in arguments
    out = Output(json_mode=raw_json, out=stdout, err=stderr,
                 color=False if "--no-color" in arguments else None)
    parser = build_parser()
    if not arguments:
        parser.print_usage(out.err)
        out.warn("a command is required; `pravaha --help` lists them")
        return EXIT_USAGE
    settings: Optional[Settings] = None
    ctx: Optional[Context] = None
    try:
        try:
            with contextlib.redirect_stdout(out.out):
                args, unknown = parser.parse_known_args(arguments)
        except SystemExit as done:  # --help prints and exits 0
            return int(done.code or 0)
        if unknown and getattr(args, "command", None) != "run":
            raise UsageError(
                f"unrecognized arguments: {' '.join(unknown)}\n"
                f"(pravaha {getattr(args, 'command', None) or ''} --help lists the flags)"
            )
        if getattr(args, "version", False) and not getattr(args, "command", None):
            out.line(f"pravaha {pravaha.__version__}")
            return EXIT_OK
        if not getattr(args, "command", None):
            raise UsageError("a command is required; `pravaha --help` lists them")
        if getattr(args, "query_name", None) is None and getattr(args, "name", None):
            args.query_name = args.name
        out.json_mode = bool(getattr(args, "json", False))
        configure_docs_base_from_environment()
        settings = Settings.resolve(args, environ)
        ctx = Context(args, out, settings)
        return int(args.run(ctx))
    except KeyboardInterrupt:
        return 130
    except ImportError as exc:
        out.warn(
            f"this command speaks Arrow Flight and needs pyarrow: {exc}. Install it with\n"
            '    pip install "pravaha[flight]"'
        )
        return EXIT_USAGE
    except BrokenPipeError:  # pragma: no cover - `pravaha ... | head`
        return EXIT_OK
    except Exception as exc:
        return _report(exc, out, settings)
    finally:
        if ctx is not None:
            ctx.close()


def run() -> None:
    """The console-script entry point."""
    sys.exit(main())
