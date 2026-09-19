"""Reading the engine's Prometheus endpoint, and answering "is everything healthy?".

Parsed here, in the console's backend, rather than in the browser (design 23.17: a BFF,
not a second engine). Three reasons:

- The exposition format carries every JVM meter the engine has. A browser should receive
  the dozen numbers a screen draws, not two hundred kilobytes it throws away.
- Ten operators watching the dashboard cost the engine **one** scrape a second, because
  the snapshot is cached here and fanned out -- the same rule the live tail follows.
- The health verdict is a judgement ("nine tenths of the state ceiling is worth a look")
  and a judgement written once is one that two screens cannot disagree about.

Nothing here invents a number the engine does not publish. Where a screen wants one the
engine does not measure -- per-operator telemetry, lane backpressure, latency percentiles
-- the snapshot says so by name, and the screen says the engine does not measure it rather
than drawing a zero.
"""
from __future__ import annotations

import dataclasses
import math
import re
import threading
import time
from collections import deque
from collections.abc import Callable
from typing import Any

#: One sample line: ``name{label="value",...} value [timestamp]``.
_SAMPLE = re.compile(
    r'^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)'
    r'(?:\{(?P<labels>.*)\})?'
    r'\s+(?P<value>\S+)'
    r'(?:\s+(?P<ts>-?\d+))?\s*$'
)
_LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*"((?:[^"\\]|\\.)*)"')


@dataclasses.dataclass(frozen=True)
class Sample:
    name: str
    labels: dict[str, str]
    value: float


def _unescape(value: str) -> str:
    return value.replace("\\n", "\n").replace('\\"', '"').replace("\\\\", "\\")


def _number(text: str) -> float:
    lowered = text.lower()
    if lowered in {"nan"}:
        return math.nan
    if lowered in {"+inf", "inf"}:
        return math.inf
    if lowered == "-inf":
        return -math.inf
    return float(text)


def parse(text: str) -> list[Sample]:
    """Every sample in a Prometheus text exposition, comments and HELP/TYPE skipped.

    Tolerant by design: a line it cannot read is skipped rather than failing the whole
    scrape, because one malformed meter should cost the dashboard one number, not all of
    them.
    """
    samples: list[Sample] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = _SAMPLE.match(line)
        if not match:
            continue
        try:
            value = _number(match.group("value"))
        except ValueError:
            continue
        labels = {k: _unescape(v) for k, v in _LABEL.findall(match.group("labels") or "")}
        samples.append(Sample(match.group("name"), labels, value))
    return samples


#: The per-query gauges PravahaMetrics publishes, by the name Prometheus exposes them under.
QUERY_METERS = {
    "pravaha_query_rows_in": "rows_in",
    "pravaha_query_state_held": "state_held",
    "pravaha_query_state_ceiling": "state_ceiling",
    "pravaha_query_state_fraction": "state_fraction",
    "pravaha_query_view_size": "view_size",
    "pravaha_query_view_evicted": "view_evicted",
    "pravaha_query_view_updates": "view_updates",
    "pravaha_query_view_removals": "view_removals",
    "pravaha_query_watermark_lag_seconds": "watermark_lag_seconds",
    "pravaha_query_running": "running",
    "pravaha_query_feed_stopped": "feed_stopped",
    "pravaha_query_feed_failures_total": "feed_failures",
    "pravaha_query_subscribers": "subscribers",
    "pravaha_query_checkpoint_last_success_timestamp_seconds": "checkpoint_last_success",
    "pravaha_query_checkpoint_duration_seconds": "checkpoint_duration_seconds",
    "pravaha_query_checkpoint_failures_total": "checkpoint_failures",
    "pravaha_query_commit_latency_seconds_count": "commit_count",
    "pravaha_query_commit_latency_seconds_sum": "commit_seconds_sum",
}

#: Derived per query from two scrapes, not published by the engine as such.
DERIVED_METERS = ("rows_in_rate", "commit_latency_mean_seconds", "checkpoint_age_seconds",
                  "checkpoint_failures_new")

#: Node-level meters worth a tile, when the engine exposes them (Spring Boot's defaults).
NODE_METERS = {
    "process_uptime_seconds": "uptime_seconds",
    "process_cpu_usage": "cpu_usage",
    "system_cpu_usage": "system_cpu_usage",
    "jvm_threads_live_threads": "threads",
}


def _finite(value: float | None) -> float | None:
    if value is None or math.isnan(value) or math.isinf(value):
        return None
    return value


def summarize(samples: list[Sample]) -> dict[str, Any]:
    """Per-query numbers keyed by query name, plus a few node-level ones.

    A NaN watermark lag stays ``None`` -- "no watermark yet" -- rather than becoming zero,
    which would show a query that has never seen a row as perfectly up to date.
    """
    queries: dict[str, dict[str, Any]] = {}
    node: dict[str, float] = {}
    heap_used = 0.0
    heap_max = 0.0
    for sample in samples:
        key = QUERY_METERS.get(sample.name)
        if key and "query" in sample.labels:
            entry = queries.setdefault(sample.labels["query"], {"name": sample.labels["query"]})
            entry[key] = _finite(sample.value)
            continue
        node_key = NODE_METERS.get(sample.name)
        if node_key:
            finite = _finite(sample.value)
            if finite is not None:
                node[node_key] = finite
        elif sample.name == "jvm_memory_used_bytes" and sample.labels.get("area") == "heap":
            heap_used += _finite(sample.value) or 0.0
        elif sample.name == "jvm_memory_max_bytes" and sample.labels.get("area") == "heap":
            value = _finite(sample.value)
            if value and value > 0:
                heap_max += value
    if heap_used:
        node["heap_used_bytes"] = heap_used
    if heap_max:
        node["heap_max_bytes"] = heap_max
    return {"queries": queries, "node": node}


@dataclasses.dataclass(frozen=True)
class Finding:
    """One thing on the dashboard worth a person's attention."""

    severity: str  # "critical" | "warn" | "info"
    query: str | None
    title: str
    detail: str
    #: The engine's ``PRV-nnnn`` behind it, when there is one: the screen links it to its help page.
    code: str | None = None

    def as_dict(self) -> dict:
        return dataclasses.asdict(self)


SEVERITY_ORDER = {"critical": 0, "warn": 1, "info": 2}

#: The title of the finding for a query whose source has stopped (FEED-1).
SOURCE_STOPPED = "Source stopped"


def source_stopped(name: str, stop: dict | None = None) -> Finding:
    """A query that says RUNNING and whose view has stopped moving, because a source failed.

    Critical, although the query is running: nothing it shows will change again until
    somebody acts, which is what the verdict's worst tier is for.
    """
    stop = stop or {}
    code = stop.get("code") or None
    where = f" reading {stop['where']}" if stop.get("where") else ""
    with_code = f" with {code}" if code else ""
    return Finding("critical", name, SOURCE_STOPPED,
                   f"{name} is RUNNING and its view has stopped moving: a source failed mid-read"
                   f"{where}{with_code} and is not retried. Fix the cause, then drop and register "
                   "the query again, or restart the node.", code)


def findings(queries: dict[str, dict[str, Any]], *, state_warn: float = 0.75,
             state_critical: float = 0.9, lag_warn_seconds: float = 300.0,
             checkpoint_warn_seconds: float = 900.0,
             registered_states: dict[str, str] | None = None,
             feed_stops: dict[str, dict] | None = None) -> list[Finding]:
    """The health rules, written once.

    Each rule names the query and says what to do, because "something is wrong" is the
    question an operator already had when they opened the page.
    """
    out: list[Finding] = []
    for name, q in sorted(queries.items()):
        state = (registered_states or {}).get(name, "")
        if q.get("running") == 0 or state == "FAILED":
            out.append(Finding("critical", name, "Not running",
                               f"{name} is registered but its lane is not running"
                               + (f" (state {state})" if state else "")
                               + ". Open it to see why, then resume or re-register."))
        stop = (feed_stops or {}).get(name)
        if stop is not None or q.get("feed_stopped") == 1:
            out.append(source_stopped(name, stop))
        fraction = q.get("state_fraction")
        if fraction is not None and fraction >= state_critical:
            out.append(Finding("critical", name, "State ceiling nearly reached",
                               f"{name} holds {fraction:.0%} of its state ceiling. At 100% it is "
                               "refused with PRV-4001 unless spilling is configured."))
        elif fraction is not None and fraction >= state_warn:
            out.append(Finding("warn", name, "State growing towards its ceiling",
                               f"{name} holds {fraction:.0%} of its state ceiling."))
        lag = q.get("watermark_lag_seconds")
        if "watermark_lag_seconds" in q and lag is None and state != "PAUSED":
            out.append(Finding("info", name, "No watermark yet",
                               f"{name} has not seen a row with an event time. A windowed query "
                               "emits nothing until one arrives. Is event-time declared on its "
                               "stream?"))
        elif lag is not None and lag > lag_warn_seconds:
            out.append(Finding("warn", name, "Event time is behind",
                               f"{name}'s watermark is {_duration(lag)} behind the wall clock: the "
                               "data is late, or its source has stopped."))
        # Checkpoint health: only for a query that is checkpointing at all, which the engine
        # says by publishing a last-success time (NaN, so None here, while it has none).
        new_failures = q.get("checkpoint_failures_new")
        if new_failures:
            out.append(Finding("warn", name, "Checkpoints are failing",
                               f"{name} failed {int(new_failures)} checkpoint"
                               f"{'s' if new_failures != 1 else ''} since the last look "
                               f"({int(q.get('checkpoint_failures') or 0)} in all). Recovery falls "
                               "back to the newest stored checkpoint, which is getting older. "
                               "Check the node's log and the checkpoint directory's disk."))
        age = q.get("checkpoint_age_seconds")
        if age is not None and age > checkpoint_warn_seconds and state != "PAUSED":
            out.append(Finding("warn", name, "No recent checkpoint",
                               f"{name} last stored a checkpoint {_duration(age)} ago; a restart "
                               "now would replay everything since."))
    out.sort(key=lambda f: (SEVERITY_ORDER.get(f.severity, 9), f.query or ""))
    return out


def verdict(reachable: bool, found: list[Finding], query_count: int) -> dict[str, str]:
    """The one-line answer to "is everything healthy, and if not, where?"."""
    if not reachable:
        return {"status": "critical", "headline": "The engine's metrics endpoint is not answering",
                "where": "engine"}
    critical = [f for f in found if f.severity == "critical"]
    warn = [f for f in found if f.severity == "warn"]
    if critical:
        where = ", ".join(sorted({f.query or "engine" for f in critical}))
        return {"status": "critical",
                "headline": f"{len(critical)} {'problems need' if len(critical) != 1 else 'problem needs'} attention",
                "where": where}
    if warn:
        where = ", ".join(sorted({f.query or "engine" for f in warn}))
        return {"status": "warn",
                "headline": f"Healthy, with {len(warn)} thing{'s' if len(warn) != 1 else ''} to watch",
                "where": where}
    if query_count == 0:
        return {"status": "ok", "headline": "The engine is up and nothing is registered yet",
                "where": ""}
    return {"status": "ok",
            "headline": f"All {query_count} quer{'ies are' if query_count != 1 else 'y is'} healthy",
            "where": ""}


def _duration(seconds: float) -> str:
    if seconds < 90:
        return f"{seconds:.0f}s"
    if seconds < 5400:
        return f"{seconds / 60:.0f} min"
    return f"{seconds / 3600:.1f} h"


#: What the dashboard would show and the engine does not publish yet. Named precisely so the
#: screen can say which API is missing, rather than drawing a zero.
NOT_EXPOSED = [
    {"metric": "backpressure", "label": "Backpressure",
     "why": "the engine does not sample lane backpressure, so there is no number to show"},
    {"metric": "latency", "label": "Commit latency percentiles",
     "why": "the engine keeps a count and a total per query, not each commit's duration, so "
            "the mean below is exact and a p99 would be invented"},
    {"metric": "operators", "label": "Per-operator telemetry",
     "why": "the runtime counts rows and state per query, not per operator"},
]


class MetricsHistory:
    """The last few minutes of scrapes, so a chart opened now has a line to draw.

    Scraped on demand and cached for ``ttl`` seconds, never on a timer of its own: a
    console nobody is looking at must cost the engine nothing, and one being looked at by
    ten people costs it one scrape a second.
    """

    def __init__(self, scrape: Callable[[], str], ttl: float = 1.0, keep: int = 300,
                 clock: Callable[[], float] = time.time) -> None:
        self._scrape = scrape
        self._ttl = ttl
        self._clock = clock
        self._lock = threading.Lock()
        self._points: deque = deque(maxlen=keep)
        self._last: dict[str, Any] | None = None
        self._at = 0.0

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            now = self._clock()
            if self._last is not None and now - self._at < self._ttl:
                return self._last
            try:
                summary = summarize(parse(self._scrape()))
                reachable, error = True, None
            except Exception as exc:  # noqa: BLE001 -- the dashboard renders this state
                summary, reachable, error = {"queries": {}, "node": {}}, False, str(exc)
            previous = self._points[-1] if self._points else None
            if reachable:
                for name, q in summary["queries"].items():
                    before = previous["queries"].get(name) if previous else None
                    q["rows_in_rate"] = _rate(before, q, previous["at"] if previous else None, now)
                    q["commit_latency_mean_seconds"] = _mean_latency(before, q)
                    last = q.get("checkpoint_last_success")
                    q["checkpoint_age_seconds"] = max(0.0, now - last) if last else None
                    q["checkpoint_failures_new"] = _increase(before, q, "checkpoint_failures")
                self._points.append({"at": now, "queries": {
                    n: {k: v for k, v in q.items() if k != "name"} for n, q in summary["queries"].items()}})
            self._last = {"at": now, "reachable": reachable, "error": error, **summary}
            self._at = now
            return self._last

    def series(self, metric: str) -> dict[str, list[list]]:
        """``{query: [[epoch_ms, value], ...]}`` for one per-query metric."""
        with self._lock:
            out: dict[str, list[list]] = {}
            for point in self._points:
                for name, values in point["queries"].items():
                    out.setdefault(name, []).append([int(point["at"] * 1000), values.get(metric)])
            return out


def _increase(before: dict | None, now_values: dict, key: str) -> float | None:
    """How much a counter rose between two scrapes; None when either is missing or it reset."""
    if not before:
        return None
    a, b = before.get(key), now_values.get(key)
    if a is None or b is None or b < a:
        return None
    return b - a


def _mean_latency(before: dict | None, now_values: dict) -> float | None:
    """Mean commit latency between two scrapes: delta of the total over delta of the count.

    Exact, because the engine publishes both totals; None when nothing committed in between,
    rather than a zero that would read as instantaneous.
    """
    commits = _increase(before, now_values, "commit_count")
    seconds = _increase(before, now_values, "commit_seconds_sum")
    if not commits or seconds is None:
        return None
    return round(seconds / commits, 6)


def _rate(before: dict | None, now_values: dict, then: float | None, now: float) -> float | None:
    if not before or then is None or now <= then:
        return None
    a, b = before.get("rows_in"), now_values.get("rows_in")
    if a is None or b is None or b < a:
        return None
    return round((b - a) / (now - then), 3)
