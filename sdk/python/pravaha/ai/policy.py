"""Policy hooks around every model call: masking, an audit record (never the prompt or the answer),
per-user and per-feature limits, features per role. Mirrors the engine's ``DefaultLlmPolicy``; when the
``server`` provider is used the engine applies its own policy as well.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import dataclasses
import re
import threading
import time
from collections import defaultdict, deque
from typing import Any, Callable, Deque, Dict, Iterable, List, Optional, Tuple

from pravaha.ai.types import CallContext, ChatMessage, ChatRequest, ChatResponse, LlmError, ModelInfo

MASK = "\u2022\u2022\u2022"


class PromptMasker:
    """Hides the value after any field named in ``fields`` (``name: v``, ``name=v``, ``"name":"v"``) and
    anything matching ``patterns``."""

    def __init__(self, fields: Iterable[str] = (), patterns: Iterable[str] = ()) -> None:
        self._fields = [re.compile(r'(?i)("?\b' + re.escape(f.strip()) + r'\b"?\s*[:=]\s*)("[^"]*"|[^\s,;}\]]+)')
                        for f in fields if f and f.strip()]
        self._plain = [re.compile(p) for p in patterns if p and p.strip()]

    @property
    def active(self) -> bool:
        return bool(self._fields or self._plain)

    def mask(self, text: str) -> str:
        for p in self._fields:
            text = p.sub(lambda m: m.group(1) + (f'"{MASK}"' if m.group(2).startswith('"') else MASK), text)
        for p in self._plain:
            text = p.sub(MASK, text)
        return text

    def mask_request(self, r: ChatRequest) -> ChatRequest:
        if not self.active:
            return r
        return dataclasses.replace(r, system=self.mask(r.system),
                                   messages=tuple(ChatMessage(m.role, self.mask(m.content)) for m in r.messages))


@dataclasses.dataclass
class PolicySettings:
    mask_redacted: bool = True
    audit: bool = True
    per_user_per_minute: int = 0
    per_user_per_day: int = 0
    cost_per_user_per_day: float = 0.0
    feature_roles: Dict[str, List[str]] = dataclasses.field(default_factory=dict)  # feature -> roles allowed
    feature_per_user_per_minute: Dict[str, int] = dataclasses.field(default_factory=dict)


AuditSink = Callable[[Dict[str, Any]], None]


class DefaultPolicy:
    def __init__(self, settings: Optional[PolicySettings] = None, masker: Optional[PromptMasker] = None,
                 audit: Optional[AuditSink] = None, clock: Callable[[], float] = time.time) -> None:
        self.s = settings or PolicySettings()
        self.masker = masker if (masker and self.s.mask_redacted) else PromptMasker()
        self._audit, self._clock = audit, clock
        self._minute: Dict[Tuple[str, str], Deque[float]] = defaultdict(deque)
        self._day: Dict[str, Tuple[int, int]] = {}
        self._spend: Dict[str, Tuple[int, float]] = {}
        self._lock = threading.Lock()

    def before(self, ctx: CallContext, model: ModelInfo, request: ChatRequest) -> ChatRequest:
        roles = self.s.feature_roles.get(ctx.feature)
        if roles and not set(roles) & set(ctx.roles):
            raise LlmError("policy", f"feature '{ctx.feature}' is not open to your roles")
        now = self._clock()
        today = int(now // 86400)
        with self._lock:
            per_min = self.s.feature_per_user_per_minute.get(ctx.feature, self.s.per_user_per_minute)
            if per_min > 0:
                q = self._minute[(ctx.user, ctx.feature)]
                while q and now - q[0] >= 60:
                    q.popleft()
                if len(q) >= per_min:
                    raise LlmError("rate_limited", f"too many calls; try again in {max(1, int(60 - (now - q[0])))} seconds")
                q.append(now)
            if self.s.per_user_per_day > 0:
                d, n = self._day.get(ctx.user, (today, 0))
                n = n + 1 if d == today else 1
                self._day[ctx.user] = (today, n)
                if n > self.s.per_user_per_day:
                    raise LlmError("rate_limited", f"the daily limit of {self.s.per_user_per_day} calls is used up")
            if self.s.cost_per_user_per_day > 0:
                d, c = self._spend.get(ctx.user, (today, 0.0))
                if d == today and c >= self.s.cost_per_user_per_day:
                    raise LlmError("rate_limited", "the daily cost limit is used up")
        return self.masker.mask_request(request)

    def after(self, ctx: CallContext, model: ModelInfo, request: ChatRequest, response: Optional[ChatResponse],
              error: Optional[Exception], millis: int) -> None:
        cost = response.usage.cost if response else 0.0
        if cost > 0:
            today = int(self._clock() // 86400)
            with self._lock:
                d, c = self._spend.get(ctx.user, (today, 0.0))
                self._spend[ctx.user] = (today, (c if d == today else 0.0) + cost)
        if self.s.audit and self._audit:
            u = response.usage if response else None
            try:
                self._audit({"user": ctx.user, "feature": ctx.feature, "provider": model.provider, "model": model.name,
                             "promptChars": request.prompt_chars(), "answerChars": len(response.text) if response else 0,
                             "promptTokens": u.prompt_tokens if u else 0, "completionTokens": u.completion_tokens if u else 0,
                             "cost": cost, "outcome": "ok" if error is None else getattr(error, "kind", "failed"),
                             "millis": millis})
            except Exception:  # an audit sink must never fail a call
                pass


class NoPolicy:
    def before(self, ctx: CallContext, model: ModelInfo, request: ChatRequest) -> ChatRequest:
        return request

    def after(self, ctx: CallContext, model: ModelInfo, request: ChatRequest, response: Optional[ChatResponse],
              error: Optional[Exception], millis: int) -> None:
        return None


__all__ = ["DefaultPolicy", "MASK", "NoPolicy", "PolicySettings", "PromptMasker"]
