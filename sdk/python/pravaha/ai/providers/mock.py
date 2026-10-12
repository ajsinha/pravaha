"""``type: mock``: no network and deterministic answers, for tests, demos and features built before a
model is chosen.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Configured under ``mock.*`` exactly like the engine's mock::

    type: mock
    mock:
      reply: "a fixed answer"      # omit to echo the last user message
      latency-ms: 0                # simulated delay
      fail: ""                     # every call fails with this message
      fail-every: 0                # every Nth call fails
      dimensions: 8                # embedding vector size
      rules:                       # first rule whose regex is found in the last user message applies
        - {match: "(?i)hello", reply: "Hi there"}
        - {match: "weather", tool: get_weather, tool-args: '{"city":"Oslo"}'}
        - {match: "boom", error: "scripted failure"}

Usage is characters / 4, so cost limits and the audit can be exercised.

"""

from __future__ import annotations

import re
import threading
import time
from typing import Any, List, Optional, Pattern, Tuple

from pravaha.ai.providers.base import CHAT_EXTRAS, BaseProvider
from pravaha.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

Rule = Tuple[Pattern[str], str, str, str, str, int]


def _num(cfg: ProviderConfig, key: str, default: str) -> int:
    try:
        return int(cfg.setting(key, default))
    except ValueError:
        raise LlmError("config", f"mock setting '{key}' is not a number") from None


class MockProvider(BaseProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime: Any = None) -> None:
        super().__init__(cfg)
        self.calls = 0
        self._lock = threading.Lock()
        self._reply: Optional[str] = cfg.settings.get("mock.reply")
        self._latency = _num(cfg, "mock.latency-ms", "0") / 1000
        self._fail = cfg.setting("mock.fail")
        self._fail_every = _num(cfg, "mock.fail-every", "0")
        self._dims = max(1, _num(cfg, "mock.dimensions", "8"))
        self._rules: List[Rule] = []
        i = 0
        while f"mock.rules.{i}.match" in cfg.settings:
            p = f"mock.rules.{i}."
            self._rules.append((re.compile(cfg.settings[p + "match"]), cfg.setting(p + "reply"), cfg.setting(p + "tool"),
                                cfg.setting(p + "tool-args", "{}"), cfg.setting(p + "error"),
                                _num(cfg, p + "latency-ms", "-1")))
            i += 1

    def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        with self._lock:
            self.calls += 1
            n = self.calls
        last = req.messages[-1].content if req.messages else ""
        hit = next((r for r in self._rules if r[0].search(last)), None)
        delay = hit[5] / 1000 if hit and hit[5] >= 0 else self._latency
        if delay > 0:
            time.sleep(delay)
        if self._fail or (self._fail_every > 0 and n % self._fail_every == 0):
            raise LlmError("failed", self._fail or "mock: scheduled failure")
        if hit and hit[4]:
            raise LlmError("failed", hit[4])
        text = hit[1] if hit else (self._reply if self._reply is not None else "echo: " + last)
        calls = (ToolCall(f"mock-{n}", hit[2], hit[3]),) if hit and hit[2] else ()
        pi, co = (req.prompt_chars() + 3) // 4, (len(text) + 3) // 4
        return ChatResponse(text, calls, info.id, "tool_calls" if calls else "stop", Usage(pi, co, info.cost(pi, co)))

    def embed(self, info: ModelInfo, texts: List[str]) -> List[List[float]]:
        out = []
        for t in texts:
            v = [0.0] * self._dims
            for i, ch in enumerate(t):
                v[i % self._dims] += (ord(ch) % 31) / 31
            out.append(v)
        return out


def factory(cfg: ProviderConfig, runtime: Any = None) -> MockProvider:
    return MockProvider(cfg, runtime)


__all__ = ["MockProvider", "factory"]
