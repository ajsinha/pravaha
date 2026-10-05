"""The ``pravaha`` commands answered by the engine's HTTP API: the node, the catalogue, one
query in detail, plans, lanes, the audit trail, tenants, permissions and metrics.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Every call goes through :class:`pravaha.api.EngineApi`, so none of these needs pyarrow or the
Flight port -- only ``--http``.
"""

from __future__ import annotations

from typing import Any, Optional

import pravaha
from pravaha.cli._common import EXIT_OK, EXIT_REFUSED, Context, UsageError
from pravaha.errors import PravahaError


def _fields_text(fields: Any) -> str:
    return ", ".join(
        f"{f.get('name')} {f.get('type')}{'' if not f.get('nullable') else '?'}"
        for f in fields or []
        if isinstance(f, dict)
    )


def _keys_text(keys: Any) -> str:
    return ",".join(str(k.get("name", k.get("ordinal"))) for k in keys or [] if isinstance(k, dict))


# ---------------------------------------------------------------------------------- the node


def status(ctx: Context) -> int:
    node = ctx.api.status()
    if ctx.out.json_mode:
        ctx.out.json(node)
        return EXIT_OK
    ctx.out.fields(
        [
            ("instance", node.get("instanceId")),
            ("version", node.get("version")),
            ("engine", node.get("engineState")),
            ("uptime", f"{node.get('uptimeSeconds', 0)} s"),
            ("queries", node.get("registeredQueries")),
            ("streams", node.get("streams")),
            ("stopped feeds", node.get("stoppedFeeds")),
            ("flight", node.get("flight")),
        ]
    )
    plugins = node.get("plugins") or []
    if plugins:
        ctx.out.line()
        ctx.out.table(plugins, ["name", "version", "health", "detail"])
    return EXIT_OK


def health(ctx: Context) -> int:
    answer = ctx.api.health()
    state = str(answer.get("status", "UNKNOWN"))
    if ctx.out.json_mode:
        ctx.out.json(answer)
    else:
        ctx.out.line(state)
        components = answer.get("components") or {}
        if isinstance(components, dict) and components:
            ctx.out.table(
                [{"component": k, "status": (v or {}).get("status")} for k, v in components.items()],
                ["component", "status"],
            )
    # UP is healthy; DEGRADED still serves every view (a feed stopped). Anything else is not.
    return EXIT_OK if state in ("UP", "DEGRADED") else EXIT_REFUSED


def metrics(ctx: Context) -> int:
    text = ctx.api.metrics_text()
    prefix = ctx.arg("grep")
    if prefix:
        text = "\n".join(line for line in text.splitlines() if prefix in line)
    if ctx.out.json_mode:
        ctx.out.json({"prometheus": text})
    else:
        ctx.out.out.write(text if text.endswith("\n") or not text else text + "\n")
    return EXIT_OK


def version(ctx: Context) -> int:
    client_version = pravaha.__version__
    server: Optional[str] = None
    problem: Optional[str] = None
    if not ctx.arg("client"):
        try:
            server = str(ctx.api.status().get("version") or "") or None
        except PravahaError as exc:
            # A version is worth printing even when the node is not: say why, and carry on.
            problem = str(getattr(exc, "message", "") or exc)
    if ctx.out.json_mode:
        ctx.out.json({"cli": client_version, "server": server, "serverError": problem})
        return EXIT_OK
    ctx.out.line(f"pravaha {client_version}")
    if server:
        ctx.out.line(f"server  {server}  ({ctx.settings.http})")
    elif problem:
        ctx.out.note(f"server: not asked successfully -- {problem}")
    return EXIT_OK


def plugins(ctx: Context) -> int:
    listed = ctx.api.plugins()
    rows = [
        {
            **p,
            "health": (p.get("health") or {}).get("state"),
            "bindings": [f"{b.get('kind')}:{b.get('name')}" for b in p.get("bindings") or []],
        }
        for p in listed
    ]
    ctx.out.result(
        listed,
        rows,
        ["name", "version", "kinds", "compatible", "loaded", "health", "bindings"],
        empty="this node can load no plugins",
    )
    return EXIT_OK


def sinks(ctx: Context) -> int:
    listed = ctx.api.sinks()
    rows = [
        {**s, "problem": (s.get("problem") or {}).get("code")} for s in listed
    ]
    ctx.out.result(
        listed,
        rows,
        [
            "name",
            "plugin",
            ("emitModes", "EMIT"),
            ("acceptsRetractions", "RETRACTIONS"),
            "guarantee",
            "writers",
            "problem",
        ],
        empty="this node binds no sink you may see",
    )
    return EXIT_OK


# ---------------------------------------------------------------------------------- catalogue


def streams(ctx: Context) -> int:
    verb = ctx.args.verb or "list"
    if verb == "list":
        listed = ctx.api.streams()
        rows = [{**s, "fields": _fields_text(s.get("fields"))} for s in listed]
        ctx.out.result(
            listed,
            rows,
            ["name", "version", ("eventTime", "EVENT TIME"), ("outOfOrderness", "LATENESS"),
             "source", "fields"],
            empty="no stream is declared that you may read",
        )
        return EXIT_OK
    name = ctx.require("stream_name", "the stream's name")
    if verb == "declare":
        declared = ctx.api.declare_stream(
            name,
            ctx.require("schema"),
            event_time=ctx.arg("event_time"),
            out_of_orderness=ctx.arg("out_of_orderness"),
        )
        if ctx.out.json_mode:
            ctx.out.json(declared)
        else:
            ctx.out.line(ctx.out.good("declared ") + str(declared.get("name", name)))
        return EXIT_OK
    one = ctx.api.stream(name)
    if ctx.out.json_mode:
        ctx.out.json(one)
        return EXIT_OK
    ctx.out.fields(
        [
            ("name", one.get("name")),
            ("version", one.get("version")),
            ("event time", one.get("eventTime")),
            ("out of order", one.get("outOfOrderness")),
            ("lateness", one.get("allowedLateness")),
            ("source", one.get("source")),
        ]
    )
    ctx.out.line()
    ctx.out.table(one.get("fields") or [], ["ordinal", "name", "type", "nullable"])
    return EXIT_OK


def views(ctx: Context) -> int:
    verb = ctx.args.verb or "list"
    if verb == "list":
        # There is no view listing of its own: every view is a registered query's, so the
        # query descriptions are the listing, and visibility is exactly theirs.
        listed = ctx.api.describe_queries()
        rows = [
            {
                "name": q.get("name"),
                "key": _keys_text(q.get("keyColumns")),
                "retention": q.get("retention"),
                "sink": (q.get("sink") or {}).get("name"),
                "fingerprint": q.get("fingerprint"),
                "state": q.get("state"),
            }
            for q in listed
        ]
        ctx.out.result(
            rows,
            rows,
            ["name", "state", "key", "retention", "sink", "fingerprint"],
            empty="no view is visible to you: no continuous query you may read is registered",
        )
        return EXIT_OK
    view = ctx.api.describe_view(ctx.require("view_name", "the view's name"))
    if ctx.out.json_mode:
        ctx.out.json(view)
        return EXIT_OK
    ctx.out.fields(
        [
            ("name", view.get("name")),
            ("key", _keys_text(view.get("keyColumns"))),
            ("retention", view.get("retention")),
            ("sink", view.get("sink")),
            ("fingerprint", view.get("fingerprint")),
        ]
    )
    ctx.out.line()
    ctx.out.table(view.get("schema") or [], ["ordinal", "name", "type", "nullable"])
    return EXIT_OK


# ---------------------------------------------------------------------------------- one query


def describe(ctx: Context) -> int:
    query = ctx.api.describe_query(ctx.require("query_name", "the query's name"))
    if ctx.out.json_mode:
        ctx.out.json(query)
        return EXIT_OK
    sink = query.get("sink") or {}
    feed = query.get("feed") or {}
    failure = query.get("failure") or {}
    lane = query.get("lane")
    if lane == "shared" and query.get("sharedLane") is not None:
        lane = f"shared #{query.get('sharedLane')}"
    rows_in = "-" if query.get("countsWithheld") else query.get("rowsIn")
    sink_text: Any = None
    if sink:
        sink_text = f"{sink.get('name')} ({'attached' if sink.get('attached') else 'detached'}, " \
            f"{sink.get('rowsWritten', 0)} rows written)"
    ctx.out.fields(
        [
            ("name", query.get("name")),
            ("state", query.get("state")),
            ("owner", query.get("owner")),
            ("fingerprint", query.get("fingerprint")),
            ("lane", lane),
            ("key", _keys_text(query.get("keyColumns"))),
            ("retention", query.get("retention")),
            ("sink", sink_text),
            ("rows in", rows_in),
            ("registered", query.get("registeredAt")),
            ("shared with", query.get("sharedWith")),
            ("reads", query.get("reads")),
            ("feed", feed.get("state")),
            ("execution", query.get("execution")),
        ]
    )
    ctx.out.line(ctx.out.bold("sql"))
    ctx.out.line(str(query.get("sql") or ""))
    if failure:
        ctx.out.warn(f"{failure.get('code')}  {failure.get('message')}")
    for source in feed.get("sources") or []:
        problem = source.get("failure") or {}
        if problem:
            ctx.out.warn(
                f"{source.get('stream')}#{source.get('partition')} {source.get('state')}: "
                f"{problem.get('code')}  {problem.get('message')}"
            )
    if sink.get("failure"):
        problem = sink["failure"]
        ctx.out.warn(f"sink {sink.get('name')}: {problem.get('code')}  {problem.get('message')}")
    return EXIT_OK


def plan(ctx: Context) -> int:
    graph = ctx.api.query_plan(ctx.require("query_name", "the query's name"))
    if ctx.out.json_mode:
        ctx.out.json(graph)
        return EXIT_OK
    measured = graph.get("operatorMetrics") or {}
    rows = []
    for node in graph.get("nodes") or []:
        numbers = measured.get(node.get("id")) or {}
        rows.append(
            {
                **node,
                "rowsIn": numbers.get("rowsIn"),
                "rowsOut": numbers.get("rowsOut"),
                "stateBytes": numbers.get("stateBytes"),
                "share": (
                    f"{100 * float(numbers['selfTimeShare']):.0f}%"
                    if numbers.get("selfTimeShare") is not None
                    else None
                ),
            }
        )
    ctx.out.table(
        rows,
        [
            "id",
            "operator",
            "stateful",
            ("rowsIn", "ROWS IN"),
            ("rowsOut", "ROWS OUT"),
            ("stateBytes", "STATE BYTES"),
            ("share", "TIME"),
            "detail",
        ],
    )
    edges = graph.get("edges") or []
    if edges:
        ctx.out.line("edges  " + "  ".join(f"{e.get('from')}->{e.get('to')}" for e in edges))
    if graph.get("bottleneck"):
        ctx.out.line(f"bottleneck  {graph['bottleneck']}")
    if graph.get("metricsNote"):
        ctx.out.note(str(graph["metricsNote"]))
    return EXIT_OK


# ---------------------------------------------------------------------------------- validate / explain


def _refuse_offline(ctx: Context, command: str) -> None:
    if ctx.arg("schema") is not None:
        raise UsageError(
            f"--schema plans against a stream you describe, with no server: that is "
            f"`pravaha-engine {command}`, the offline Java tool. `pravaha {command}` plans against "
            "a running node, which already knows its streams -- drop --schema to use it"
        )


def validate(ctx: Context) -> int:
    _refuse_offline(ctx, "validate")
    answer = ctx.api.validate(ctx.sql())
    if ctx.out.json_mode:
        ctx.out.json(answer)
        return EXIT_OK if answer.get("valid") else EXIT_REFUSED
    if answer.get("valid"):
        ctx.out.line(ctx.out.good("valid") + f"  {answer.get('elapsedMicros', 0)} us")
        ctx.out.line(f"  output: [{_fields_text(answer.get('outputFields'))}]")
        return EXIT_OK
    for diagnostic in answer.get("diagnostics") or []:
        where = diagnostic.get("range") or {}
        at = f" (line {where.get('startLine')}, column {where.get('startColumn')})" if where else ""
        code, message = str(diagnostic.get("code") or ""), str(diagnostic.get("message") or "")
        # The engine's message may already begin with its code; it is said once.
        ctx.out.warn((message if message.startswith(code) else f"{code}  {message}") + at)
    return EXIT_REFUSED


def explain(ctx: Context) -> int:
    _refuse_offline(ctx, "explain")
    level = ctx.arg("level", "physical")
    if level not in ("physical", "logical", "codegen"):
        raise UsageError(f"--level must be logical, physical or codegen; got '{level}'")
    answer = ctx.api.explain(ctx.sql(), level, graph=bool(ctx.arg("graph")))
    if ctx.out.json_mode:
        ctx.out.json(answer)
    else:
        ctx.out.line(str(answer.get("plan") or ""))
    return EXIT_OK


# ---------------------------------------------------------------------------------- lanes


def lanes(ctx: Context) -> int:
    verb = ctx.args.verb or "list"
    if verb == "list":
        summary = ctx.api.lanes()
        placed = ctx.api.describe_queries()
        if ctx.out.json_mode:
            ctx.out.json(
                {
                    **summary,
                    "queries": [
                        {k: q.get(k) for k in ("name", "state", "lane", "sharedLane")}
                        for q in placed
                    ],
                }
            )
            return EXIT_OK
        auto = summary.get("autoFrom")
        ctx.out.line(
            f"mode {summary.get('mode')}"
            + ("" if auto is None else f", a lane each until {auto}")
            + f", at most {summary.get('maxQueriesPerLane')} per shared lane; "
            + f"{summary.get('hosted')} hosted, {summary.get('ownLaneQueries')} on lanes of their "
            + f"own ({summary.get('dedicatedQueries')} dedicated)"
        )
        ctx.out.table(placed, ["name", "state", "lane", ("sharedLane", "SHARED LANE")])
        return EXIT_OK
    # rebalance
    if ctx.arg("rebalance_what") == "status":
        plan_now = ctx.api.lane_rebalance()
        preview = False
    else:
        preview = not ctx.confirmed()
        plan_now = ctx.api.rebalance_lanes(dry_run=preview)
    if ctx.out.json_mode:
        ctx.out.json({**plan_now, "dryRun": preview})
        return EXIT_OK
    ctx.out.line(
        f"mode {plan_now.get('mode')}, room for {plan_now.get('room')} more on lanes of their own"
        + ("; running" if plan_now.get("running") else "")
    )
    moves = plan_now.get("moves") or []
    if not moves:
        ctx.out.line("nothing to move")
    else:
        ctx.out.table(
            moves, ["name", ("fromSharedLane", "FROM SHARED LANE"), "status", "detail"]
        )
    if preview and moves:
        ctx.out.note(
            "a plan only: nothing was moved. Run `pravaha lanes rebalance --yes` to move these, "
            "one at a time"
        )
    return EXIT_OK


# ---------------------------------------------------------------------------------- governance


def audit(ctx: Context) -> int:
    page = ctx.api.audit(
        since=ctx.arg("since"),
        until=ctx.arg("until"),
        principal=ctx.arg("principal"),
        view=ctx.arg("view"),
        action=ctx.arg("action"),
        decision=ctx.arg("decision"),
        limit=ctx.arg("limit"),
        cursor=ctx.arg("cursor"),
    )
    if ctx.out.json_mode:
        ctx.out.json(page)
        return EXIT_OK
    if page.get("recording") is False:
        ctx.out.note(str(page.get("note") or "this node records no audit trail (audit: none)"))
        return EXIT_OK
    events = page.get("events") or []
    if events:
        ctx.out.table(
            events,
            ["sequence", "at", "principal", "action", "target", "decision", "reason"],
        )
    else:
        ctx.out.note("no recorded decision matches")
    ctx.out.note(
        f"{page.get('retained', 0)} retained of {page.get('capacity', 0)}, "
        f"{page.get('evicted', 0)} evicted"
        + (f"; older: --cursor {page['nextCursor']}" if page.get("nextCursor") else "")
    )
    return EXIT_OK


def tenants(ctx: Context) -> int:
    page = ctx.api.tenants()
    if ctx.out.json_mode:
        ctx.out.json(page)
        return EXIT_OK
    defaults = page.get("defaults") or {}

    def limit(value: Any) -> str:
        return "no limit" if value is None else str(value)

    ctx.out.line(
        f"scope {page.get('scope')}; defaults: {limit(defaults.get('maxQueries'))} queries, "
        f"{limit(defaults.get('maxStateKeys'))} state keys"
    )
    rows = [
        {
            **t,
            "maxQueries": limit((t.get("limits") or {}).get("maxQueries")),
            "maxStateKeys": limit((t.get("limits") or {}).get("maxStateKeys")),
        }
        for t in page.get("tenants") or []
    ]
    ctx.out.table(
        rows,
        [
            "tenant",
            "queries",
            ("maxQueries", "MAX QUERIES"),
            "computations",
            ("stateKeys", "STATE KEYS"),
            ("maxStateKeys", "MAX KEYS"),
            ("queryRefusals", "QUERY REFUSALS"),
            ("stateRefusals", "STATE REFUSALS"),
        ],
    )
    return EXIT_OK


def permissions(ctx: Context) -> int:
    mine = ctx.api.permissions()
    if ctx.out.json_mode:
        ctx.out.json(mine)
        return EXIT_OK

    def decision(value: Any) -> str:
        value = value or {}
        if value.get("allowed"):
            return "allowed"
        return "refused" + (f" ({value.get('reason')})" if value.get("reason") else "")

    ctx.out.fields(
        [
            ("principal", mine.get("principal")),
            ("tenant", mine.get("tenant")),
            ("roles", mine.get("roles")),
            ("policy", mine.get("policy")),
            ("register", decision(mine.get("register"))),
            ("read audit", decision(mine.get("readAudit"))),
        ]
    )
    for kind in ("views", "streams"):
        objects = mine.get(kind) or []
        if objects:
            ctx.out.line()
            ctx.out.table(
                [
                    {
                        "name": o.get("name"),
                        "read": o.get("read"),
                        "administer": decision(o.get("administer")),
                    }
                    for o in objects
                ],
                [("name", kind[:-1].upper()), "read", "administer"],
            )
    return EXIT_OK

