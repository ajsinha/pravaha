"""Provider types by name. To add one: a class with the ``LlmProvider`` methods (``BaseProvider`` does the
bookkeeping), a ``factory(config, runtime)``, and an entry here (or ``register`` at start-up).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from typing import Any, Dict

from pravaha.ai.providers import (anthropic, azure, bedrock, cohere, gemini, mock, ollama, openai_compat,
                                  server)

FACTORIES: Dict[str, Any] = {
    "openai": openai_compat.factory, "anthropic": anthropic.factory, "server": server.factory, "mock": mock.factory,
    # the OpenAI-compatible presets, then one class each (the engine's list, docs/design/architecture/ai.md)
    **{t: openai_compat.factory for t in ("openai_compatible", "groq", "together", "fireworks", "deepseek", "xai",
                                          "openrouter", "perplexity", "huggingface", "vllm", "lmstudio", "mistral")},
    "azure_openai": azure.factory, "gemini": gemini.factory, "cohere": cohere.factory, "ollama": ollama.factory,
    "bedrock": bedrock.factory,
}


def register(type_name: str, factory: Any) -> None:
    """Adds (or replaces) a provider type at start-up."""
    FACTORIES[type_name.lower()] = factory


__all__ = ["FACTORIES", "register"]
