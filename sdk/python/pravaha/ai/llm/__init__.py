"""The OpenAI-shaped second layer, on the abstractions of sajhamcpserver as Drishti ported them:
``LLMProvider`` / ``LLMModel``, the canonical types, ``ProviderBase``, the provider registry and
``LLMFactory`` / ``GovernedModel``. The engine's ``com.ash.messaging.pravaha.ai.llm`` is the same design in
camelCase.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

    factory = ai.llm                                       # an LLMFactory
    model = factory.model("default", context=CallContext("ann", frozenset(), "my-feature"))
    completion = model.chat_completions_create(messages=[{"role": "user", "content": "hello"}])
    completion.text
"""

from pravaha.ai.llm.base import LLMModel, LLMProvider
from pravaha.ai.llm.canonical import (ChatCompletion, ChatCompletionChunk, ChatCompletionRequest, ChatMessage,
                                      Choice, CompletionUsage, EmbeddingData, EmbeddingsRequest, EmbeddingsResponse,
                                      FunctionCall, FunctionDefinition, PravahaRequest, ResponseFormat,
                                      ResponsePravaha, ToolCall, ToolDefinition, completion_to_chunks)
from pravaha.ai.llm.errors import (BudgetExceeded, ConfigurationError, LLMError, ModelFailed, NoModelAvailable,
                                   PolicyDenied, ProviderUnavailable, RateLimited, UnsupportedFeature)
from pravaha.ai.llm.factory import GovernedModel, LLMFactory
from pravaha.ai.llm.model import ChatModel, EmbeddingModel, HealthStatus, ModelCapabilities, ModelInfo
from pravaha.ai.llm.provider import ProviderBase
from pravaha.ai.llm.registry import register_provider, registered_providers, unregister_provider

__all__ = ["BudgetExceeded", "ChatCompletion", "ChatCompletionChunk", "ChatCompletionRequest", "ChatMessage",
           "ChatModel", "Choice", "CompletionUsage", "ConfigurationError", "EmbeddingData", "EmbeddingModel",
           "EmbeddingsRequest", "EmbeddingsResponse", "FunctionCall", "FunctionDefinition", "GovernedModel",
           "HealthStatus", "LLMError", "LLMFactory", "LLMModel", "LLMProvider", "ModelCapabilities", "ModelFailed",
           "ModelInfo", "NoModelAvailable", "PolicyDenied", "PravahaRequest", "ProviderBase", "ProviderUnavailable",
           "RateLimited", "ResponseFormat", "ResponsePravaha", "ToolCall", "ToolDefinition", "UnsupportedFeature",
           "completion_to_chunks", "register_provider", "registered_providers", "unregister_provider"]
