"""``pravaha.assist``: plain English to and from continuous SQL, through any model, with the
engine as the judge (ADR-058).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Three layers, each replaceable::

    Assistant  --uses-->  ModelRouter  --picks-->  ModelProvider
    (tasks)               (profiles, fallback,     (anthropic | openai | openai-compatible |
                           budgets, reconfigure)    ollama | fake | any entry point)

Phase 1 is built: the provider protocol and five built-in providers, the router with fallback,
budgets and runtime reconfiguration, the configuration store and its administration facade, and
the two tasks that need no confirmation -- :meth:`Assistant.explain_query` and
:meth:`Assistant.explain_refusal`. Drafting a query from a description is phase 2. ::

    from pravaha.api import EngineApi
    from pravaha.assist import Assistant, FileConfigStore, ModelRouter

    router = ModelRouter.from_store(FileConfigStore())      # ~/.config/pravaha/assist.json
    assistant = Assistant(router, EngineApi("http://localhost:18080", token=token))
    print(assistant.explain_refusal("PRV-2050", "SELECT k, COUNT(*) FROM s GROUP BY k").fix)

Standard library only: the SDK's base install gains no dependency for any of it.
"""

from pravaha.assist._http import request_json, retry_after
from pravaha.assist.admin import AssistAdmin, AuditRecord
from pravaha.assist.assistant import (
    EXPLAIN_PROFILE,
    Assistant,
    QueryExplanation,
    RefusalExplanation,
)
from pravaha.assist.config import AssistConfig, Budgets, ModelConfig, ProviderConfig
from pravaha.assist.errors import (
    AssistConfigError,
    AssistError,
    BudgetExceeded,
    ModelError,
    ModelOutputError,
    ModelRateLimited,
    ModelRefused,
    ModelUnavailable,
)
from pravaha.assist.prompts import DialectCard, Prompt, load_card, load_prompt
from pravaha.assist.provider import (
    BaseProvider,
    Capabilities,
    ChatChunk,
    ChatRequest,
    ChatResponse,
    Message,
    ModelProvider,
    ProviderSettings,
    StreamingProvider,
    Usage,
)
from pravaha.assist.registry import (
    ENTRY_POINT_GROUP,
    ProviderInfo,
    create_provider,
    provider_names,
    providers,
)
from pravaha.assist.router import (
    Attempt,
    CheckResult,
    ModelRouter,
    RoutedResponse,
    UsageLedger,
)
from pravaha.assist.store import (
    AssistConfigStore,
    ConfigConflict,
    ConfigWatch,
    FileConfigStore,
    default_config_path,
)

__all__ = [
    "ENTRY_POINT_GROUP",
    "EXPLAIN_PROFILE",
    "AssistAdmin",
    "AssistConfig",
    "AssistConfigError",
    "AssistConfigStore",
    "AssistError",
    "Assistant",
    "Attempt",
    "AuditRecord",
    "BaseProvider",
    "Budgets",
    "BudgetExceeded",
    "Capabilities",
    "ChatChunk",
    "ChatRequest",
    "ChatResponse",
    "CheckResult",
    "ConfigConflict",
    "ConfigWatch",
    "DialectCard",
    "FileConfigStore",
    "Message",
    "ModelConfig",
    "ModelError",
    "ModelOutputError",
    "ModelProvider",
    "ModelRateLimited",
    "ModelRefused",
    "ModelRouter",
    "ModelUnavailable",
    "Prompt",
    "ProviderConfig",
    "ProviderInfo",
    "ProviderSettings",
    "QueryExplanation",
    "RefusalExplanation",
    "RoutedResponse",
    "StreamingProvider",
    "Usage",
    "UsageLedger",
    "create_provider",
    "default_config_path",
    "load_card",
    "load_prompt",
    "provider_names",
    "providers",
    "request_json",
    "retry_after",
]
