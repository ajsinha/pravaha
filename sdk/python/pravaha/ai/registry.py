"""The provider registry: providers and models by logical name, how each is doing (health from
configuration and the calls seen, never a network call), reload, and a retry schedule for providers that
could not be built. Mirrors the engine's ``LlmRegistry``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import dataclasses
import logging
import threading
import time
from typing import Any, Callable, Dict, List, Mapping, Optional, Tuple

from pravaha.ai.protocols import ChatModel, EmbeddingModel, LlmProvider
from pravaha.ai.types import LlmError, ModelInfo, ProviderConfig

LOG = logging.getLogger("pravaha.ai")

Source = Callable[[], Tuple[List[ProviderConfig], Dict[str, str]]]


@dataclasses.dataclass
class RetryStatus:
    attempts: int
    next_retry: float
    last_error: str
    permanent: bool

    def as_dict(self) -> Dict[str, Any]:
        return dataclasses.asdict(self)


class RetryBoard:
    """Providers that could not be built and when each is tried again: the wait doubles from ``initial`` to
    ``maximum``. A failure that will not mend by itself (a configuration mistake) waits for its file to change."""

    def __init__(self, initial: float = 5.0, maximum: float = 300.0, clock: Callable[[], float] = time.time) -> None:
        self.initial, self.maximum, self._clock = initial, maximum, clock
        self._failing: Dict[str, RetryStatus] = {}
        self._lock = threading.Lock()

    def failed(self, name: str, error: str, permanent: bool) -> None:
        with self._lock:
            prev = self._failing.get(name)
            n = prev.attempts + 1 if prev else 1
            wait = min(self.maximum, self.initial * (2 ** min(20, n - 1)))
            self._failing[name] = RetryStatus(n, float("inf") if permanent else self._clock() + wait, error, permanent)

    def succeeded(self, name: str) -> None:
        with self._lock:
            self._failing.pop(name, None)

    forget = succeeded

    def failing(self, name: str) -> bool:
        return name in self._failing

    def due(self) -> List[str]:
        now = self._clock()
        with self._lock:
            return sorted(k for k, s in self._failing.items() if s.next_retry <= now)

    def statuses(self) -> Dict[str, RetryStatus]:
        with self._lock:
            return dict(sorted(self._failing.items()))

    def retry_all_now(self) -> int:
        now = self._clock()
        with self._lock:
            for k, s in self._failing.items():
                self._failing[k] = dataclasses.replace(s, next_retry=now)
            return len(self._failing)


@dataclasses.dataclass
class State:
    calls: int = 0
    failures: int = 0
    last_ok: Optional[float] = None
    last_failure: Optional[float] = None
    last_error: str = ""
    last_was_failure: bool = False


@dataclasses.dataclass
class Entry:
    config: ProviderConfig
    provider: Optional[LlmProvider]
    error: Optional[str]
    state: State = dataclasses.field(default_factory=State)

    @property
    def usable(self) -> bool:
        return self.provider is not None and self.config.enabled

    def built(self) -> LlmProvider:
        if self.provider is None:
            raise LlmError("config", f"provider {self.config.name} is not built")
        return self.provider


class LlmRegistry:
    def __init__(self, factories: Mapping[str, Any], source: Source, runtime: Any = None, default_model: str = "",
                 retry: Optional[RetryBoard] = None) -> None:
        """``source()`` returns ``(configs, file_errors)``; ``runtime`` is handed to provider factories (the
        ``server`` provider)."""
        self._factories, self._source, self._runtime = factories, source, runtime
        self.retry = retry or RetryBoard()
        self._default = (default_model or "").strip()
        self._entries: Dict[str, Entry] = {}
        self.file_errors: Dict[str, str] = {}
        self.loaded_at = 0.0
        self._lock = threading.Lock()
        self._summary = ""

    def reload(self) -> None:
        with self._lock:
            self._reload()

    def _reload(self) -> None:
        configs, errors = self._source()
        entries: Dict[str, Entry] = {}
        due = self.retry.due()
        for c in configs:
            prev = self._entries.get(c.name)
            state = prev.state if prev else State()
            if not c.enabled:
                self.retry.forget(c.name)
                entries[c.name] = Entry(c, None, None, state)
                continue
            if prev and prev.error and prev.config == c and self.retry.failing(c.name) and c.name not in due:
                entries[c.name] = prev  # refused before, same file, not yet time to try again
                continue
            if prev and prev.config != c:
                self.retry.forget(c.name)  # the file changed: a fresh start
            if prev and prev.provider is not None and prev.config == c:
                entries[c.name] = prev  # the same file: keep the provider
                continue
            f = self._factories.get(c.type.lower())
            if f is None:
                why = f"no provider type '{c.type}' is installed (have: {sorted(self._factories)})"
                self.retry.failed(c.name, why, True)
                entries[c.name] = Entry(c, None, why, state)
                continue
            try:
                entries[c.name] = Entry(c, f(c, self._runtime), None, state)
                self.retry.succeeded(c.name)
            except LlmError as e:
                self.retry.failed(c.name, e.message, e.kind == "config")
                entries[c.name] = Entry(c, None, e.message, state)
        for k in list(self.retry.statuses()):
            if k not in entries:
                self.retry.forget(k)
        old = self._entries
        self._entries, self.file_errors, self.loaded_at = entries, dict(errors), time.time()
        for name, gone in old.items():
            if gone.provider is not None and entries.get(name) is not gone:
                try:
                    gone.provider.close()
                except Exception:  # closing an old provider must not stop a reload
                    LOG.debug("closing provider %s failed", name)
        summary = f"{len(entries)} provider(s), {sum(1 for e in entries.values() if e.usable)} usable, {len(errors)} file error(s)"
        if summary != self._summary:
            LOG.info("ai registry: %s", summary)
            self._summary = summary

    def types(self) -> List[str]:
        return sorted(self._factories)

    def entries(self) -> List[Entry]:
        return sorted(self._entries.values(), key=lambda e: e.config.name)

    def provider(self, name: str) -> Optional[Entry]:
        return self._entries.get(name)

    def models(self) -> List[ModelInfo]:
        return [m for e in self.entries() if e.usable for m in e.built().models()]

    def chat(self, ref: Optional[str]) -> Tuple[Entry, ChatModel]:
        """``(entry, chat model)`` for ``provider/model``, a model name only one provider has, or (None) the
        default, then the only chat model."""
        r = (ref or self._default or "").strip()
        hits: List[Tuple[Entry, ChatModel]] = []
        if not r:
            for e in self.entries():
                if e.usable:
                    for m in e.built().models():
                        if not m.embedding and (not e.config.default_model or e.config.default_model == m.name):
                            cm = e.built().chat_model(m.name)
                            if cm is not None:
                                hits.append((e, cm))
            if len(hits) == 1:
                return hits[0]
            raise LlmError("not_found", "no chat model is configured" if not hits
                           else "several chat models are configured; name one (provider/model) or set a default-model")
        prov, _, name = r.rpartition("/") if "/" in r else ("", "", r)
        for e in self.entries():
            if e.usable and (not prov or e.config.name == prov):
                found = e.built().chat_model(name)
                if found is not None:
                    hits.append((e, found))
        if not hits:
            if self.embedding(r) is not None:
                raise LlmError("unsupported", f"model '{r}' is an embedding model; it cannot chat")
            raise LlmError("not_found", f"no chat model '{r}' is configured")
        if len(hits) > 1:
            raise LlmError("not_found", f"model name '{r}' is in several providers; use provider/model")
        return hits[0]

    def embedding(self, ref: Optional[str]) -> Optional[EmbeddingModel]:
        if not ref:
            return None
        prov, _, name = ref.rpartition("/") if "/" in ref else ("", "", ref)
        for e in self.entries():
            if e.usable and (not prov or e.config.name == prov):
                m = e.built().embedding_model(name)
                if m is not None:
                    return m
        return None

    def record(self, entry: Entry, ok: bool, error: Optional[str] = None) -> None:
        s = entry.state
        s.calls += 1
        if ok:
            s.last_ok, s.last_was_failure = time.time(), False
        else:
            s.failures += 1
            s.last_failure, s.last_error, s.last_was_failure = time.time(), error or "", True

    def health(self, e: Entry) -> Dict[str, Any]:
        """OFF, ERROR, UNKNOWN, UP or DOWN, from the file and the calls seen; never a network call."""
        s = e.state
        if not e.config.enabled:
            st, detail = "OFF", "enabled: false in the provider file"
        elif e.error:
            st, detail = "ERROR", e.error
        elif s.calls == 0:
            st, detail = "UNKNOWN", "configured; no call yet"
        else:
            st, detail = ("DOWN", s.last_error) if s.last_was_failure else ("UP", "last call succeeded")
        return {"status": st, "detail": detail, "calls": s.calls, "failures": s.failures, "lastOk": s.last_ok,
                "lastFailure": s.last_failure}

    def close(self) -> None:
        with self._lock:
            entries, self._entries = self._entries, {}
        for e in entries.values():
            if e.provider is not None:
                e.provider.close()


__all__ = ["Entry", "LlmRegistry", "RetryBoard", "RetryStatus", "State"]
