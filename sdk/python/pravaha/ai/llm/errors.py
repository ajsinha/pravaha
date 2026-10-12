"""The error hierarchy of the LLM layer (sajhamcpserver's names, as Drishti ported them). Each carries a
``kind`` equal to the engine's ``LlmException.Kind``, so the same scenario raises the same kind in both
(the shared contract test). The message never carries a key or a prompt.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from typing import Any, Dict, Optional, Type

from pravaha.ai.types import LlmError


class LLMError(Exception):
    kind = "FAILED"

    def __init__(self, message: str = "", *, provider: str = "", model: str = "", kind: Optional[str] = None,
                 code: str = "") -> None:
        super().__init__(message)
        self.message, self.provider, self.model, self.code = message, provider, model, code
        if kind:
            self.kind = kind

    def to_dict(self) -> Dict[str, Any]:
        return {"kind": self.kind, "message": self.message, "provider": self.provider, "model": self.model,
                "code": self.code}


class RateLimited(LLMError):
    kind = "RATE_LIMITED"

    def __init__(self, message: str = "", *, retry_after: Optional[float] = None, **kw: Any) -> None:
        super().__init__(message, **kw)
        self.retry_after = retry_after


class ProviderUnavailable(LLMError):
    kind = "TIMEOUT"


class UnsupportedFeature(LLMError):
    kind = "UNSUPPORTED"


class ModelFailed(LLMError):
    kind = "FAILED"


class PolicyDenied(LLMError):
    kind = "POLICY"


class BudgetExceeded(RateLimited):
    kind = "RATE_LIMITED"


class NoModelAvailable(LLMError):
    kind = "NOT_FOUND"


class ConfigurationError(LLMError):
    kind = "CONFIG"


_BY_KIND: Dict[str, Type[LLMError]] = {
    "config": ConfigurationError, "not_found": NoModelAvailable, "unsupported": UnsupportedFeature,
    "policy": PolicyDenied, "rate_limited": RateLimited, "timeout": ProviderUnavailable, "failed": ModelFailed}


def from_wire(e: LlmError, *, provider: str = "", model: str = "") -> LLMError:
    """The wire layer's ``LlmError(kind, message)`` as the canonical error of that kind, with its code."""
    return _BY_KIND.get(e.kind, ModelFailed)(e.message, provider=provider, model=model, code=e.code)


__all__ = ["BudgetExceeded", "ConfigurationError", "LLMError", "ModelFailed", "NoModelAvailable", "PolicyDenied",
           "ProviderUnavailable", "RateLimited", "UnsupportedFeature", "from_wire"]
