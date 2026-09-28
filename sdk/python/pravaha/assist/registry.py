"""Finding providers: the built-ins, and any package that declares one by entry point.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A provider is a package, not a change to Pravaha (ADR-058 §1)::

    [project.entry-points."pravaha.assist.providers"]
    acme = "acme_pravaha:AcmeProvider"

The object an entry point names is called with one :class:`ProviderSettings` and must answer a
:class:`ModelProvider` -- a class whose ``__init__`` takes the settings is the usual form.

A third-party entry point cannot take a built-in's name: the built-in wins and the clash is
listed by :func:`discovery_problems`, so installing a package cannot silently redirect the
``anthropic`` provider somewhere else. An entry point that fails to import is reported when it is
used, naming its package, rather than when anything else is.
"""

from __future__ import annotations

import dataclasses
import importlib
import sys
from importlib import metadata
from typing import Any, Callable, Optional

from pravaha.assist.errors import AssistConfigError
from pravaha.assist.provider import Capabilities, ModelProvider, ProviderSettings

ENTRY_POINT_GROUP = "pravaha.assist.providers"

#: The built-ins, by name, as ``module:attribute`` -- the same targets pyproject.toml declares.
BUILTIN_PROVIDERS: dict[str, str] = {
    "fake": "pravaha.assist.providers.fake:FakeProvider",
    "anthropic": "pravaha.assist.providers.anthropic:AnthropicProvider",
    "openai": "pravaha.assist.providers.openai:OpenAIProvider",
    "openai-compatible": "pravaha.assist.providers.openai:OpenAICompatibleProvider",
    "ollama": "pravaha.assist.providers.ollama:OllamaProvider",
}

ProviderFactory = Callable[[ProviderSettings], ModelProvider]


@dataclasses.dataclass(frozen=True)
class ProviderInfo:
    """One provider this process can build: its name, where it came from, and what it names."""

    name: str
    target: str
    #: ``builtin``, or the distribution that declared the entry point.
    source: str
    #: The class's default capabilities, when it declares them; ``None`` when loading it failed.
    capabilities: Optional[Capabilities] = None
    problem: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "target": self.target,
            "source": self.source,
            "capabilities": self.capabilities.to_dict() if self.capabilities else None,
            "problem": self.problem,
        }


def _entry_points() -> "list[metadata.EntryPoint]":
    if sys.version_info >= (3, 10):
        return list(metadata.entry_points(group=ENTRY_POINT_GROUP))
    found: Any = metadata.entry_points()  # pragma: no cover - Python 3.9: a dict of groups
    return list(found.get(ENTRY_POINT_GROUP, []))  # pragma: no cover


def _distribution(entry: "metadata.EntryPoint") -> str:
    dist = getattr(entry, "dist", None)
    return str(getattr(dist, "name", None) or "an installed package")


def _discover() -> "tuple[dict[str, tuple[str, str]], list[str]]":
    """``name -> (target, source)`` for every provider, and the clashes found on the way."""
    known: dict[str, tuple[str, str]] = {
        name: (target, "builtin") for name, target in BUILTIN_PROVIDERS.items()
    }
    problems: list[str] = []
    for entry in _entry_points():
        source = _distribution(entry)
        if entry.name in BUILTIN_PROVIDERS:
            if entry.value.replace(" ", "") != BUILTIN_PROVIDERS[entry.name]:
                problems.append(
                    f"{source} declares provider {entry.name!r}, a built-in's name; ignored"
                )
            continue
        if entry.name in known:
            problems.append(
                f"{source} declares provider {entry.name!r}, which {known[entry.name][1]} "
                f"already declared; the first is used"
            )
            continue
        known[entry.name] = (entry.value, source)
    return known, problems


def discovery_problems() -> "list[str]":
    """Entry points that were ignored, and why."""
    return _discover()[1]


def provider_names() -> "list[str]":
    """Every provider name this process can build, built-ins first."""
    known = _discover()[0]
    return list(BUILTIN_PROVIDERS) + sorted(n for n in known if n not in BUILTIN_PROVIDERS)


def _load(target: str) -> Any:
    module_name, _, attribute = target.partition(":")
    thing: Any = importlib.import_module(module_name.strip())
    for part in attribute.strip().split(".") if attribute else []:
        thing = getattr(thing, part)
    return thing


def load_factory(name: str) -> ProviderFactory:
    """The callable that builds provider ``name``, or :class:`AssistConfigError`."""
    known = _discover()[0]
    if name not in known:
        raise AssistConfigError(
            f"no provider named {name!r}; known: {', '.join(provider_names())}. A third-party "
            f"provider is a package declaring an entry point in group {ENTRY_POINT_GROUP!r}"
        )
    target, source = known[name]
    try:
        factory = _load(target)
    except Exception as exc:
        raise AssistConfigError(
            f"provider {name!r} ({target}, from {source}) could not be loaded: "
            f"{type(exc).__name__}: {exc}"
        ) from exc
    if not callable(factory):
        raise AssistConfigError(f"provider {name!r} ({target}) is not callable")
    return factory  # type: ignore[no-any-return]


def create_provider(name: str, settings: ProviderSettings) -> ModelProvider:
    """Builds provider ``name`` and checks it is one: ``name``, ``capabilities``, ``complete``."""
    provider = load_factory(name)(settings)
    if not isinstance(provider, ModelProvider):
        raise AssistConfigError(
            f"provider {name!r} built a {type(provider).__name__}, which lacks name, "
            f"capabilities or complete"
        )
    return provider


def providers() -> "list[ProviderInfo]":
    """Every provider -- built-in and discovered -- with its default capabilities."""
    known = _discover()[0]
    answer: list[ProviderInfo] = []
    for name in provider_names():
        target, source = known[name]
        try:
            factory = _load(target)
        except Exception as exc:
            answer.append(ProviderInfo(name, target, source, None, f"{type(exc).__name__}: {exc}"))
            continue
        capabilities = getattr(factory, "default_capabilities", None)
        answer.append(
            ProviderInfo(
                name,
                target,
                source,
                capabilities if isinstance(capabilities, Capabilities) else None,
            )
        )
    return answer


__all__ = [
    "BUILTIN_PROVIDERS",
    "ENTRY_POINT_GROUP",
    "ProviderFactory",
    "ProviderInfo",
    "create_provider",
    "discovery_problems",
    "load_factory",
    "provider_names",
    "providers",
]
