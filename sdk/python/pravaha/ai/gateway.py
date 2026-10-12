"""The one door callers use to call a model: refuses every call while the layer is off, resolves by
logical name, runs the policy before and after, enforces the timeout, keeps the health bookkeeping.
Mirrors the engine's ``LlmGateway``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import concurrent.futures
import contextvars
import dataclasses
import time
from typing import Callable, Optional

from pravaha.ai.policy import NoPolicy
from pravaha.ai.protocols import Policy
from pravaha.ai.registry import LlmRegistry
from pravaha.ai.types import CHAT, JSON_MODE, TOOL_CALLS, CallContext, ChatRequest, ChatResponse, LlmError

_POOL = concurrent.futures.ThreadPoolExecutor(max_workers=8, thread_name_prefix="pravaha-ai")


class LlmGateway:
    def __init__(self, registry: LlmRegistry, policy: Optional[Policy] = None,
                 enabled: Callable[[], bool] = lambda: True) -> None:
        self.registry = registry
        self.policy: Policy = policy or NoPolicy()
        self._enabled = enabled

    @property
    def enabled(self) -> bool:
        return bool(self._enabled())

    def require_enabled(self) -> None:
        if not self._enabled():
            raise LlmError.disabled()

    def chat(self, ctx: CallContext, request: ChatRequest) -> ChatResponse:
        self.require_enabled()
        entry, model = self.registry.chat(request.model)
        info = model.info
        if CHAT not in info.capabilities:
            raise LlmError("unsupported", f"model {info.ref} cannot chat")
        try:
            masked = self.policy.before(ctx, info, request)
        except LlmError as e:
            self.policy.after(ctx, info, request, None, e, 0)  # a refused call is a call: it has its audit row
            raise
        if masked.json_mode and JSON_MODE not in info.capabilities:
            raise LlmError("unsupported", f"model {info.ref} has no json-mode")
        if masked.tools and TOOL_CALLS not in info.capabilities:
            raise LlmError("unsupported", f"model {info.ref} has no tool-calls")
        masked = dataclasses.replace(masked, context={**masked.context, "ident": ctx.ident, "feature": ctx.feature})
        t0 = time.monotonic()
        try:
            resp = self._call(model.chat, masked)
        except LlmError as e:
            self.registry.record(entry, False, e.message)
            self.policy.after(ctx, info, masked, None, e, int((time.monotonic() - t0) * 1000))
            raise
        self.registry.record(entry, True)
        self.policy.after(ctx, info, masked, resp, None, int((time.monotonic() - t0) * 1000))
        return resp

    @staticmethod
    def _call(fn: Callable[[ChatRequest], ChatResponse], req: ChatRequest) -> ChatResponse:
        if not req.timeout:
            return fn(req)
        future = _POOL.submit(contextvars.copy_context().run, fn, req)  # the caller's context goes along
        try:
            return future.result(timeout=req.timeout)
        except concurrent.futures.TimeoutError:
            future.cancel()
            raise LlmError("timeout", f"the model endpoint did not answer within {max(1, int(req.timeout))}s") from None


__all__ = ["LlmGateway"]
