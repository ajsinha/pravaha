"""``ModelRouter``: which model answers, what happens when it cannot, and what it may spend.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Configuration, not code, decides (ADR-058 §1):

* **Profiles to chains.** A task asks for a profile (``explain``, ``draft``); its chain is tried in
  order. Falling through happens on :class:`ModelUnavailable` and :class:`ModelRateLimited` only.
  :class:`ModelRefused` and :class:`ModelOutputError` stop the chain and are reported with the
  model that said them -- a refusal is an answer, and asking a second model until one agrees is
  not something the assistant does quietly.
* **Budgets.** ``per_request_max_tokens`` caps one request -- its estimated input plus the most it
  may answer; ``per_user_daily_tokens`` caps a user's day, counted from each answer's ``usage`` in
  a small local ledger (``assist-usage.json`` beside the configuration, mode 0600). A request that
  would exceed either is refused *before* it is sent. The ledger is a per-machine convenience cap,
  not an enforcement point: a person with a shell can delete it. The console's audit trail
  (phase 3) is where spend is accounted.
* **Runtime reconfiguration.** The router holds one immutable :class:`AssistConfig` snapshot.
  :meth:`ModelRouter.reconfigure` validates a new one completely -- unknown providers, duplicate
  ids, chains naming unknown or disabled models, a key variable this process lacks -- and only
  then swaps it in, under a lock. A request reads the snapshot once when it starts, so requests in
  flight finish on the old configuration and new ones use the new; none sees half of each.
  :meth:`ModelRouter.follow` applies whatever another process stores (the console's admin screen,
  ``pravaha assist use``); a document that fails validation is kept out and recorded in
  :attr:`ModelRouter.last_rejected`.
"""

from __future__ import annotations

import dataclasses
import datetime
import getpass
import json
import os
import pathlib
import tempfile
import threading
import time
from typing import Any, Mapping, Optional, Sequence

from pravaha.assist.config import AssistConfig, ModelConfig, _thaw
from pravaha.assist.errors import (
    AssistConfigError,
    BudgetExceeded,
    ModelError,
    ModelOutputError,
    ModelRateLimited,
    ModelUnavailable,
)
from pravaha.assist.provider import (
    ChatRequest,
    ChatResponse,
    Message,
    ModelProvider,
    ProviderSettings,
    Usage,
)
from pravaha.assist.registry import create_provider
from pravaha.assist.store import AssistConfigStore, ConfigWatch, config_dir

#: The answer length asked for when a request names none.
DEFAULT_MAX_TOKENS = 2048
#: How long the ledger keeps a day.
LEDGER_DAYS = 7


def estimate_tokens(request: ChatRequest) -> int:
    """A deliberately rough input estimate: four characters a token, rounded up. Budgets are
    checked against it before sending and charged with the provider's own count after."""
    characters = len(request.system or "") + sum(len(m.content) for m in request.messages)
    return characters // 4 + 1


# ---------------------------------------------------------------------------------- results


@dataclasses.dataclass(frozen=True)
class Attempt:
    """One model the router tried and moved past, and why."""

    model_id: str
    provider: str
    model: str
    kind: str
    message: str
    retry_after: Optional[float] = None

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)


@dataclasses.dataclass(frozen=True)
class RoutedResponse:
    """An answer, and which configured model gave it."""

    response: ChatResponse
    #: The configured model id that answered (``claude-main``), its provider id and type.
    model_id: str
    provider_id: str
    provider: str
    #: The models tried before it, in order.
    attempts: "tuple[Attempt, ...]" = ()
    #: The configuration version the request ran under.
    config_version: int = 0

    @property
    def text(self) -> str:
        return self.response.text

    @property
    def parsed(self) -> Any:
        return self.response.parsed

    @property
    def usage(self) -> Usage:
        return self.response.usage

    def answered_by(self) -> dict[str, Any]:
        return {
            "modelId": self.model_id,
            "provider": self.provider,
            "providerId": self.provider_id,
            "model": self.response.model,
            "configVersion": self.config_version,
            "usage": dataclasses.asdict(self.response.usage),
            "latencyMs": self.response.latency_ms,
            "attempts": [a.to_dict() for a in self.attempts],
        }


@dataclasses.dataclass(frozen=True)
class CheckResult:
    """Whether one configured model answered a cheap ping."""

    model_id: str
    provider: str
    model: str
    ok: bool
    latency_ms: float = 0.0
    error: Optional[dict[str, Any]] = None

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)


# ---------------------------------------------------------------------------------- the ledger


class UsageLedger:
    """Tokens spent per user per UTC day, per model, in one small JSON file (mode 0600)."""

    def __init__(self, path: "str | os.PathLike[str] | None" = None) -> None:
        self.path = pathlib.Path(path) if path is not None else config_dir() / "assist-usage.json"
        self._lock = threading.Lock()

    @staticmethod
    def today() -> str:
        return datetime.datetime.now(datetime.timezone.utc).date().isoformat()

    def _read(self) -> dict[str, Any]:
        try:
            document = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {"days": {}}
        return document if isinstance(document, dict) and isinstance(document.get("days"), dict) \
            else {"days": {}}

    def used(self, user: str, day: Optional[str] = None) -> int:
        """Tokens ``user`` has spent on ``day`` (today), across every model."""
        with self._lock:
            spent = self._read()["days"].get(day or self.today(), {}).get(user, {})
        return sum(int(v) for v in spent.values()) if isinstance(spent, dict) else 0

    def record(self, user: str, model_id: str, tokens: int) -> None:
        if tokens <= 0:
            return
        with self._lock:
            document = self._read()
            days: dict[str, Any] = document["days"]
            day = days.setdefault(self.today(), {})
            mine = day.setdefault(user, {})
            mine[model_id] = int(mine.get(model_id, 0)) + int(tokens)
            for old in sorted(days)[:-LEDGER_DAYS]:
                del days[old]
            self.path.parent.mkdir(parents=True, exist_ok=True)
            descriptor, temporary = tempfile.mkstemp(prefix=".assist-usage.", suffix=".tmp",
                                                     dir=str(self.path.parent))
            try:
                with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                    json.dump(document, handle, indent=1, sort_keys=True)
                os.replace(temporary, self.path)
                os.chmod(self.path, 0o600)
            except BaseException:
                try:
                    os.unlink(temporary)
                except OSError:
                    pass
                raise


# ---------------------------------------------------------------------------------- the router


class _State:
    """One applied snapshot and the providers built from it. Replaced whole, never edited."""

    def __init__(self, config: AssistConfig) -> None:
        self.config = config
        self.providers: dict[str, ModelProvider] = {}
        self.lock = threading.Lock()


class ModelRouter:
    """Routes a request through a profile's chain of configured models.

    ``providers`` maps a model id to a provider instance already built -- for tests, and for an
    application that constructs its own. ``environ`` is where key variables are read (the
    process's environment by default, read at the moment a provider is built).
    """

    def __init__(
        self,
        config: Optional[AssistConfig] = None,
        *,
        store: Optional[AssistConfigStore] = None,
        environ: Optional[Mapping[str, str]] = None,
        ledger: Optional[UsageLedger] = None,
        user: Optional[str] = None,
        providers: Optional[Mapping[str, ModelProvider]] = None,
    ) -> None:
        self._environ = os.environ if environ is None else environ
        if config is None:
            config = store.load() if store is not None else AssistConfig()
        self._store = store
        self._prebuilt = dict(providers or {})
        self._swap = threading.Lock()
        self._state = _State(self._check(config))
        self.ledger = ledger if ledger is not None else UsageLedger()
        self.user = user
        #: The last configuration :meth:`follow` refused to apply, and why.
        self.last_rejected: Optional[AssistConfigError] = None

    @classmethod
    def from_store(cls, store: AssistConfigStore, **kwargs: Any) -> "ModelRouter":
        return cls(store.load(), store=store, **kwargs)

    # ------------------------------------------------------------------ configuration

    @property
    def config(self) -> AssistConfig:
        """The snapshot in force now."""
        return self._state.config

    def _check(self, config: AssistConfig) -> AssistConfig:
        found = config.problems(self._environ, prebuilt=self._prebuilt)
        if found:
            raise AssistConfigError("the assistant configuration is not valid: " + "; ".join(found))
        return config

    def reconfigure(self, config: AssistConfig) -> AssistConfig:
        """Validates ``config`` completely, then makes it the one in force; answers the previous
        snapshot. On any problem raises :class:`AssistConfigError` and changes nothing."""
        checked = self._check(config)
        with self._swap:
            previous = self._state.config
            self._state = _State(checked)
        return previous

    def follow(self, store: Optional[AssistConfigStore] = None, *, interval_s: float = 1.0,
               start: bool = True) -> ConfigWatch:
        """Applies every snapshot ``store`` (by default the one this router was built from) sees
        stored from now on. Answers the watch; ``start=False`` leaves polling to the caller."""
        source = store or self._store
        if source is None:
            raise AssistConfigError("this router was built without a store to follow")

        def apply(config: AssistConfig) -> None:
            if config.version and config.version == self.config.version and config == self.config:
                return
            try:
                self.reconfigure(config)
                self.last_rejected = None
            except AssistConfigError as exc:
                self.last_rejected = exc

        def refused(exc: AssistConfigError) -> None:
            self.last_rejected = exc

        watch = source.watch(apply, interval_s=interval_s, on_error=refused)
        return watch.start() if start else watch

    # ------------------------------------------------------------------ providers

    def _settings(self, config: AssistConfig, model: ModelConfig) -> "tuple[str, ProviderSettings]":
        provider = config.provider(model.provider)
        options = {**_thaw(provider.options), **_thaw(model.options)}
        debug = config.debug or self._environ.get("PRAVAHA_ASSIST_DEBUG", "") in ("1", "true")
        return provider.type, ProviderSettings(
            endpoint=provider.endpoint,
            api_key=provider.resolve_key(self._environ),
            options=options,
            timeout_s=model.timeout_s or provider.timeout_s,
            debug=debug,
            id=provider.id,
        )

    def _provider(self, state: _State, model: ModelConfig) -> ModelProvider:
        if model.id in self._prebuilt:
            return self._prebuilt[model.id]
        with state.lock:
            built = state.providers.get(model.id)
            if built is None:
                kind, settings = self._settings(state.config, model)
                built = create_provider(kind, settings)
                state.providers[model.id] = built
            return built

    def provider(self, model_id: str) -> ModelProvider:
        """The provider instance for a configured model, built on first use."""
        state = self._state
        return self._provider(state, state.config.model(model_id))

    # ------------------------------------------------------------------ asking

    def _user(self, request: ChatRequest, user: Optional[str]) -> str:
        chosen = user or self.user or request.metadata.get("user")
        if chosen:
            return str(chosen)
        try:
            return getpass.getuser()
        except Exception:  # pragma: no cover - no login name at all
            return "unknown"

    def _budget(self, config: AssistConfig, request: ChatRequest, user: str) -> None:
        answer_cap = int(request.max_tokens or DEFAULT_MAX_TOKENS)
        wanted = estimate_tokens(request) + answer_cap
        cap = config.budgets.per_request_max_tokens
        if cap is not None and wanted > cap:
            raise BudgetExceeded(
                f"this request could use up to {wanted} tokens (about {wanted - answer_cap} in, "
                f"{answer_cap} out); the per-request budget is {cap}"
            )
        daily = config.budgets.per_user_daily_tokens
        if daily is not None:
            spent = self.ledger.used(user)
            if spent + wanted > daily:
                raise BudgetExceeded(
                    f"{user} has used {spent} of {daily} tokens today; this request could use "
                    f"up to {wanted} more"
                )

    def complete(
        self,
        request: ChatRequest,
        *,
        profile: Optional[str] = None,
        model: Optional[str] = None,
        user: Optional[str] = None,
    ) -> RoutedResponse:
        """Asks the first model in ``profile``'s chain that can answer -- or only ``model``, a
        configured model id. ``request.model`` is replaced by each configured model's own."""
        state = self._state  # read once: this request runs on this snapshot to the end
        config = state.config
        if model is not None:
            chosen = config.model(model)
            if not chosen.enabled:
                raise AssistConfigError(f"model {model!r} is disabled")
            chain: Sequence[str] = (model,)
        else:
            chain = config.chain(profile)
        who = self._user(request, user)
        if request.max_tokens is None:
            request = dataclasses.replace(request, max_tokens=DEFAULT_MAX_TOKENS)
        self._budget(config, request, who)
        attempts: list[Attempt] = []
        failures: list[AssistConfigError | ModelError] = []
        for model_id in chain:
            entry = config.model(model_id)
            kind = config.provider(entry.provider).type
            try:
                provider = self._provider(state, entry)
            except AssistConfigError as exc:
                attempts.append(Attempt(model_id, kind, entry.model, exc.kind, exc.message))
                failures.append(exc)
                continue
            capped = request.max_tokens
            limit = provider.capabilities(entry.model).max_output_tokens
            if limit and capped and capped > limit:
                capped = limit
            asked = dataclasses.replace(
                request,
                model=entry.model,
                max_tokens=capped,
                timeout_s=entry.timeout_s or config.provider(entry.provider).timeout_s,
                metadata={**request.metadata, "user": who},
            )
            try:
                answer = provider.complete(asked)
            except (ModelUnavailable, ModelRateLimited) as exc:
                self._annotate(exc, model_id, kind, entry.model)
                attempts.append(Attempt(model_id, kind, entry.model, exc.kind, exc.message,
                                        getattr(exc, "retry_after", None)))
                failures.append(exc)
                continue
            except ModelError as exc:
                self._annotate(exc, model_id, kind, entry.model)
                exc.attempts = tuple(attempts)
                if isinstance(exc, ModelOutputError) and isinstance(exc.usage, Usage):
                    self.ledger.record(who, model_id, exc.usage.total_tokens)
                raise
            except AssistConfigError:
                raise
            except Exception as exc:  # a third-party provider that did not normalise
                wrapped = ModelUnavailable(f"{type(exc).__name__}: {exc}", retryable=False)
                self._annotate(wrapped, model_id, kind, entry.model)
                attempts.append(Attempt(model_id, kind, entry.model, wrapped.kind, wrapped.message))
                failures.append(wrapped)
                continue
            self.ledger.record(who, model_id, answer.usage.total_tokens)
            return RoutedResponse(answer, model_id, entry.provider, kind, tuple(attempts),
                                  config.version)
        if failures and all(isinstance(f, AssistConfigError) for f in failures):
            raise AssistConfigError(
                "no model in the chain could be used: " + "; ".join(a.message for a in attempts)
            )
        last = failures[-1]
        assert isinstance(last, ModelError)
        last.attempts = tuple(attempts[:-1])
        if len(chain) > 1:
            last.message = f"every model in the chain failed; the last: {last.message}"
            last.args = (last.message,)
        raise last

    @staticmethod
    def _annotate(exc: ModelError, model_id: str, kind: str, model: str) -> None:
        exc.alias = exc.alias or model_id
        exc.provider = exc.provider or kind
        exc.model = exc.model or model

    def ask(self, prompt: str, *, system: Optional[str] = None, **kwargs: Any) -> RoutedResponse:
        """One user turn, for a quick question: ``router.ask("...", profile="explain")``."""
        return self.complete(ChatRequest(messages=[Message("user", prompt)], system=system),
                             **kwargs)

    # ------------------------------------------------------------------ administration

    def check(self, model_ids: Optional[Sequence[str]] = None) -> "list[CheckResult]":
        """Pings each configured model (every enabled one, or ``model_ids``) as cheaply as its
        provider allows -- a models endpoint where there is one, so no tokens are spent."""
        state = self._state
        config = state.config
        chosen = [config.model(m) for m in model_ids] if model_ids else \
            [m for m in config.models if m.enabled]
        results: list[CheckResult] = []
        for entry in chosen:
            kind = config.provider(entry.provider).type
            started = time.monotonic()
            try:
                provider = self._provider(state, entry)
                ping = getattr(provider, "ping", None)
                if callable(ping):
                    ping(entry.model)
                else:
                    provider.complete(ChatRequest(messages=[Message("user", "Reply with OK.")],
                                                  model=entry.model, max_tokens=5))
            except (ModelError, AssistConfigError) as exc:
                if isinstance(exc, ModelError):
                    self._annotate(exc, entry.id, kind, entry.model)
                results.append(CheckResult(entry.id, kind, entry.model, False,
                                           round((time.monotonic() - started) * 1000, 3),
                                           exc.to_dict()))
                continue
            except Exception as exc:  # a third-party provider that did not normalise
                results.append(CheckResult(entry.id, kind, entry.model, False, 0.0,
                                           {"kind": type(exc).__name__, "message": str(exc)}))
                continue
            results.append(CheckResult(entry.id, kind, entry.model, True,
                                       round((time.monotonic() - started) * 1000, 3)))
        return results


__all__ = [
    "Attempt",
    "CheckResult",
    "DEFAULT_MAX_TOKENS",
    "ModelRouter",
    "RoutedResponse",
    "UsageLedger",
    "estimate_tokens",
]
