"""Provider discovery by entry point: a provider is a package, not a change to Pravaha.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Each test installs a throwaway distribution -- a module and a ``.dist-info`` with an
``entry_points.txt`` -- into a directory on ``sys.path``, which is exactly what ``pip install``
leaves behind, and checks the registry finds it.
"""

from __future__ import annotations

import sys
import textwrap

import pytest

from pravaha.assist import AssistConfig, AssistConfigError, ChatRequest, Message, ModelRouter
from pravaha.assist.registry import (
    BUILTIN_PROVIDERS,
    ENTRY_POINT_GROUP,
    create_provider,
    discovery_problems,
    provider_names,
    providers,
)
from pravaha.assist.provider import ProviderSettings

ACME = '''
from pravaha.assist import BaseProvider, Capabilities, ChatResponse, Usage


class AcmeProvider(BaseProvider):
    name = "acme"
    default_capabilities = Capabilities(structured_output="none", max_context_tokens=8192)

    def _send(self, request, *, native_schema):
        greeting = self.settings.options.get("greeting", "hello")
        return ChatResponse(text=f"{greeting} from {request.model}", model=request.model,
                            usage=Usage(1, 2))


class NotAProvider:
    def __init__(self, settings):
        pass
'''


def _install(tmp_path, monkeypatch, entries: str, module: str = ACME, name: str = "acme_pravaha"):
    site = tmp_path / "site"
    site.mkdir(exist_ok=True)
    (site / f"{name}.py").write_text(textwrap.dedent(module))
    info = site / f"{name}-1.0.dist-info"
    info.mkdir()
    (info / "METADATA").write_text(f"Metadata-Version: 2.1\nName: {name}\nVersion: 1.0\n")
    (info / "entry_points.txt").write_text(f"[{ENTRY_POINT_GROUP}]\n{textwrap.dedent(entries)}")
    monkeypatch.syspath_prepend(str(site))
    monkeypatch.delitem(sys.modules, name, raising=False)


def test_the_built_ins_are_always_there():
    assert provider_names()[:5] == list(BUILTIN_PROVIDERS)
    assert all(p.source == "builtin" for p in providers() if p.name in BUILTIN_PROVIDERS)


def test_a_third_party_provider_is_discovered_and_used(tmp_path, monkeypatch):
    _install(tmp_path, monkeypatch, "acme = acme_pravaha:AcmeProvider\n")
    assert "acme" in provider_names()
    info = next(p for p in providers() if p.name == "acme")
    assert info.source == "acme_pravaha"
    assert info.capabilities is not None and info.capabilities.max_context_tokens == 8192
    provider = create_provider("acme", ProviderSettings(options={"greeting": "hi"}))
    assert provider.complete(ChatRequest(messages=[Message("user", "x")], model="m1")).text == \
        "hi from m1"
    # ...and through configuration, like any built-in.
    config = AssistConfig.from_dict({
        "providers": [{"id": "acme", "type": "acme", "options": {"greeting": "hey"}}],
        "models": [{"id": "rocket", "provider": "acme", "model": "rocket-1"}],
        "profiles": {"explain": ["rocket"]},
    })
    router = ModelRouter(config, environ={})
    answer = router.ask("x", profile="explain")
    assert (answer.text, answer.provider) == ("hey from rocket-1", "acme")


def test_an_entry_point_cannot_take_a_built_in_name(tmp_path, monkeypatch):
    _install(tmp_path, monkeypatch, "anthropic = acme_pravaha:AcmeProvider\n")
    assert any("a built-in's name; ignored" in p for p in discovery_problems())
    provider = create_provider("anthropic", ProviderSettings(api_key="k"))
    assert type(provider).__module__ == "pravaha.assist.providers.anthropic"


def test_a_broken_or_wrong_entry_point_is_a_named_configuration_error(tmp_path, monkeypatch):
    _install(tmp_path, monkeypatch,
             "broken = acme_pravaha_missing:Nothing\nodd = acme_pravaha:NotAProvider\n")
    with pytest.raises(AssistConfigError, match="'broken'.*could not be loaded"):
        create_provider("broken", ProviderSettings())
    with pytest.raises(AssistConfigError, match="lacks name, capabilities or complete"):
        create_provider("odd", ProviderSettings())
    assert next(p for p in providers() if p.name == "broken").problem
    with pytest.raises(AssistConfigError, match="no provider named 'nope'"):
        create_provider("nope", ProviderSettings())
