"""The built-in model providers: ``fake``, ``anthropic``, ``openai``, ``openai-compatible``,
``ollama``. Each is written against its provider's HTTP API with the standard library.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

They are found the same way a third-party provider is, through the ``pravaha.assist.providers``
entry-point group (see :mod:`pravaha.assist.registry`), and also named here so a source checkout
with no installed metadata still has them.
"""

from pravaha.assist.providers.anthropic import AnthropicProvider
from pravaha.assist.providers.fake import FakeProvider
from pravaha.assist.providers.ollama import OllamaProvider
from pravaha.assist.providers.openai import OpenAICompatibleProvider, OpenAIProvider

__all__ = [
    "AnthropicProvider",
    "FakeProvider",
    "OllamaProvider",
    "OpenAICompatibleProvider",
    "OpenAIProvider",
]
