"""The console's own observability: the assistant's metrics, JSON log lines, and trace propagation.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Three things, each off or plain until configured:

* :class:`AssistMetrics` -- requests, tokens, failures, fallbacks and latency per model and profile,
  counted at the one router the console holds, and rendered in the Prometheus text format at
  ``/metrics`` (``metrics.enabled``). By hand, not with ``prometheus_client``: the console does not
  depend on it, and the format is a few lines of text.
* :class:`JsonFormatter` -- ``logging.format: json`` writes one JSON object per line with the time,
  level, logger, thread, message, the request's correlation id and, when OpenTelemetry is installed
  and a span is current, its trace and span ids.
* :class:`RequestContext` -- ASGI middleware that gives each request a correlation id (the one the
  browser's api.js sent, else a new one), answers it in ``X-Correlation-Id``, and carries a
  ``traceparent`` the request arrived with onto the engine calls made for it
  (:mod:`pravaha.tracecontext`), so a trace started in front of the console runs through the engine.

**Labels are bounded by configuration.** A model id and a profile name are what an administrator
configured; a failure kind is one of the SDK's few. A user, a prompt, a statement or a key is never a
label -- the assist log is where who asked what is kept.
"""
from __future__ import annotations

import contextvars
import datetime
import json
import logging
import re
import secrets
import threading
from collections.abc import Iterable
from typing import Any

#: Latency buckets, seconds: a model answers in a fraction of a second or in tens of seconds.
BUCKETS = (0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0, 120.0)

_KIND = re.compile(r"^[a-z_]{1,40}$")
_PLAIN = re.compile(r"^[A-Za-z0-9._:@-]{1,64}$")

#: This request's correlation id, for log lines written while it is served.
correlation: contextvars.ContextVar[str | None] = contextvars.ContextVar("pravaha_console_correlation",
                                                                          default=None)


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _labels(pairs: Iterable[tuple[str, str]]) -> str:
    return "{" + ",".join(f'{k}="{_escape(str(v))}"' for k, v in pairs) + "}"


def _kind(value: Any) -> str:
    """A failure's kind as a label: the SDK names it by class (``ModelUnavailable``), which becomes
    ``model_unavailable``; anything that does not look like one of those is ``other``."""
    text = re.sub(r"(?<!^)(?=[A-Z])", "_", str(value or "").strip()).lower()
    return text if _KIND.match(text) else "other"


class AssistMetrics:
    """What the console's assistant has asked of its models since the console started."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.requests: dict[tuple[str, str, str], int] = {}
        self.tokens: dict[tuple[str, str, str], int] = {}
        self.failures: dict[tuple[str, str, str], int] = {}
        self.fallbacks: dict[tuple[str, str], int] = {}
        self.latency: dict[tuple[str, str], list[float]] = {}

    @staticmethod
    def _add(table: dict, key: tuple, amount: int = 1) -> None:
        table[key] = table.get(key, 0) + amount

    def _moved_past(self, profile: str, attempts: Iterable[Any]) -> None:
        for attempt in attempts or ():
            model = str(getattr(attempt, "model_id", "") or "none")
            self._add(self.failures, (model, profile, _kind(getattr(attempt, "kind", None))))
            self._add(self.fallbacks, (model, profile))

    def _observe(self, model: str, profile: str, seconds: float) -> None:
        # [count per bucket..., +Inf count, sum]
        row = self.latency.setdefault((model, profile), [0.0] * (len(BUCKETS) + 2))
        for i, bound in enumerate(BUCKETS):
            if seconds <= bound:
                row[i] += 1
        row[len(BUCKETS)] += 1
        row[len(BUCKETS) + 1] += max(0.0, seconds)

    def answered(self, profile: str, routed: Any, seconds: float) -> None:
        """A model answered: ``routed`` is the SDK's ``RoutedResponse``."""
        model = str(getattr(routed, "model_id", "") or "none")
        usage = getattr(routed, "usage", None)
        with self._lock:
            self._add(self.requests, (model, profile, "ok"))
            self._add(self.tokens, (model, profile, "input"), int(getattr(usage, "input_tokens", 0) or 0))
            self._add(self.tokens, (model, profile, "output"), int(getattr(usage, "output_tokens", 0) or 0))
            self._moved_past(profile, getattr(routed, "attempts", ()))
            self._observe(model, profile, seconds)

    def failed(self, profile: str, exc: BaseException, seconds: float) -> None:
        """No model answered; ``exc`` is what the router raised."""
        from pravaha.assist import AssistConfigError, BudgetExceeded, ModelError

        model = "none"
        if isinstance(exc, BudgetExceeded):
            outcome = "budget"
        elif isinstance(exc, ModelError):
            outcome = "model_error"
            model = str(getattr(exc, "alias", None) or "none")
        elif isinstance(exc, AssistConfigError):
            outcome = "config"
        else:
            outcome = "error"
        with self._lock:
            self._add(self.requests, (model, profile, outcome))
            if isinstance(exc, ModelError):
                self._add(self.failures, (model, profile, _kind(getattr(exc, "kind", None))))
                self._moved_past(profile, getattr(exc, "attempts", ()))
            self._observe(model, profile, seconds)

    def render(self, *, version: str = "", ledger_today: dict[str, int] | None = None) -> str:
        """The Prometheus text exposition format, version 0.0.4."""
        out: list[str] = []

        def family(name: str, kind: str, help_text: str) -> None:
            out.append(f"# HELP {name} {help_text}")
            out.append(f"# TYPE {name} {kind}")

        with self._lock:
            family("pravaha_console_info", "gauge", "The console process, by version; always 1.")
            out.append(f"pravaha_console_info{_labels([('version', version)])} 1")
            family("pravaha_console_assist_requests_total", "counter",
                   "Assistant model requests, by the model that answered or failed last and the outcome.")
            for (model, profile, outcome), n in sorted(self.requests.items()):
                out.append(f"pravaha_console_assist_requests_total"
                           f"{_labels([('model', model), ('profile', profile), ('outcome', outcome)])} {n}")
            family("pravaha_console_assist_tokens_total", "counter", "Tokens a model used, input and output.")
            for (model, profile, direction), n in sorted(self.tokens.items()):
                out.append(f"pravaha_console_assist_tokens_total"
                           f"{_labels([('model', model), ('profile', profile), ('direction', direction)])} {n}")
            family("pravaha_console_assist_failures_total", "counter",
                   "A model's failures by kind, including those a fallback absorbed.")
            for (model, profile, kind), n in sorted(self.failures.items()):
                out.append(f"pravaha_console_assist_failures_total"
                           f"{_labels([('model', model), ('profile', profile), ('kind', kind)])} {n}")
            family("pravaha_console_assist_fallbacks_total", "counter",
                   "Times a request moved past this model to the next in its profile's chain.")
            for (model, profile), n in sorted(self.fallbacks.items()):
                out.append(f"pravaha_console_assist_fallbacks_total"
                           f"{_labels([('model', model), ('profile', profile)])} {n}")
            family("pravaha_console_assist_latency_seconds", "histogram",
                   "Seconds per request, fallbacks included.")
            for (model, profile), row in sorted(self.latency.items()):
                base = [("model", model), ("profile", profile)]
                for i, bound in enumerate(BUCKETS):
                    out.append(f"pravaha_console_assist_latency_seconds_bucket"
                               f"{_labels(base + [('le', repr(bound))])} {int(row[i])}")
                out.append(f"pravaha_console_assist_latency_seconds_bucket"
                           f"{_labels(base + [('le', '+Inf')])} {int(row[len(BUCKETS)])}")
                out.append(f"pravaha_console_assist_latency_seconds_sum{_labels(base)} {row[len(BUCKETS) + 1]}")
                out.append(f"pravaha_console_assist_latency_seconds_count{_labels(base)} {int(row[len(BUCKETS)])}")
        family("pravaha_console_assist_ledger_tokens_today", "gauge",
               "Tokens the usage ledger has charged today, every person together, by model.")
        for model, n in sorted((ledger_today or {}).items()):
            out.append(f"pravaha_console_assist_ledger_tokens_today{_labels([('model', model)])} {n}")
        return "\n".join(out) + "\n"


def ledger_today(document: Any, today: str) -> dict[str, int]:
    """Today's tokens per model from a usage-ledger document (``{"days": {day: {user: {model: n}}}}``)."""
    days = document.get("days") if isinstance(document, dict) else None
    people = days.get(today) if isinstance(days, dict) else None
    totals: dict[str, int] = {}
    for models in (people or {}).values() if isinstance(people, dict) else ():
        if not isinstance(models, dict):
            continue
        for model, tokens in models.items():
            try:
                totals[str(model)] = totals.get(str(model), 0) + int(tokens)
            except (TypeError, ValueError):
                continue
    return totals


def authorized(header: str | None, token: str) -> bool:
    """Whether an ``Authorization`` header carries ``token`` as its bearer, compared in constant time."""
    if not token:
        return True
    presented = (header or "").strip()
    if not presented.lower().startswith("bearer "):
        return False
    return secrets.compare_digest(presented[7:].strip().encode(), token.encode())


# ====================================================================== logs

def _trace_ids() -> tuple[str | None, str | None]:
    try:
        # Optional: only when the deployment traces the console.
        from opentelemetry import trace  # type: ignore[import-not-found,unused-ignore]
    except ImportError:
        return None, None
    context = trace.get_current_span().get_span_context()
    if not getattr(context, "is_valid", False):
        return None, None
    return f"{context.trace_id:032x}", f"{context.span_id:016x}"


class JsonFormatter(logging.Formatter):
    """One JSON object per record: ``@timestamp``, ``level``, ``logger``, ``thread``, ``message``, and
    ``correlationId``, ``traceId`` and ``spanId`` when there are any."""

    def format(self, record: logging.LogRecord) -> str:
        moment = datetime.datetime.fromtimestamp(record.created, datetime.UTC)
        line: dict[str, Any] = {
            "@timestamp": moment.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "level": record.levelname,
            "logger": record.name,
            "thread": record.threadName,
            "message": record.getMessage(),
        }
        cid = correlation.get()
        if cid:
            line["correlationId"] = cid
        trace_id, span_id = _trace_ids()
        if trace_id:
            line["traceId"], line["spanId"] = trace_id, span_id
        if record.exc_info:
            line["stack_trace"] = self.formatException(record.exc_info)
        return json.dumps(line, ensure_ascii=False, default=str)


def configure_logging(format_name: str, level: str = "INFO") -> None:
    """``logging.format``: ``text`` (the default pattern) or ``json``. Anything else refuses the start,
    because a pipeline expecting one and receiving the other breaks quietly."""
    chosen = (format_name or "text").strip().lower()
    if chosen not in ("text", "json"):
        raise ValueError(f"logging.format is {format_name!r}, and it is text or json")
    handler = logging.StreamHandler()
    if chosen == "json":
        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)-5s %(name)s — %(message)s"))
    root = logging.getLogger()
    for existing in list(root.handlers):
        root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(getattr(logging, (level or "INFO").upper(), logging.INFO))


# ====================================================================== each request

class RequestContext:
    """ASGI middleware: a correlation id per request, and the request's ``traceparent`` carried onto
    the engine calls made while serving it."""

    def __init__(self, app: Any) -> None:
        self.app = app

    async def __call__(self, scope: dict, receive: Any, send: Any) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        headers = {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope.get("headers") or ()}
        given = headers.get("x-correlation-id", "")
        cid = given if _PLAIN.match(given) else secrets.token_hex(8)

        async def answer(message: dict) -> None:
            if message.get("type") == "http.response.start":
                message.setdefault("headers", [])
                message["headers"] = list(message["headers"]) + [(b"x-correlation-id", cid.encode())]
            await send(message)

        from pravaha import tracecontext

        token = correlation.set(cid)
        try:
            with tracecontext.use(headers.get("traceparent"), headers.get("tracestate")):
                await self.app(scope, receive, answer)
        finally:
            correlation.reset(token)


__all__ = ["AssistMetrics", "JsonFormatter", "RequestContext", "authorized", "configure_logging",
           "correlation", "ledger_today"]
