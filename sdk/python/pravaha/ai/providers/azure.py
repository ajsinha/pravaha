"""``type: azure_openai``: Azure OpenAI over ``urllib``. The OpenAI wire format with Azure's addressing
and authentication. Settings: ``base-url`` (the resource; also ``AZURE_OPENAI_ENDPOINT``), ``api-style``
(``v1`` or ``deployments``), ``api-version`` (2024-10-21), ``deployments.<model id>: <deployment>``,
``auth`` (``api_key``, ``bearer`` or ``entra``), ``max-tokens-param`` (``max_completion_tokens``), and for
Entra ``entra-mode``, ``tenant-id``, ``client-id``, ``client-secret`` (``${ENV}``),
``federated-token-file``, ``entra-scope``, ``entra-authority``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

from pravaha.ai.providers.cloud_auth import EntraTokenSource
from pravaha.ai.providers.http_base import JsonHttpProvider
from pravaha.ai.providers.openai_compat import OpenAiCompatibleProvider
from pravaha.ai.types import LlmError, ModelInfo, ProviderConfig


class AzureOpenAiProvider(OpenAiCompatibleProvider):
    def __init__(self, cfg: ProviderConfig, runtime: Any = None) -> None:
        if not cfg.base_url:
            raise LlmError("config", "base-url is required: the Azure OpenAI resource, https://<name>.openai.azure.com "
                           "(or set AZURE_OPENAI_ENDPOINT)")
        super().__init__(cfg, runtime)
        self.style = cfg.setting("api-style", "v1").lower()
        self.auth_mode = cfg.setting("auth", "api_key").lower()
        if self.style not in ("v1", "deployments") or self.auth_mode not in ("api_key", "bearer", "entra"):
            raise LlmError("config", "api-style is v1 or deployments; auth is api_key, bearer or entra")
        self._tokens: Optional[EntraTokenSource] = (EntraTokenSource(self._opener, cfg.settings)
                                                    if self.auth_mode == "entra" else None)
        self._tokens_param = cfg.setting("max-tokens-param", "max_completion_tokens")

    def _deployment(self, info: ModelInfo) -> str:
        return self.cfg.setting("deployments." + info.id, info.id)

    def _url_for(self, info: ModelInfo, what: str) -> str:
        if self.style == "v1":
            return f"{self.base}/openai/v1/{what}"
        return (f"{self.base}/openai/deployments/{self._deployment(info)}/{what}"
                f"?api-version={self.cfg.setting('api-version', '2024-10-21')}")

    def chat_url(self, info: ModelInfo) -> str:
        return self._url_for(info, "chat/completions")

    def embeddings_url(self, info: ModelInfo) -> str:
        return self._url_for(info, "embeddings")

    def wire_id(self, info: ModelInfo) -> str:
        return self._deployment(info)

    def _headers(self) -> Dict[str, str]:
        h = JsonHttpProvider._headers(self)  # no Authorization from the key: Azure has its own rules
        if self.auth_mode == "api_key" and self.cfg.api_key:
            h["api-key"] = self.cfg.api_key
        elif self.auth_mode == "bearer" and self.cfg.api_key:
            h["Authorization"] = "Bearer " + self.cfg.api_key
        return h

    def auth(self, method: str, url: str, data: bytes) -> Dict[str, str]:
        return {"Authorization": "Bearer " + self._tokens.token()} if self._tokens else {}

    def check(self) -> Tuple[bool, str]:
        if self._tokens:
            return self._tokens.check()
        if not self.cfg.api_key:
            return False, "no api key: set api-key: ${AZURE_OPENAI_API_KEY} (or auth: entra)"
        return True, "configured; no call is made to find out"

    def probe(self) -> Tuple[bool, str]:
        return self.check()  # Azure has no model-list call that every key may make

    def live_models(self) -> List[Dict[str, str]]:
        return []  # the deployments of the file are this resource's models


def factory(cfg: ProviderConfig, runtime: Any = None) -> AzureOpenAiProvider:
    return AzureOpenAiProvider(cfg, runtime)


__all__ = ["AzureOpenAiProvider", "factory"]
