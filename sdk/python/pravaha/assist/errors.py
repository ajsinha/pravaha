"""What can go wrong between the assistant and a model, in four normalised shapes.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Every provider turns its own failures into one of four types (ADR-058 §1), so the router can act
on them without knowing which provider it is talking to:

* :class:`ModelUnavailable` -- the model could not answer this request: nothing answered, a 5xx,
  an overloaded service, an unknown model, a key it refused. The router tries the next model.
* :class:`ModelRateLimited` -- the provider said "not now", with its ``retry_after`` when it gave
  one. The router tries the next model.
* :class:`ModelRefused` -- the model declined on content policy. Reported, never silently
  passed to another model.
* :class:`ModelOutputError` -- the model answered, but not in the shape asked for, even after
  the one repair turn. Reported, never guessed at.

:class:`AssistConfigError` is a mistake in the configuration (exit 2 on the command line) and
:class:`BudgetExceeded` a request refused before it was sent. None of these is a
:class:`pravaha.errors.PravahaError`: ``PRV-nnnn`` codes are the engine's, and the engine is not
involved in any of this.
"""

from __future__ import annotations

from typing import Any, Optional, Sequence


class AssistError(Exception):
    """Anything the assistant reports. ``kind`` is the class name, for JSON output."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message

    @property
    def kind(self) -> str:
        return type(self).__name__

    def to_dict(self) -> dict[str, Any]:
        return {"kind": self.kind, "message": self.message}


class AssistConfigError(AssistError):
    """The assistant's configuration is wrong or incomplete: nothing was sent to any model."""


class BudgetExceeded(AssistError):
    """A request that would exceed a token budget, refused before it was sent."""


class ModelError(AssistError):
    """A model or its provider failed. Carries who failed, and what the router tried first."""

    #: Whether asking again, or asking another model, may succeed.
    retryable = False

    def __init__(
        self,
        message: str,
        *,
        provider: Optional[str] = None,
        model: Optional[str] = None,
        alias: Optional[str] = None,
        status: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.provider = provider
        self.model = model
        self.alias = alias
        self.status = status
        #: Earlier failures in the same fallback chain, oldest first (set by the router).
        self.attempts: Sequence[Any] = ()

    def __str__(self) -> str:
        who = "/".join(part for part in (self.alias, self.provider, self.model) if part)
        return f"{self.message} ({who})" if who else self.message

    def to_dict(self) -> dict[str, Any]:
        body = super().to_dict()
        body.update(
            {
                "alias": self.alias,
                "provider": self.provider,
                "model": self.model,
                "status": self.status,
                "attempts": [getattr(a, "to_dict", lambda a=a: a)() for a in self.attempts],
            }
        )
        return body


class ModelUnavailable(ModelError):
    """The model could not answer: unreachable, overloaded, a 5xx, or a request it would not
    take (an unknown model, a refused key). The router falls through to the next model."""

    def __init__(self, message: str, *, retryable: bool = True, **who: Any) -> None:
        super().__init__(message, **who)
        self.retryable = retryable


class ModelRateLimited(ModelError):
    """The provider is rate-limiting this caller. ``retry_after`` is its own hint, in seconds."""

    retryable = True

    def __init__(self, message: str, *, retry_after: Optional[float] = None, **who: Any) -> None:
        super().__init__(message, **who)
        self.retry_after = retry_after

    def to_dict(self) -> dict[str, Any]:
        body = super().to_dict()
        body["retryAfter"] = self.retry_after
        return body


class ModelRefused(ModelError):
    """The model declined on content policy. Not passed to another model without saying so."""


class ModelOutputError(ModelError):
    """The answer did not satisfy the schema asked for, after exactly one repair turn."""

    def __init__(
        self,
        message: str,
        *,
        text: str = "",
        problem: str = "",
        usage: Any = None,
        **who: Any,
    ) -> None:
        super().__init__(message, **who)
        #: The last answer the model gave, verbatim.
        self.text = text
        #: What was wrong with it.
        self.problem = problem
        #: Tokens spent on both turns, so a budget still counts them.
        self.usage = usage

    def to_dict(self) -> dict[str, Any]:
        body = super().to_dict()
        body["problem"] = self.problem
        return body


__all__ = [
    "AssistConfigError",
    "AssistError",
    "BudgetExceeded",
    "ModelError",
    "ModelOutputError",
    "ModelRateLimited",
    "ModelRefused",
    "ModelUnavailable",
]
