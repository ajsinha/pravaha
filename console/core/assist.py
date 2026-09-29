"""The assistant in the console (ADR-058 phase 3): one router, its administration, and the tasks.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The console process holds **one** :class:`pravaha.assist.ModelRouter`, built from the SDK's
:class:`~pravaha.assist.FileConfigStore` and *following* it. Two ways a change reaches it, and
both take effect on the very next request, with no restart:

* **Admin -> AI models.** Every change goes through :class:`~pravaha.assist.AssistAdmin` built over
  that same store and that same router: validated completely, saved against the version the
  administrator's page was drawn from (a concurrent edit is a :class:`ConfigConflict`, said as
  such), and applied to the router with ``reconfigure`` before the POST answers.
* **Anyone else** -- ``pravaha assist use``, a person editing the file. The router's watch polls the
  file (``assist.watch_seconds``) and applies what validates in this process; what does not is kept
  out and shown on the admin page (``router.last_rejected``).

Every change's :class:`~pravaha.assist.AuditRecord` -- who, what, the element before and after, the
version -- and every assist request (who, which model, tokens, a hash of what was asked, the
engine's verdict, whether it was registered) are appended to the console's own assist log, a JSON
Lines file beside the configuration. That log is where spend is accounted (the router's ledger is
a per-machine budget cap); the admin page reads it for "Recent changes" and the usage view.

The tasks run **as the signed-in person**: the engine calls the assistant makes (validate, explain,
the catalogue's listings, and registration) go through the console's one engine adapter, which
carries that person's engine session (ADR-052), and the usage ledger charges that person's name. A
draft is held here, server-side, between the draft and its registration, so what is registered is
exactly what the engine judged -- the browser sends an id and a confirmation, never the SQL.

No HTTP and no HTML in this module, like ``core.services``.
"""
from __future__ import annotations

import dataclasses
import datetime
import hashlib
import json
import logging
import os
import pathlib
import secrets
import threading
import time
from typing import Any

from pravaha.assist import (
    AssistAdmin,
    Assistant,
    AssistConfig,
    AssistConfigError,
    AssistError,
    BudgetExceeded,
    ConfigConflict,
    FileConfigStore,
    ModelError,
    ModelRouter,
    RegistrationRefused,
    UsageLedger,
)
from pravaha.assist.store import default_config_path

from core.observability import AssistMetrics, ledger_today
from core.services import ServiceError, _refusal

logger = logging.getLogger(__name__)

#: The profiles the console's surfaces ask for: drafting, and both explain tasks.
DRAFT = "draft"
EXPLAIN = "explain"
#: How many drafts one console holds between drafting and registering, and for how long.
MAX_DRAFTS = 256
DRAFT_TTL_SECONDS = 3600
#: How much of the assist log the admin page reads back (its tail).
LOG_TAIL_BYTES = 512 * 1024


def _now() -> str:
    moment = datetime.datetime.now(datetime.UTC).replace(microsecond=0)
    return moment.isoformat().replace("+00:00", "Z")


def _digest(*parts: Any) -> str:
    """A short one-way name for what was asked: the log records that a request was made and can
    match two identical ones, without holding a description or a statement."""
    text = "\x1f".join(str(p or "") for p in parts)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


# ====================================================================== the engine, as the SDK wants it

def _api_error(exc: Exception) -> Exception:
    """The console adapter's refusal as the SDK's :class:`pravaha.rest.ApiError`, which is what the
    assistant's context builder and judge catch and read (``status``, ``engine_code``)."""
    from pravaha.rest import ApiError

    from core.engine import EngineHttpError

    if isinstance(exc, EngineHttpError):
        return ApiError(int(exc.status or 0), str(exc), exc.code)
    return exc


class EngineApiAdapter:
    """The slice of :class:`pravaha.api.EngineApi` the assistant uses, answered by the console's
    one engine adapter -- so every call carries the signed-in person's engine session, and a test's
    fake engine stands in for the engine here exactly as it does everywhere else."""

    def __init__(self, engine: Any) -> None:
        self._engine = engine

    def _call(self, name: str, *args: Any, **kwargs: Any) -> Any:
        try:
            return getattr(self._engine, name)(*args, **kwargs)
        except Exception as exc:
            translated = _api_error(exc)
            if translated is exc:
                raise
            raise translated from exc

    def permissions(self) -> dict:
        return dict(self._call("permissions") or {})

    def streams(self) -> list:
        return list(self._call("streams") or [])

    def describe_queries(self) -> list:
        return list(self._call("describe_queries") or [])

    def sinks(self) -> list:
        return list(self._call("sinks") or [])

    def describe_view(self, name: str) -> dict:
        return dict(self._call("describe_view", name) or {})

    def describe_query(self, name: str) -> dict:
        return dict(self._call("describe_query", name) or {})

    def validate(self, sql: str) -> dict:
        return dict(self._call("validate", sql) or {})

    def explain(self, sql: str, level: str = "physical", *, graph: bool = False,
                keys: Any = None, retention: str | None = None, sink: str | None = None,
                name: str | None = None) -> dict:
        # The registration's fields only when given: with them the engine answers the fingerprint
        # a registration would get (EXPLAINFP-1), which the assistant's reuse offer compares.
        asked = {k: v for k, v in (("keys", keys), ("retention", retention), ("sink", sink),
                                   ("name", name)) if v}
        return dict(self._call("explain", sql, level, **asked) or {})


class _Rows:
    def __init__(self, rows: list[dict]) -> None:
        self._rows = rows

    def to_list(self) -> list[dict]:
        return list(self._rows)


class ClientAdapter:
    """What :meth:`pravaha.assist.Assistant.register` calls -- ``register`` and, for a draft with an
    index or a lane, ``query`` of its statement -- through the console's engine adapter, under the
    signed-in person's own session. The registration is theirs, authorised and audited as theirs."""

    def __init__(self, engine: Any) -> None:
        self._engine = engine

    def register(self, name: str, sql: str, keys: Any, *, sink: Any = None, retention: Any = None):
        return self._engine.register(name, sql, list(keys), sink, retention)

    def query(self, statement: str) -> _Rows:
        columns, rows = self._engine.query(statement)
        return _Rows([dict(zip(columns, row)) for row in rows])


class _PersonRouter:
    """The console's one router, asked on behalf of one person: every request is charged to
    their name in the usage ledger and checked against their daily budget -- and counted, by
    model and profile, for ``/metrics`` (never by person: the assist log is where that is kept)."""

    def __init__(self, router: ModelRouter, user: str, metrics: AssistMetrics | None = None) -> None:
        self._router = router
        self._user = user
        self._metrics = metrics

    @property
    def config(self) -> AssistConfig:
        return self._router.config

    def complete(self, request: Any, *, profile: str | None = None, model: str | None = None,
                 user: str | None = None) -> Any:
        if self._metrics is None:
            return self._router.complete(request, profile=profile, model=model, user=self._user)
        label = "direct" if model else (profile or self._router.config.default_profile or "default")
        started = time.monotonic()
        try:
            routed = self._router.complete(request, profile=profile, model=model, user=self._user)
        except Exception as exc:
            self._metrics.failed(label, exc, time.monotonic() - started)
            raise
        self._metrics.answered(label, routed, time.monotonic() - started)
        return routed


# ====================================================================== failures

class AssistFailure(Exception):
    """A task that did not answer, said the way the screens say it: a kind (``budget``,
    ``model``, ``config``, ``engine``, ``refused``, ``input``, ``unconfigured``), the words, the
    HTTP status, the engine's code when it was the engine, and -- for a model's failure -- which
    model."""

    def __init__(self, kind: str, message: str, status: int, code: str | None = None,
                 model: str | None = None) -> None:
        super().__init__(message)
        self.kind, self.message, self.status, self.code, self.model = kind, message, status, code, model

    def __str__(self) -> str:
        return self.message

    def to_dict(self) -> dict[str, Any]:
        return {"kind": self.kind, "error": self.message, "status": self.status, "code": self.code,
                "model": self.model}


def _failure(exc: BaseException) -> AssistFailure:
    if isinstance(exc, AssistFailure):
        return exc
    if isinstance(exc, BudgetExceeded):
        return AssistFailure("budget", exc.message, 429)
    if isinstance(exc, RegistrationRefused):
        return AssistFailure("refused", exc.message, 409)
    if isinstance(exc, ModelError):
        who = "/".join(p for p in (exc.alias, exc.provider, exc.model) if p) or None
        return AssistFailure("model", exc.message, 502, model=who)
    if isinstance(exc, AssistConfigError):
        return AssistFailure("config", exc.message, 503)
    if isinstance(exc, AssistError):
        return AssistFailure("model", exc.message, 502)
    if isinstance(exc, ValueError):
        return AssistFailure("input", str(exc), 400)
    engine_code = getattr(exc, "engine_code", None)  # the SDK's ApiError: "PRV-2050"
    refusal = exc if isinstance(exc, ServiceError) else _refusal(exc)  # type: ignore[arg-type]
    code = engine_code if isinstance(engine_code, str) else getattr(refusal, "code", None)
    return AssistFailure("engine", getattr(exc, "message", None) or str(refusal),
                         int(getattr(refusal, "status", 0) or 502),
                         code=code if isinstance(code, str) else None)


# ====================================================================== the service

@dataclasses.dataclass
class _Held:
    owner: str
    draft: Any
    at: float


class AssistService:
    """The assistant for the whole console process."""

    def __init__(self, engine: Any, *, config_path: str | os.PathLike[str] | None = None,
                 usage_path: str | os.PathLike[str] | None = None,
                 log_path: str | os.PathLike[str] | None = None,
                 watch_seconds: float = 1.0, follow: bool = True,
                 environ: dict[str, str] | None = None) -> None:
        self._engine = engine
        self._environ = os.environ if environ is None else environ
        path = pathlib.Path(config_path) if config_path else default_config_path(self._environ)
        self.store = FileConfigStore(path)
        self.ledger = UsageLedger(pathlib.Path(usage_path) if usage_path
                                  else path.parent / "assist-usage.json")
        self.log_path = pathlib.Path(log_path) if log_path else path.parent / "console-assist-log.jsonl"
        self._log_lock = threading.Lock()
        self._drafts: dict[str, _Held] = {}
        #: Requests, tokens, failures, fallbacks and latency per model and profile, for /metrics.
        self.metrics = AssistMetrics()
        self._drafts_lock = threading.Lock()
        #: Why the stored configuration could not be applied when the console started, if so.
        self.load_problem: str | None = None
        try:
            self.router = ModelRouter.from_store(self.store, ledger=self.ledger, environ=self._environ)
        except AssistConfigError as exc:
            # The console starts anyway, with no model, and the admin page says why -- a console
            # that refused to start over its assistant's file would be down for everything else.
            self.load_problem = exc.message
            logger.warning("the assistant's configuration at %s was not applied: %s", path, exc.message)
            self.router = ModelRouter(AssistConfig(), store=self.store, ledger=self.ledger,
                                      environ=self._environ)
        self.watch = self.router.follow(self.store, interval_s=watch_seconds, start=follow)

    def close(self) -> None:
        self.watch.stop()

    # ------------------------------------------------------------------ readiness

    def status(self, profile: str | None = None) -> dict[str, Any]:
        """Whether a surface asking for ``profile`` can be answered now: ``ready``, and when not,
        why -- ``none`` (no model configured), ``disabled`` (none enabled) or ``unrouted`` (no
        chain the profile could use)."""
        config = self.router.config
        enabled = [m for m in config.models if m.enabled]
        reason = "ok"
        if not config.models:
            reason = "none"
        elif not enabled:
            reason = "disabled"
        else:
            try:
                config.chain(profile)
            except AssistConfigError:
                reason = "unrouted"
        return {"ready": reason == "ok", "reason": reason, "models": len(config.models),
                "enabled": len(enabled), "version": config.version}

    # ------------------------------------------------------------------ who may administer

    def is_admin(self) -> bool:
        """Whether the engine says the signed-in person holds its ``admin`` role, asked now: the
        engine is the identity authority (ADR-052), and the roles copied into the session at
        sign-in only decide which links are shown."""
        try:
            me = self._engine.me()
        except Exception as exc:
            raise _refusal(exc) from exc
        return "admin" in [str(r) for r in (me or {}).get("roles") or []]

    # ------------------------------------------------------------------ administration: reading

    def admin_view(self) -> dict[str, Any]:
        admin = AssistAdmin(self.store, self.router, environ=self._environ)
        stored_problem = None
        try:
            stored = self.store.load()
        except AssistConfigError as exc:
            stored, stored_problem = self.router.config, exc.message
        in_force = self.router.config
        providers = []
        for provider in stored.providers:
            key = provider.api_key_env or provider.api_key_file
            providers.append({**provider.to_dict(), "key": key,
                              "keyKind": "env" if provider.api_key_env else "file" if provider.api_key_file else None,
                              "keySet": (provider.key_problem(self._environ) is None) if key else None,
                              "models": [m.id for m in stored.models if m.provider == provider.id]})
        models = admin.models() if stored_problem is None else []
        for row in models:
            row["timeout_s"] = stored.model(row["id"]).timeout_s
            row["active"] = [p["profile"] for p in row["profiles"] if p["position"] == 1]
        rejected = self.router.last_rejected
        return {
            "version": stored.version,
            "changedAt": stored.changed_at,
            "changedBy": stored.changed_by,
            "inForce": in_force.version,
            "path": str(self.store.path),
            "providerTypes": [p.to_dict() for p in admin.providers()],
            "providers": providers,
            "models": models,
            "profiles": [{"name": name, "chain": list(chain), "default": name == stored.default_profile}
                         for name, chain in sorted(stored.profiles.items())],
            "defaultProfile": stored.default_profile,
            "budgets": {"per_user_daily_tokens": stored.budgets.per_user_daily_tokens,
                        "per_request_max_tokens": stored.budgets.per_request_max_tokens},
            "problem": stored_problem or (rejected.message if rejected is not None else None)
            or self.load_problem,
            "status": self.status(),
        }

    # ------------------------------------------------------------------ administration: changing

    def change(self, actor: str, expected_version: int | None, action: str, *args: Any,
               **kwargs: Any) -> dict[str, Any]:
        """One administrator's change, through :class:`AssistAdmin`: refused as a conflict when
        the stored configuration is no longer the version the administrator's page showed, else
        validated, saved, applied to the router and logged. Answers the audit record."""
        admin = AssistAdmin(self.store, self.router, actor=actor, environ=self._environ)
        try:
            if expected_version is not None:
                current = self.store.load()
                if current.version != expected_version:
                    raise ConfigConflict(
                        f"the configuration is at version {current.version} (changed by "
                        f"{current.changed_by or 'someone'} at {current.changed_at or 'an unknown time'}), "
                        f"not version {expected_version} as this page showed it: reload and make "
                        f"the change again")
            record = getattr(admin, action)(*args, **kwargs)
        except ConfigConflict as exc:
            raise ServiceError(exc.message, status=409, code="conflict") from exc
        except AssistConfigError as exc:
            raise ServiceError(exc.message, status=400) from exc
        entry = {"kind": "change", **record.to_dict()}
        self._append(entry)
        logger.info("'%s' changed the assistant's configuration: %s %s (version %s)", actor,
                    record.action, record.target, record.version)
        return entry

    def test_model(self, model_id: str) -> dict[str, Any]:
        """A cheap ping of one model: its latency, or the normalised error."""
        admin = AssistAdmin(self.store, self.router, environ=self._environ)
        try:
            return admin.test_model(model_id).to_dict()
        except AssistConfigError as exc:
            return {"model_id": model_id, "ok": False, "latency_ms": 0.0,
                    "error": {"kind": exc.kind, "message": exc.message}}

    # ------------------------------------------------------------------ the log and the usage

    def _append(self, entry: dict[str, Any]) -> None:
        line = json.dumps(entry, sort_keys=True, default=str)
        with self._log_lock:
            try:
                self.log_path.parent.mkdir(parents=True, exist_ok=True)
                fresh = not self.log_path.exists()
                with self.log_path.open("a", encoding="utf-8") as handle:
                    handle.write(line + "\n")
                if fresh:
                    os.chmod(self.log_path, 0o600)
            except OSError as exc:  # the answer still stands; the log line is what is lost
                logger.warning("could not append to the assist log %s: %s", self.log_path, exc)

    def _entries(self) -> list[dict[str, Any]]:
        try:
            with self.log_path.open("rb") as handle:
                handle.seek(0, os.SEEK_END)
                size = handle.tell()
                handle.seek(max(0, size - LOG_TAIL_BYTES))
                text = handle.read().decode("utf-8", errors="replace")
        except OSError:
            return []
        lines = text.splitlines()
        if len(text.encode("utf-8")) >= LOG_TAIL_BYTES and lines:
            lines = lines[1:]  # the first may be cut in half
        entries = []
        for line in lines:
            try:
                value = json.loads(line)
            except ValueError:
                continue
            if isinstance(value, dict):
                entries.append(value)
        return entries

    def recent_changes(self, limit: int = 20) -> list[dict[str, Any]]:
        changes = [e for e in self._entries() if e.get("kind") == "change"]
        return list(reversed(changes))[:limit]

    def usage(self) -> dict[str, Any]:
        """Tokens per model and per person from the usage ledger (the days it keeps), and requests
        per model and per person from the console's assist log. Read-only."""
        try:
            document = json.loads(self.ledger.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            document = {}
        days = document.get("days") if isinstance(document, dict) else None
        days = days if isinstance(days, dict) else {}
        today = UsageLedger.today()
        by_model: dict[str, dict[str, int]] = {}
        by_user: dict[str, dict[str, int]] = {}

        def add(table: dict[str, dict[str, int]], key: str, field: str, amount: int) -> None:
            row = table.setdefault(key, {"tokens": 0, "today": 0, "requests": 0})
            row[field] += amount

        for day, people in days.items():
            if not isinstance(people, dict):
                continue
            for user, models in people.items():
                if not isinstance(models, dict):
                    continue
                for model_id, tokens in models.items():
                    try:
                        amount = int(tokens)
                    except (TypeError, ValueError):
                        continue
                    add(by_model, str(model_id), "tokens", amount)
                    add(by_user, str(user), "tokens", amount)
                    if day == today:
                        add(by_model, str(model_id), "today", amount)
                        add(by_user, str(user), "today", amount)
        for entry in self._entries():
            if entry.get("kind") != "request":
                continue
            if entry.get("model"):
                add(by_model, str(entry["model"]), "requests", 1)
            if entry.get("user"):
                add(by_user, str(entry["user"]), "requests", 1)
        order = lambda table: [{"name": k, **v} for k, v in sorted(table.items())]
        return {"days": sorted(days), "today": today, "byModel": order(by_model),
                "byUser": order(by_user), "ledger": str(self.ledger.path)}

    def prometheus(self, version: str = "") -> str:
        """The assistant's metrics in the Prometheus text format, with today's tokens from the
        usage ledger (every person together, by model). Read-only."""
        try:
            document = json.loads(self.ledger.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            document = {}
        return self.metrics.render(version=version, ledger_today=ledger_today(document, UsageLedger.today()))

    def _record(self, user: str, task: str, *, asked: str, answered_by: Any = None,
                verdict: str | None = None, registered: bool | None = None,
                failure: AssistFailure | None = None, tokens: int | None = None) -> None:
        by = dict(answered_by or {})
        usage = by.get("usage") or {}
        spent = tokens if tokens is not None else (
            int(usage.get("input_tokens", 0) or 0) + int(usage.get("output_tokens", 0) or 0))
        self._append({"kind": "request", "at": _now(), "user": user, "task": task,
                      "model": by.get("modelId") or (failure.model if failure else None),
                      "provider": by.get("provider"), "configVersion": by.get("configVersion"),
                      "tokens": spent, "asked": asked, "verdict": verdict, "registered": registered,
                      "failure": failure.kind if failure else None})

    # ------------------------------------------------------------------ the tasks

    def _assistant(self, user: str) -> Assistant:
        # Duck-typed stand-ins for ModelRouter and EngineApi: the slices the assistant calls.
        router: Any = _PersonRouter(self.router, user, self.metrics)
        api: Any = EngineApiAdapter(self._engine)
        return Assistant(router, api,
                         client=ClientAdapter(self._engine))

    def _ready(self, profile: str) -> None:
        state = self.status(profile)
        if not state["ready"]:
            raise AssistFailure("unconfigured", state["reason"], 503)

    def explain_query(self, user: str, *, name: str | None = None, sql: str | None = None) -> dict:
        self._ready(EXPLAIN)
        asked = _digest("explain-query", name, sql)
        try:
            answer = self._assistant(user).explain_query(sql or None, query_name=name or None)
        except Exception as exc:
            failure = _failure(exc)
            self._record(user, "explain-query", asked=asked, failure=failure)
            raise failure from exc
        result = answer.to_dict()
        self._record(user, "explain-query", asked=asked, answered_by=result["answeredBy"])
        return result

    def explain_refusal(self, user: str, code: str, sql: str | None = None) -> dict:
        self._ready(EXPLAIN)
        asked = _digest("explain-refusal", code, sql)
        try:
            answer = self._assistant(user).explain_refusal(code, sql or None)
        except Exception as exc:
            failure = _failure(exc)
            self._record(user, "explain-refusal", asked=asked, failure=failure)
            raise failure from exc
        result = answer.to_dict()
        verdict = result.get("rewriteVerdict") or {}
        self._record(user, "explain-refusal", asked=asked, answered_by=result["answeredBy"],
                     verdict=None if not verdict.get("checked") else
                     ("accepted" if verdict.get("valid") else "refused"))
        return result

    @staticmethod
    def with_answers(description: str, answers: list[tuple[str, str]]) -> str:
        """The description with the person's answers to the model's questions, for a re-draft."""
        given = [(q.strip(), a.strip()) for q, a in answers if a and a.strip()]
        if not given:
            return description.strip()
        lines = [description.strip(), "", "Answers to your questions:"]
        lines += [f"- {q} {a}" for q, a in given]
        return "\n".join(lines)

    def draft(self, user: str, description: str, *, name: str | None = None) -> dict:
        self._ready(DRAFT)
        asked = _digest("draft", description, name)
        try:
            draft = self._assistant(user).draft(description, name=name or None)
        except Exception as exc:
            failure = _failure(exc)
            self._record(user, "draft", asked=asked, failure=failure)
            raise failure from exc
        held = self._hold(user, draft)
        result = draft.to_dict()
        result.update({"id": held, "registrable": draft.accepted})
        self._record(user, "draft", asked=asked, answered_by=result["answeredBy"],
                     verdict=draft.status, tokens=draft.tokens)
        return result

    def _hold(self, user: str, draft: Any) -> str:
        now = time.monotonic()
        with self._drafts_lock:
            for key in [k for k, v in self._drafts.items() if now - v.at > DRAFT_TTL_SECONDS]:
                del self._drafts[key]
            while len(self._drafts) >= MAX_DRAFTS:
                del self._drafts[min(self._drafts, key=lambda k: self._drafts[k].at)]
            key = secrets.token_urlsafe(12)
            self._drafts[key] = _Held(user, draft, now)
        return key

    def held(self, user: str, draft_id: str) -> Any:
        with self._drafts_lock:
            found = self._drafts.get(draft_id or "")
        if found is None or found.owner != user or time.monotonic() - found.at > DRAFT_TTL_SECONDS:
            raise AssistFailure("refused", "not registered: that draft is not one this console holds "
                                "for you any more; draft it again", 409)
        return found.draft

    def register(self, user: str, draft_id: str, *, confirmed: bool, name: str | None = None) -> dict:
        """Registers a held draft -- only the person's own, only confirmed, only one the engine
        accepted -- through the ordinary registration, under their own engine session."""
        draft = self.held(user, draft_id)
        asked = _digest("register", draft_id, name)
        try:
            answer = self._assistant(user).register(draft, confirmed=confirmed, name=name or None)
        except Exception as exc:
            failure = _failure(exc)
            self._record(user, "register", asked=asked, verdict=draft.status, registered=False,
                         failure=failure, tokens=0)
            raise failure from exc
        self._record(user, "register", asked=asked, answered_by=draft.answered_by,
                     verdict=draft.status, registered=True, tokens=0)
        with self._drafts_lock:
            self._drafts.pop(draft_id, None)
        return {**answer, "sql": draft.sql, "keys": list(draft.keys)}


__all__ = ["AssistFailure", "AssistService", "ClientAdapter", "EngineApiAdapter"]
