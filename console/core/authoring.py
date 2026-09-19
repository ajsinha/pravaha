"""What the SQL Workbench knows about Pravaha SQL: plans, diagnostics, and their fixes.

Kept on the server, not in the browser, for the same reason the error mapping in
``routes/base.py`` is: a judgement written once cannot drift between two screens. The
workbench, the onboarding flow and a query's own page all show a plan and all show a
refusal, and they must show them the same way.

Everything here is a pure function of what the engine's *public* API returned -- the
``/validate`` diagnostics with their positions and the ``/explain`` graph -- plus the catalog. Nothing is
inferred that the engine did not say. A fix is offered only where the change it makes is
certain to be the one the diagnostic asks for; otherwise the diagnostic links to its help
and stops there.
"""
from __future__ import annotations

import difflib
import re
from typing import Any

# --------------------------------------------------------------------------- plan graph

#: Operator families, for the node's colour and icon. The operator kind is the engine's own;
#: this only groups it, and anything unrecognised is shown as itself in the neutral family.
FAMILIES = {
    "scan": "source", "source": "source", "lookup": "source",
    "filter": "filter", "project": "project", "calc": "project", "compute": "project",
    "aggregate": "aggregate", "windowaggregate": "aggregate", "windowedaggregate": "aggregate",
    "windowassign": "aggregate", "window": "aggregate",
    "tumble": "aggregate", "hop": "aggregate", "groupby": "aggregate", "distinct": "aggregate",
    "join": "join", "hashjoin": "join", "intervaljoin": "join", "lookupjoin": "join",
    "temporaljoin": "join", "union": "join",
    "sort": "other", "limit": "other", "values": "source", "sink": "sink",
}


def _family(op: str) -> str:
    lowered = op.lower()
    if lowered in FAMILIES:
        return FAMILIES[lowered]
    for key, family in FAMILIES.items():
        if key in lowered:
            return family
    return "other"


def _short(operator: str, label: str) -> str:
    """The part of the engine's label after its operator name, for the node's second line.

    Display only: the operator kind comes from the engine's own field, never from here.
    """
    if label.startswith(operator) and len(label) > len(operator) + 1 and label[len(operator)] in "([":
        inner = label[len(operator) + 1:]
        return inner[:-1] if inner and inner[-1] in ")]" else inner
    return label


def plan_graph(graph: dict | None) -> dict[str, Any]:
    """The engine's structured plan as the shape the plan-graph island draws.

    The engine answers ``{nodes: [{id, operator, detail, stateful, fields}], edges: [{from,
    to}]}``, with edges running the way rows flow -- from an input to its consumer -- and
    ``n0`` the root. This used to be rebuilt by counting the leading spaces of the text plan,
    a parser of a rendering; now it is a renaming of the engine's own structure, plus each
    node's depth from the root (for the text-free list view) and its colour family.
    """
    graph = graph or {}
    raw_nodes = list(graph.get("nodes") or [])
    raw_edges = list(graph.get("edges") or [])
    consumer = {str(e.get("from")): str(e.get("to")) for e in raw_edges}
    depth: dict[str, int] = {}

    def depth_of(node_id: str) -> int:
        seen = 0
        current = node_id
        while current in consumer and seen <= len(raw_nodes):
            current = consumer[current]
            seen += 1
        return seen

    nodes = []
    for node in raw_nodes:
        node_id = str(node.get("id"))
        op = str(node.get("operator") or "")
        label = str(node.get("detail") or op)
        depth[node_id] = depth_of(node_id)
        nodes.append({"id": node_id, "op": op, "detail": _short(op, label), "label": label,
                      "depth": depth[node_id], "family": _family(op),
                      "stateful": bool(node.get("stateful")),
                      "fields": list(node.get("fields") or [])})
    edges = [{"id": f"e{i}", "source": str(e.get("from")), "target": str(e.get("to"))}
             for i, e in enumerate(raw_edges)]
    return {"nodes": nodes, "edges": edges}


# --------------------------------------------------------------------------- diagnostics

_WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def locate(sql: str, diagnostic_range: dict | None) -> dict[str, int]:
    """Where in the editor a diagnostic belongs.

    The engine reads the position from the parser's and validator's own fields and sends it
    as ``range`` -- 1-based, end column inclusive. Monaco's end column is exclusive, so one is
    added. Without a range the engine does not know where (a refusal about the plan rather
    than the text), and the whole first line is the honest answer: the query is wrong
    somewhere. Nothing is parsed out of the message's English any more.
    """
    lines = sql.splitlines() or [""]
    if isinstance(diagnostic_range, dict) and diagnostic_range.get("startLine"):
        try:
            return {"startLine": int(diagnostic_range["startLine"]),
                    "startColumn": int(diagnostic_range["startColumn"]),
                    "endLine": int(diagnostic_range["endLine"]),
                    "endColumn": int(diagnostic_range["endColumn"]) + 1}
        except (KeyError, TypeError, ValueError):
            pass
    first = lines[0] if lines else ""
    return {"startLine": 1, "startColumn": 1, "endLine": 1, "endColumn": max(2, len(first) + 1)}


def _text_at(sql: str, where: dict[str, int] | None) -> str | None:
    """The single-line text an (end-exclusive) editor range covers, if it is one word."""
    if not where or where.get("startLine") != where.get("endLine"):
        return None
    lines = sql.splitlines()
    index = where["startLine"] - 1
    if not 0 <= index < len(lines):
        return None
    text = lines[index][where["startColumn"] - 1: where["endColumn"] - 1]
    return text if _WORD.fullmatch(text or "") else None


def _edit(range_: dict[str, int], text: str) -> dict[str, Any]:
    return {"range": range_, "text": text}


def fixes_for(code: str, sql: str, streams: list[dict],
              where: dict[str, int] | None = None) -> list[dict[str, Any]]:
    """Fixes the console can apply with certainty, for one diagnostic.

    Each is ``{"title", "edits": [...]}`` (text edits the editor applies) or ``{"title",
    "action"}`` (a workbench action: clear the sink, open a template). A replacement is
    offered only where the engine said exactly which text is wrong -- ``where`` is its range
    -- and a declared name is close enough to be a typo of it. A guessed fix that compiles
    and means something else is worse than none.
    """
    out: list[dict[str, Any]] = []
    names = [s.get("name", "") for s in streams if s.get("name")]
    word = _text_at(sql, where)

    if code == "PRV-2003":
        if word and word.lower() not in {n.lower() for n in names}:
            for candidate in difflib.get_close_matches(word, names, n=2, cutoff=0.6):
                out.append({"title": f"Replace '{word}' with '{candidate}'",
                            "edits": [_edit(where, candidate)]})
        out.append({"title": "Browse the declared streams", "action": "open-catalog"})

    elif code == "PRV-2002":
        columns = sorted({f.get("name", "") for s in streams for f in s.get("fields", [])
                          if f.get("name")})
        if word and word.lower() not in {c.lower() for c in columns}:
            for candidate in difflib.get_close_matches(word, columns, n=2, cutoff=0.6):
                out.append({"title": f"Replace '{word}' with '{candidate}'",
                            "edits": [_edit(where, candidate)]})

    elif code == "PRV-2050":
        out.append({"title": "Start from a tumbling-window template", "action": "template:tumble"})

    elif code == "PRV-2041":
        out.append({"title": "Register without the sink", "action": "clear-sink"})

    elif code in {"PRV-2060", "PRV-2061"}:
        out.append({"title": "Fill in the parameters", "action": "focus-params"})

    return out


def enrich(result: dict, sql: str, streams: list[dict]) -> dict[str, Any]:
    """The engine's /validate answer, made into what an editor needs.

    Adds each diagnostic's editor range (from the engine's own position), its console help
    page (the engine's own ``helpUrl`` points at a host that does not exist -- see
    TROUBLESHOOTING.md -- and an air-gapped browser could not reach it if it did), and the
    fixes that apply.
    """
    diagnostics = []
    for diagnostic in result.get("diagnostics") or []:
        code = str(diagnostic.get("code") or "")
        message = str(diagnostic.get("message") or "")
        engine_range = diagnostic.get("range")
        where = locate(sql, engine_range)
        diagnostics.append({
            "code": code,
            "message": message,
            "severity": str(diagnostic.get("severity") or "error"),
            "help": f"/help/codes/{code}" if code.startswith("PRV-") else "/help/troubleshooting",
            "range": where,
            # Said, so the editor can tell "the engine placed this" from "somewhere in here".
            "positioned": isinstance(engine_range, dict) and bool(engine_range.get("startLine")),
            "fixes": fixes_for(code, sql, streams, where if engine_range else None),
        })
    return {
        "valid": bool(result.get("valid")),
        "diagnostics": diagnostics,
        "output_fields": list(result.get("outputFields") or []),
        "elapsed_us": result.get("elapsedMicros"),
    }


# --------------------------------------------------------------------------- language

#: Keywords the Monaco tokenizer highlights and completion offers. Pravaha's dialect is
#: Calcite's, narrowed to what CONTINUOUS_QUERIES.md says runs.
KEYWORDS = [
    "SELECT", "STREAM", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "AS", "AND",
    "OR", "NOT", "IN", "BETWEEN", "IS", "NULL", "LIKE", "CASE", "WHEN", "THEN", "ELSE",
    "END", "JOIN", "INNER", "LEFT", "ON", "WITH", "TABLE", "DESCRIPTOR", "INTERVAL",
    "SECOND", "MINUTE", "HOUR", "DAY", "CAST", "DISTINCT", "TRUE", "FALSE", "FOR",
    "SYSTEM_TIME", "OF", "UNION", "ALL", "LIMIT",
]

#: Functions with the signature completion shows. Only what the SQL reference marks as
#: running; offering one the planner refuses would be completion that teaches a refusal.
FUNCTIONS = [
    {"name": "TUMBLE", "signature": "TUMBLE(TABLE stream, DESCRIPTOR(event_time), INTERVAL 'n' UNIT)",
     "doc": "Fixed, non-overlapping windows. The descriptor must name the stream's declared event time.",
     "insert": "TUMBLE(TABLE ${1:stream}, DESCRIPTOR(${2:event_time}), INTERVAL '${3:1}' ${4:MINUTE})"},
    {"name": "HOP", "signature": "HOP(TABLE stream, DESCRIPTOR(event_time), INTERVAL slide, INTERVAL size)",
     "doc": "Sliding windows: each row belongs to size/slide windows.",
     "insert": "HOP(TABLE ${1:stream}, DESCRIPTOR(${2:event_time}), INTERVAL '${3:10}' SECOND, INTERVAL '${4:60}' SECOND)"},
    {"name": "TUMBLE_END", "signature": "TUMBLE_END(event_time, INTERVAL 'n' UNIT)",
     "doc": "The end of the tumbling window a row falls in.", "insert": "TUMBLE_END(${1:event_time}, INTERVAL '${2:1}' ${3:MINUTE})"},
    {"name": "COUNT", "signature": "COUNT(*) | COUNT(x) | COUNT(DISTINCT x)",
     "doc": "Windowed, or global COUNT(*). COUNT(DISTINCT) over an unwindowed stream is refused (PRV-2050).",
     "insert": "COUNT(${1:*})"},
    {"name": "SUM", "signature": "SUM(integer_expr)", "doc": "Over integer columns. Floating point is refused (PRV-2020).",
     "insert": "SUM(${1:amount})"},
    {"name": "AVG", "signature": "AVG(integer_expr)", "doc": "Over integer columns.", "insert": "AVG(${1:amount})"},
    {"name": "MIN", "signature": "MIN(expr)", "doc": "Smallest value in the group.", "insert": "MIN(${1:x})"},
    {"name": "MAX", "signature": "MAX(expr)", "doc": "Largest value in the group.", "insert": "MAX(${1:x})"},
    {"name": "ABS", "signature": "ABS(x)", "doc": "Absolute value.", "insert": "ABS(${1:x})"},
    {"name": "FLOOR", "signature": "FLOOR(x)", "doc": "One argument.", "insert": "FLOOR(${1:x})"},
    {"name": "CEIL", "signature": "CEIL(x)", "doc": "One argument.", "insert": "CEIL(${1:x})"},
    {"name": "ROUND", "signature": "ROUND(x)", "doc": "One argument; ROUND(x, 2) is refused.", "insert": "ROUND(${1:x})"},
    {"name": "MOD", "signature": "MOD(a, b)", "doc": "Remainder, sign of the dividend.", "insert": "MOD(${1:a}, ${2:b})"},
    {"name": "UPPER", "signature": "UPPER(s)", "doc": "Root locale.", "insert": "UPPER(${1:s})"},
    {"name": "LOWER", "signature": "LOWER(s)", "doc": "Root locale.", "insert": "LOWER(${1:s})"},
    {"name": "TRIM", "signature": "TRIM(s)", "doc": "Spaces from both ends only.", "insert": "TRIM(${1:s})"},
    {"name": "SUBSTRING", "signature": "SUBSTRING(s FROM start [FOR length])",
     "doc": "1-based, counted in code points.", "insert": "SUBSTRING(${1:s} FROM ${2:1} FOR ${3:3})"},
    {"name": "CAST", "signature": "CAST(x AS type)", "doc": "Between numeric types.", "insert": "CAST(${1:x} AS ${2:DOUBLE})"},
]

TYPES = ["INT32", "INT64", "FLOAT32", "FLOAT64", "DOUBLE", "BIGINT", "INTEGER", "VARCHAR",
         "STRING", "BOOLEAN", "TIMESTAMP", "DECIMAL"]


def _pick(fields: list[dict], kinds: tuple[str, ...], fallback: str, measure: bool = False) -> str:
    """The first column of a kind. For a measure, an identifier is skipped when anything
    else will do: SUM(txn_id) is valid SQL and a meaningless first query."""
    matching = [f for f in fields if any(k in str(f.get("type", "")).upper() for k in kinds)]
    if measure:
        measures = [f for f in matching if not re.search(r"(^id$|_id$|^id_)", str(f.get("name")), re.IGNORECASE)]
        matching = measures or matching
    return str(matching[0].get("name")) if matching else fallback


def templates(stream: dict | None = None) -> list[dict[str, str]]:
    """The snippet library, written against a real stream's columns when one is chosen.

    A template that names ``txn`` and ``amount`` on an engine that has neither is a
    template that fails its first validation, which is the worst possible first minute.
    """
    name = str((stream or {}).get("name") or "txn")
    fields = list((stream or {}).get("fields") or [])
    key = _pick(fields, ("VARCHAR", "STRING", "CHAR"), fields[0]["name"] if fields else "user_id")
    time_col = _pick(fields, ("TIMESTAMP",), "event_time")
    number = _pick(fields, ("INT", "BIGINT", "DECIMAL"), "amount", measure=True)
    columns = ", ".join(str(f.get("name")) for f in fields[:4]) or "*"
    return [
        {"id": "filter", "title": "Filter and project",
         "summary": "Every row that matches, as it arrives. Never revises: goes to any sink.",
         "sql": f"SELECT {columns}\nFROM {name}\nWHERE {number} > 100"},
        {"id": "tumble", "title": "Tumbling-window count",
         "summary": "One row per key per fixed window, emitted when the watermark closes it.",
         "sql": (f"SELECT window_start, window_end, {key}, COUNT(*) AS events\n"
                 f"FROM TABLE(TUMBLE(TABLE {name}, DESCRIPTOR({time_col}), INTERVAL '1' MINUTE))\n"
                 f"GROUP BY window_start, window_end, {key}")},
        {"id": "tumble-sum", "title": "Tumbling-window total",
         "summary": "A per-key total per window. SUM runs over integer columns.",
         "sql": (f"SELECT window_start, window_end, {key}, SUM({number}) AS total\n"
                 f"FROM TABLE(TUMBLE(TABLE {name}, DESCRIPTOR({time_col}), INTERVAL '1' MINUTE))\n"
                 f"GROUP BY window_start, window_end, {key}")},
        {"id": "hop", "title": "Sliding (hopping) window",
         "summary": "A minute of history, recomputed every ten seconds.",
         "sql": (f"SELECT window_start, window_end, COUNT(*) AS events\n"
                 f"FROM TABLE(HOP(TABLE {name}, DESCRIPTOR({time_col}), INTERVAL '10' SECOND, INTERVAL '60' SECOND))\n"
                 f"GROUP BY window_start, window_end")},
        {"id": "global-count", "title": "Global count",
         "summary": "One number, always current. One group, so its state is bounded.",
         "sql": f"SELECT COUNT(*) AS events\nFROM {name}"},
        {"id": "case", "title": "Classify with CASE",
         "summary": "Derive a label per row; only the branch taken is evaluated.",
         "sql": (f"SELECT {key}, {number},\n"
                 f"       CASE WHEN {number} > 1000 THEN 'large' ELSE 'small' END AS size\n"
                 f"FROM {name}")},
    ]


def output_ordinals(fields: list[dict], names: list[str]) -> list[int]:
    """Key columns chosen by name, as the ordinals the register action takes.

    By name because an analyst thinks in names and an ordinal silently changes meaning when
    somebody reorders the SELECT list. Mapped here, against the schema the engine validated,
    so the ordinal sent is the one the engine will use.
    """
    by_name: dict[str, int] = {}
    for position, field in enumerate(fields):
        ordinal = field.get("ordinal")
        by_name.setdefault(str(field.get("name")).lower(),
                           int(ordinal) if isinstance(ordinal, int) else position)
    out: list[int] = []
    for name in names:
        key = str(name).strip().lower()
        if key not in by_name:
            known = ", ".join(str(f.get("name")) for f in fields) or "none"
            raise KeyError(f"'{name}' is not a column of this query's output (it has: {known})")
        out.append(by_name[key])
    return out
