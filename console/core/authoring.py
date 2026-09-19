"""What the SQL Workbench knows about Pravaha SQL: plans, diagnostics, and their fixes.

Kept on the server, not in the browser, for the same reason the error mapping in
``routes/base.py`` is: a judgement written once cannot drift between two screens. The
workbench, the onboarding flow and a query's own page all show a plan and all show a
refusal, and they must show them the same way.

Everything here is a pure function of what the engine's *public* API returned -- the
``/validate`` diagnostics and the ``/explain`` text -- plus the catalog. Nothing is
inferred that the engine did not say. A fix is offered only where the change it makes is
certain to be the one the diagnostic asks for; otherwise the diagnostic links to its help
and stops there.
"""
from __future__ import annotations

import difflib
import re
from typing import Any

# --------------------------------------------------------------------------- plan graph

_OPERATOR = re.compile(r"^(?P<op>[A-Za-z][A-Za-z0-9_]*)(?:\((?P<detail>.*)\))?\s*$")

#: Operator families, for the node's colour and icon. The label is the engine's own; this
#: only groups it, and anything unrecognised is shown as itself in the neutral family.
FAMILIES = {
    "scan": "source", "source": "source", "lookup": "source",
    "filter": "filter", "project": "project", "calc": "project",
    "aggregate": "aggregate", "windowaggregate": "aggregate", "window": "aggregate",
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


def plan_graph(text: str) -> dict[str, Any]:
    """The engine's indented plan text as nodes and edges.

    ``PhysicalPlanBuilder.explain`` writes one operator per line, children indented two
    spaces under their parent. Rows flow from the leaves (scans) to the root, so an edge
    runs child -> parent: that is the direction a reader traces data, and the direction the
    graph is laid out in.
    """
    nodes: list[dict[str, Any]] = []
    edges: list[dict[str, str]] = []
    stack: list[tuple[int, str]] = []
    for raw in (text or "").splitlines():
        if not raw.strip() or raw.lstrip().startswith("--"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        depth = indent // 2
        label = raw.strip()
        match = _OPERATOR.match(label)
        op = match.group("op") if match else label.split("(")[0].strip() or label
        detail = (match.group("detail") or "") if match else label[len(op):].strip()
        node_id = f"n{len(nodes)}"
        nodes.append({"id": node_id, "op": op, "detail": detail, "label": label,
                      "depth": depth, "family": _family(op)})
        while stack and stack[-1][0] >= depth:
            stack.pop()
        if stack:
            edges.append({"id": f"e{len(edges)}", "source": node_id, "target": stack[-1][1]})
        stack.append((depth, node_id))
    return {"nodes": nodes, "edges": edges}


# --------------------------------------------------------------------------- diagnostics

_LINE_COL = re.compile(r"line (\d+), column (\d+)(?:\s+to line (\d+), column (\d+))?", re.IGNORECASE)
_QUOTED = re.compile(r"['\"`]([A-Za-z_][A-Za-z0-9_.]*)['\"`]")
_WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def _range_of_word(sql: str, word: str) -> dict[str, int] | None:
    """The first whole-word, case-insensitive occurrence of ``word`` in ``sql``."""
    pattern = re.compile(r"(?<![A-Za-z0-9_])" + re.escape(word) + r"(?![A-Za-z0-9_])", re.IGNORECASE)
    for number, line in enumerate(sql.splitlines(), start=1):
        found = pattern.search(line)
        if found:
            return {"startLine": number, "startColumn": found.start() + 1,
                    "endLine": number, "endColumn": found.end() + 1}
    return None


def locate(sql: str, message: str) -> dict[str, int]:
    """Where in the editor a diagnostic belongs.

    The engine's diagnostics carry no structured position (see the report's list of engine
    APIs the console needs). Calcite's parse errors keep ``line N, column M`` in the message,
    so those are exact; otherwise the first identifier the message quotes that also appears
    in the SQL is underlined; otherwise the whole first line, which is honest about knowing
    only that the query is wrong somewhere.
    """
    lines = sql.splitlines() or [""]
    match = _LINE_COL.search(message or "")
    if match:
        start_line = max(1, int(match.group(1)))
        start_col = max(1, int(match.group(2)))
        if match.group(3):
            end_line, end_col = int(match.group(3)), int(match.group(4)) + 1
        else:
            end_line = start_line
            text = lines[start_line - 1] if start_line <= len(lines) else ""
            word = _WORD.match(text, start_col - 1)
            end_col = (word.end() + 1) if word else start_col + 1
        return {"startLine": start_line, "startColumn": start_col,
                "endLine": end_line, "endColumn": end_col}
    for quoted in _QUOTED.findall(message or ""):
        where = _range_of_word(sql, quoted.split(".")[-1])
        if where:
            return where
    first = lines[0] if lines else ""
    return {"startLine": 1, "startColumn": 1, "endLine": 1, "endColumn": max(2, len(first) + 1)}


def _edit(range_: dict[str, int], text: str) -> dict[str, Any]:
    return {"range": range_, "text": text}


def fixes_for(code: str, message: str, sql: str, streams: list[dict]) -> list[dict[str, Any]]:
    """Fixes the console can apply with certainty, for one diagnostic.

    Each is ``{"title", "edits": [...]}`` (text edits the editor applies) or ``{"title",
    "action"}`` (a workbench action: clear the sink, open a template). Offered only where
    the change is the one the diagnostic asks for -- a guessed fix that compiles and means
    something else is worse than none.
    """
    out: list[dict[str, Any]] = []
    names = [s.get("name", "") for s in streams if s.get("name")]
    quoted = _QUOTED.findall(message or "")

    if code == "PRV-2003":
        # Unknown stream: the nearest declared name, when one is close enough to be a typo.
        for word in quoted or [w for w in _WORD.findall(sql)]:
            if word.lower() in {n.lower() for n in names}:
                continue
            close = difflib.get_close_matches(word, names, n=2, cutoff=0.6)
            where = _range_of_word(sql, word)
            for candidate in close:
                if where:
                    out.append({"title": f"Replace '{word}' with '{candidate}'",
                                "edits": [_edit(where, candidate)]})
            if close:
                break
        out.append({"title": "Browse the declared streams", "action": "open-catalog"})

    elif code == "PRV-2002":
        columns = sorted({f.get("name", "") for s in streams for f in s.get("fields", [])
                          if f.get("name")})
        for word in quoted:
            bare = word.split(".")[-1]
            if bare.lower() in {c.lower() for c in columns}:
                continue
            close = difflib.get_close_matches(bare, columns, n=2, cutoff=0.6)
            where = _range_of_word(sql, bare)
            for candidate in close:
                if where:
                    out.append({"title": f"Replace '{bare}' with '{candidate}'",
                                "edits": [_edit(where, candidate)]})
            if close:
                break

    elif code == "PRV-2050":
        out.append({"title": "Start from a tumbling-window template", "action": "template:tumble"})

    elif code == "PRV-2041":
        out.append({"title": "Register without the sink", "action": "clear-sink"})

    elif code in {"PRV-2060", "PRV-2061"}:
        out.append({"title": "Fill in the parameters", "action": "focus-params"})

    return out


def enrich(result: dict, sql: str, streams: list[dict]) -> dict[str, Any]:
    """The engine's /validate answer, made into what an editor needs.

    Adds each diagnostic's position, its console help page (the engine's own ``helpUrl``
    points at a host that does not exist -- see TROUBLESHOOTING.md -- and an air-gapped
    browser could not reach it if it did), and the fixes that apply.
    """
    diagnostics = []
    for diagnostic in result.get("diagnostics") or []:
        code = str(diagnostic.get("code") or "")
        message = str(diagnostic.get("message") or "")
        diagnostics.append({
            "code": code,
            "message": message,
            "severity": str(diagnostic.get("severity") or "error"),
            "help": f"/help/codes/{code}" if code.startswith("PRV-") else "/help/troubleshooting",
            "range": locate(sql, message),
            "fixes": fixes_for(code, message, sql, streams),
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
