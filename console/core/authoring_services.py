"""The services the workbench stands on: the stream catalogue, authoring, views.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Split out of ``core.services`` (CONSOLESIZE-1), which re-exports every name here.
"""
from __future__ import annotations

import threading
import time
from typing import Any

from core import credential
from core.engine import Engine
from core.query_services import Query, QueryService
from core.service_base import ServiceError, _refusal, jsonable


def _metrics_state(operator_metrics: Any, query_metrics: Any) -> str:
    """Which of the three answers the engine gave about per-operator numbers.

    ``measured``, ``operators_off`` (there is something running, and the counters were never
    built into its stages -- ``pravaha.metrics.operators``), or ``not_running`` (this is SQL
    nobody registered, so there is nothing to measure). The engine says the same thing in
    ``metricsNote``, in words; this is the same distinction as a key, so a screen can choose
    what to draw without reading English.
    """
    if operator_metrics:
        return "measured"
    return "operators_off" if query_metrics else "not_running"


class CatalogService:
    """What exists to be queried: streams and their fields, from the engine's public REST API.

    Cached for a few seconds. The workbench asks on every page load and completion asks on
    every keystroke burst; the catalog changes when an administrator declares a stream,
    which is not a thing that happens between two keystrokes.
    """

    def __init__(self, engine: Engine, ttl_seconds: float = 5.0) -> None:
        self._engine = engine
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        # By the person asking (ADR-052): the engine lists the streams each principal may read,
        # so one person's catalogue is never another's answer, however recently it was asked.
        self._cached: dict[str, tuple[list[dict], float]] = {}

    def forget(self) -> None:
        """Drop what is cached, so the next read asks the engine.

        The cache is five seconds deep, which is right for a catalogue an administrator changes
        by hand and wrong for anything that needs to see the engine's current answer now -- a
        test photographing the error state of a failed call among them, which otherwise
        photographs whatever the previous page load left behind.
        """
        with self._lock:
            self._cached = {}

    def streams(self, fresh: bool = False) -> list[dict]:
        whose = credential.scope()
        with self._lock:
            held = self._cached.get(whose)
            if not fresh and held is not None and time.monotonic() - held[1] < self._ttl:
                return held[0]
        try:
            streams = sorted(self._engine.streams(), key=lambda s: str(s.get("name", "")))
        except Exception as exc:
            raise _refusal(exc, 503) from exc
        with self._lock:
            self._cached[whose] = (streams, time.monotonic())
        return streams

    def streams_or_empty(self) -> list[dict]:
        """For callers that can do without the catalog -- validation, the palette."""
        try:
            return self.streams()
        except ServiceError:
            return []

    def stream(self, name: str) -> dict:
        for stream in self.streams():
            if str(stream.get("name", "")).lower() == name.lower():
                return stream
        raise ServiceError(f"no stream named '{name}' is declared on this engine, or this "
                           "console's identity may not read it", status=404)

    def sinks(self) -> list[dict]:
        """The sink bindings this identity may see (``GET /api/v1/sinks`` via the SDK).

        Only what the engine publishes: plugin, row shape, key, emit modes, whether a revising
        query may write there, and the visible writers. A binding's options -- where its
        credentials live -- are never part of the engine's answer, so they cannot be part of
        this one.
        """
        try:
            return sorted(self._engine.sinks(), key=lambda s: str(s.get("name", "")))
        except Exception as exc:
            raise _refusal(exc, 503) from exc

    def sinks_or_empty(self) -> list[dict]:
        try:
            return self.sinks()
        except ServiceError:
            return []

    def declare(self, name: str, schema: str, event_time: str | None = None,
                out_of_orderness: str | None = None) -> dict:
        from core.snippets import IDENTIFIER

        if not IDENTIFIER.match(name or ""):
            raise ServiceError("a stream name must be letters, digits and underscores", status=400)
        if not (schema or "").strip():
            raise ServiceError("a stream needs a schema, as name:TYPE pairs separated by commas",
                               status=400)
        try:
            declared = self._engine.declare_stream(
                name, schema.strip(), event_time=(event_time or "").strip() or None,
                out_of_orderness=(out_of_orderness or "").strip() or None)
        except Exception as exc:
            raise _refusal(exc) from exc
        with self._lock:
            self._cached = {}
        return declared

    def completions(self) -> dict:
        """Everything the editor completes: streams, their columns with types, functions."""
        from core import authoring

        return {
            "streams": [{"name": s.get("name"), "version": s.get("version"),
                         "fields": s.get("fields") or []} for s in self.streams_or_empty()],
            "functions": authoring.FUNCTIONS,
            "keywords": authoring.KEYWORDS,
            "types": authoring.TYPES,
        }


class AuthoringService:
    """Validate and explain, through the engine's public REST API, made editor-shaped."""

    LEVELS = ("physical", "logical", "codegen")

    def __init__(self, engine: Engine, catalog: CatalogService) -> None:
        self._engine = engine
        self._catalog = catalog

    def validate(self, sql: str) -> dict:
        from core import authoring

        try:
            raw = self._engine.validate(sql)
        except Exception as exc:
            raise _refusal(exc, 503) from exc
        return authoring.enrich(raw, sql, self._catalog.streams_or_empty())

    def explain(self, sql: str, level: str = "physical") -> dict:
        from core import authoring

        if level not in self.LEVELS:
            raise ServiceError(f"level must be one of {', '.join(self.LEVELS)}", status=400)
        if not sql.strip():
            raise ServiceError("nothing to explain", status=400)
        try:
            raw = self._engine.explain(sql, level)
        except Exception as exc:
            raise _refusal(exc) from exc
        text = str(raw.get("plan") or "")
        engine_graph = raw.get("graph") or {}
        return {
            "level": raw.get("level", level),
            "plan": text,
            # The engine's own structure, renamed for the island -- not a parse of the text.
            "graph": authoring.plan_graph(engine_graph),
            "output_fields": list(raw.get("outputFields") or []),
            # The engine does not count per operator, and says so; the console repeats it
            # rather than drawing zeroes.
            "operator_metrics": engine_graph.get("operatorMetrics"),
            "metrics_note": engine_graph.get("metricsNote"),
            # Measured, never inferred from row counts; null until something has been sampled.
            "bottleneck": engine_graph.get("bottleneck"),
            "metrics_state": _metrics_state(engine_graph.get("operatorMetrics"), None),
            "query_metrics": None,
        }

    def plan(self, name: str) -> dict:
        """The plan a registered query is running, with the totals the engine measures for it."""
        from core import authoring

        try:
            raw = self._engine.query_plan(name)
        except Exception as exc:
            raise _refusal(exc) from exc
        return {
            "level": "physical",
            "plan": "",
            "graph": authoring.plan_graph(raw),
            "output_fields": [],
            "operator_metrics": raw.get("operatorMetrics"),
            "metrics_note": raw.get("metricsNote"),
            "bottleneck": raw.get("bottleneck"),
            "metrics_state": _metrics_state(raw.get("operatorMetrics"), raw.get("query")),
            "query_metrics": raw.get("query"),
        }

    def diff(self, left: dict, right: dict, queries: QueryService) -> dict:
        """Two versions of a query side by side (design 23.7): their SQL, their plans matched
        operator by operator, and what the engine will do with the right relative to the left.

        Each side is ``{"query": name}`` -- a registered query: its SQL from the registry, its
        running plan and measured totals from ``GET /api/v1/queries/{name}/plan``, its keys and
        retention from its description -- or ``{"sql": ..., "label": ...}``, a draft, explained
        and validated as it stands. Measured totals belong to a registered side only: a draft
        has not run. A side the engine would not plan, or will not show this identity, is said
        on that side (``plan_error``, ``refused``) and the rest is still answered.
        """
        from core import authoring

        try:
            registry = queries.find(limit=QueryService.MAX_LIMIT).items
        except ServiceError:
            registry = None
        sides = [self._diff_side(spec if isinstance(spec, dict) else {}, registry, queries)
                 for spec in (left, right)]
        a, b = sides
        plan = (authoring.plan_diff(a["graph"], b["graph"])
                if a["graph"] is not None and b["graph"] is not None else None)
        return {
            "left": a,
            "right": b,
            "same_sql": a["sql"].strip() == b["sql"].strip(),
            "plan": plan,
            "consequences": authoring.diff_consequences(plan, a, b),
            "registry_known": registry is not None,
        }

    def _diff_side(self, spec: dict, registry: list[Query] | None, queries: QueryService) -> dict:
        name = str(spec.get("query") or "").strip()
        side: dict[str, Any] = {
            "label": str(spec.get("label") or name or "draft"), "query": name or None, "sql": "",
            "graph": None, "plan_error": None, "refused": None, "query_metrics": None,
            "metrics_note": None, "operator_metrics": None, "bottleneck": None,
            "metrics_state": None,
            "fingerprint": None, "keys": [], "retention": None,
            "output_fields": None, "registered_as": None,
        }

        def refusal_of(exc: ServiceError) -> dict:
            return {"message": str(exc), "code": exc.code, "status": exc.status}

        if name:
            found = next((q for q in registry or [] if q.name == name), None) or queries.get(name)
            side.update(sql=found.sql, fingerprint=found.fingerprint, retention=found.retention)
            try:
                detail = queries.detail(name)
                side["keys"] = [str(k.get("name")) for k in detail.get("keyColumns") or []]
                side["retention"] = detail.get("retention") or side["retention"]
            except ServiceError as exc:
                if exc.status == 403:
                    side["refused"] = refusal_of(exc)
            if side["refused"] is None:
                try:
                    running = self.plan(name)
                    side.update(graph=running["graph"], query_metrics=running["query_metrics"],
                                metrics_note=running["metrics_note"],
                                operator_metrics=running["operator_metrics"],
                                bottleneck=running["bottleneck"],
                                metrics_state=running["metrics_state"])
                except ServiceError as exc:
                    if exc.status == 403:
                        side["refused"] = refusal_of(exc)
                    else:
                        side["plan_error"] = refusal_of(exc)
            if side["refused"] is None:
                # The running view's own columns, not what its SQL would produce if validated now.
                try:
                    side["output_fields"] = list(self._engine.describe_view(name).get("schema") or [])
                except Exception:  # noqa: BLE001 -- any failure falls back to validating its SQL
                    side["output_fields"] = self._output_fields(found.sql)
            return side

        sql = str(spec.get("sql") or "")
        if not sql.strip():
            raise ServiceError("nothing to compare: a side needs a registered query's name or some SQL",
                               status=400)
        side["sql"] = sql
        try:
            side["graph"] = self.explain(sql)["graph"]
        except ServiceError as exc:
            side["plan_error"] = refusal_of(exc)
        side["output_fields"] = self._output_fields(sql)
        # A draft whose SQL is exactly a registered query's is that query: its fingerprint is known.
        same = [q for q in registry or [] if q.sql.strip() == sql.strip()]
        if same:
            side["registered_as"] = ", ".join(q.name for q in same)
            prints = {q.fingerprint for q in same}
            side["fingerprint"] = prints.pop() if len(prints) == 1 else None
        return side

    def _output_fields(self, sql: str) -> list[dict] | None:
        """The validated output schema, or ``None`` when the engine would not say (not "no columns")."""
        try:
            checked = self.validate(sql)
        except ServiceError:
            return None
        return list(checked["output_fields"]) if checked["valid"] else None

    def key_ordinals(self, sql: str, names: list[str]) -> tuple[list[int], list[dict]]:
        """Key columns by name -> the ordinals the engine takes, against its own schema."""
        from core import authoring

        checked = self.validate(sql)
        if not checked["valid"]:
            first = checked["diagnostics"][0] if checked["diagnostics"] else {}
            raise ServiceError(first.get("message") or "the query does not validate",
                               status=400, code=first.get("code"))
        try:
            return authoring.output_ordinals(checked["output_fields"], names), checked["output_fields"]
        except KeyError as exc:
            raise ServiceError(str(exc.args[0]), status=400) from exc


class ViewService:
    """A registered query's view, as an application developer consumes it."""

    def __init__(self, engine: Engine, queries: QueryService, authoring: AuthoringService,
                 row_limit: int = 500) -> None:
        self._engine = engine
        self._queries = queries
        self._authoring = authoring
        self._limit = row_limit

    def describe(self, name: str) -> dict:
        """The view as the engine describes it, without reading it: schema, key, retention,
        sink and fingerprint (``GET /api/v1/views/{name}`` via the SDK).

        This replaced re-validating the query's SQL to guess the view's columns -- which
        answered "what would this SQL produce now", not "what does the running view hold" --
        and, failing that, reading the whole view to keep only its header.
        """
        try:
            return self._engine.describe_view(name)
        except Exception as exc:
            raise _refusal(exc) from exc

    def schema(self, name: str) -> list[dict]:
        """The view's columns, from the engine's description of the view itself."""
        return list(self.describe(name).get("schema") or [])

    def lookup(self, name: str, filters: dict[str, Any]) -> dict:
        """A point query: ``SELECT * FROM view WHERE col = ? AND ...``, parameterised.

        Column names are checked against the view's own schema and the values bound as
        parameters, so nothing typed into the form is ever spliced into SQL.
        """
        from core.snippets import IDENTIFIER, _typed

        if not IDENTIFIER.match(name or ""):
            raise ServiceError(f"'{name}' is not a view name this console can query", status=400)
        known = {str(f.get("name")).lower(): str(f.get("name")) for f in self.schema(name)}
        clauses, values = [], []
        for column, value in filters.items():
            if value is None or str(value) == "":
                continue
            actual = known.get(str(column).lower())
            if actual is None:
                raise ServiceError(f"'{column}' is not a column of {name}", status=400)
            clauses.append(f"{actual} = ?")
            values.append(_typed(str(value)))
        sql = f"SELECT * FROM {name}" + (" WHERE " + " AND ".join(clauses) if clauses else "")
        started = time.monotonic()
        try:
            columns, rows, types = self._engine.query_typed(sql, values or None)
        except Exception as exc:
            raise _refusal(exc) from exc
        return {"sql": sql, "parameters": jsonable(values), "columns": columns, "types": types,
                "rows": jsonable(rows[: self._limit]), "truncated": len(rows) > self._limit,
                "returned": min(len(rows), self._limit),
                "took_ms": round((time.monotonic() - started) * 1000, 1)}
