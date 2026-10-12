"""Provider classes by type: ``register_provider`` puts an ``LLMProvider`` subclass in the registry;
``registered_providers`` lists them. The shipped types are registered with ``ProviderBase``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from typing import Any, Dict, Optional

from pravaha.ai.llm.base import LLMProvider
from pravaha.ai.llm.errors import ConfigurationError

_PROVIDERS: Dict[str, type] = {}


def register_provider(cls: Any = None, *, name: Optional[str] = None, replace: bool = True) -> Any:
    """Register ``cls`` under ``name`` (default ``cls.name``); usable as a decorator."""
    def deco(c: Any) -> Any:
        if not (isinstance(c, type) and issubclass(c, LLMProvider)):
            raise ConfigurationError(f"{c!r} is not an LLMProvider subclass")
        key = (name or getattr(c, "name", "") or "").lower()
        if not key:
            raise ConfigurationError("a provider needs a name")
        if key in _PROVIDERS and not replace:
            raise ConfigurationError(f"provider '{key}' is already registered")
        _PROVIDERS[key] = c
        return c
    return deco(cls) if cls is not None else deco


def unregister_provider(name: str) -> None:
    _PROVIDERS.pop(name.lower(), None)


def registered_providers() -> Dict[str, type]:
    from pravaha.ai.llm.provider import ProviderBase
    from pravaha.ai.providers import FACTORIES
    for t in FACTORIES:
        _PROVIDERS.setdefault(t, ProviderBase)
    return dict(sorted(_PROVIDERS.items()))


__all__ = ["register_provider", "registered_providers", "unregister_provider"]
