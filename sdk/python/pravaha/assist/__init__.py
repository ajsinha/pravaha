"""``pravaha.assist``: plain English to and from continuous SQL, through any model, with the
engine as the judge (ADR-058).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Three layers, each replaceable::

    Assistant  --uses-->  ModelRouter  --picks-->  ModelProvider
    (tasks)               (profiles, fallback,     (anthropic | openai | openai-compatible |
                           budgets, reconfigure)    ollama | fake | any entry point)

Phases 1 and 2 are built: the provider protocol and five built-in providers, the router with
fallback, budgets and runtime reconfiguration, the configuration store and its administration
facade; the two tasks that need no confirmation -- :meth:`Assistant.explain_query` and
:meth:`Assistant.explain_refusal`; drafting a query from a description with the engine as the
judge -- :meth:`Assistant.draft`, registered only by a person through
:meth:`Assistant.register`; and the evaluation harness over the golden set
(:mod:`pravaha.assist.evaluate`). ::

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
    DRAFT_PROFILE,
    EXPLAIN_PROFILE,
    Assistant,
    QueryExplanation,
    RefusalExplanation,
)
from pravaha.assist.context import ContextBuilder, DraftContext, Example, load_examples
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
    RegistrationRefused,
)
from pravaha.assist.drafting import Draft, Turn, Verdict
from pravaha.assist.evaluate import CaseResult, EvalReport, Evaluator, GoldenCase, load_golden_set
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
    "load_golden_set",
    "load_examples",
    "Verdict",
    "Turn",
    "RegistrationRefused",
    "GoldenCase",
    "Example",
    "Evaluator",
    "EvalReport",
    "DraftContext",
    "Draft",
    "ContextBuilder",
    "CaseResult",
    "DRAFT_PROFILE",
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
