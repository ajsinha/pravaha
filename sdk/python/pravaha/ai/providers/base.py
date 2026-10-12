"""Common parts of providers: the model list from the provider file, lookup by logical name, and the
shared ``tls.*`` / ``proxy.*`` handling for the HTTP ones.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

TLS is the connectors' vocabulary (docs/guides/CONNECTOR_TLS.md) in its PEM form: ``tls.ca``,
``tls.certificate`` + ``tls.key``, ``tls.enabled``; ``tls.verify-hostname: false`` is refused (as the
engine refuses it), and a JKS/PKCS12 ``tls.truststore`` / ``tls.keystore`` is refused with directions,
because the standard library reads PEM only. A proxy is ``proxy.url``; its authentication is not built.
"""

from __future__ import annotations

import ssl
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

from pravaha.ai.types import CHAT, EMBEDDINGS, JSON_MODE, TOOL_CALLS, ChatRequest, LlmError, ModelInfo, ProviderConfig

CHAT_EXTRAS: Tuple[str, ...] = (TOOL_CALLS, JSON_MODE)

_TLS_KNOWN = {"tls.enabled", "tls.verify-hostname", "tls.ca", "tls.certificate", "tls.key", "tls.truststore",
              "tls.truststore.password", "tls.truststore.type", "tls.keystore", "tls.keystore.password", "tls.keystore.type"}


def ssl_context(cfg: ProviderConfig) -> Optional[ssl.SSLContext]:
    """The context the file's ``tls.*`` keys describe, or None for the system's defaults."""
    tls = {k: v for k, v in cfg.settings.items() if k.startswith("tls.")}
    if not tls:
        return None
    for k in tls:
        if k not in _TLS_KNOWN:
            raise LlmError("config", f"'{k}' is not a TLS option a connector understands (tls.ca, tls.certificate, tls.key, ...)")
    if tls.get("tls.verify-hostname", "true").lower() == "false":
        raise LlmError("config", "tls.verify-hostname: false is not supported; give the endpoint a name its certificate carries")
    if tls.get("tls.truststore") or tls.get("tls.keystore"):
        raise LlmError("config", "a JKS/PKCS12 tls.truststore or tls.keystore is read by the engine only; "
                       "in Python give PEM files: tls.ca, tls.certificate and tls.key")
    if tls.get("tls.enabled", "").lower() == "false":
        return None
    if bool(tls.get("tls.certificate")) != bool(tls.get("tls.key")):
        raise LlmError("config", "tls.certificate and tls.key go together")
    try:
        ctx = ssl.create_default_context(cafile=tls.get("tls.ca") or None)
        if tls.get("tls.certificate"):
            ctx.load_cert_chain(tls["tls.certificate"], tls["tls.key"])
    except (OSError, ssl.SSLError) as e:
        raise LlmError("config", f"the tls.* files could not be read ({type(e).__name__})") from None
    return ctx


def opener(cfg: ProviderConfig) -> urllib.request.OpenerDirector:
    """A urllib opener with the file's TLS and proxy, and never the environment's proxy."""
    if cfg.setting("proxy.username") or cfg.setting("proxy.password"):
        raise LlmError("config", "proxy authentication (proxy.username, proxy.password) is not built")
    proxy = cfg.setting("proxy.url")
    handlers: List[Any] = [urllib.request.ProxyHandler({"http": proxy, "https": proxy} if proxy else {})]
    ctx = ssl_context(cfg)
    if ctx is not None:
        handlers.append(urllib.request.HTTPSHandler(context=ctx))
    return urllib.request.build_opener(*handlers)


class BaseProvider:
    default_caps: Tuple[str, ...] = ()

    def __init__(self, cfg: ProviderConfig) -> None:
        self.cfg = cfg
        self.id = cfg.name
        self.type = cfg.type
        self._models: List[ModelInfo] = []
        for m in cfg.models:
            caps = tuple(m["capabilities"]) or ((EMBEDDINGS,) if m["kind"] == "embedding" else (CHAT,))
            if m["kind"] != "embedding" and caps == (CHAT,):
                caps = caps + self.default_caps
            self._models.append(ModelInfo(m["name"], cfg.name, m["id"], m["kind"], caps, m["max_tokens"], m["temperature"],
                                          m["cost_per_1k_input"], m["cost_per_1k_output"]))

    def models(self) -> List[ModelInfo]:
        return list(self._models)

    def _find(self, name: str, embedding: bool) -> Optional[ModelInfo]:
        return next((m for m in self._models if m.name == name and m.embedding == embedding), None)

    def chat_model(self, logical_name: str) -> Optional["Bound"]:
        info = self._find(logical_name, False)
        return Bound(info, self) if info else None

    def embedding_model(self, logical_name: str) -> Optional["BoundEmbed"]:
        info = self._find(logical_name, True)
        return BoundEmbed(info, self) if info else None

    def complete(self, info: ModelInfo, req: ChatRequest) -> Any:
        raise NotImplementedError

    def embed(self, info: ModelInfo, texts: List[str]) -> List[List[float]]:
        raise LlmError("unsupported", "this provider type has no embeddings")

    def check(self) -> Tuple[bool, str]:
        """No-network readiness: is what the type needs there (a key, a region, ...)?"""
        return True, "configured; no call is made to find out"

    def probe(self) -> Tuple[bool, str]:
        """One cheap live call (a model list) where the type has one; the readiness check otherwise."""
        return self.check()

    def live_models(self) -> List[Dict[str, str]]:
        """The model ids the endpoint reports now: ``[{"id", "kind"}]``; empty when the API has no listing."""
        return []

    def close(self) -> None:
        return None

    @staticmethod
    def max_tokens(req: ChatRequest, info: ModelInfo, default: int) -> int:
        return req.max_tokens or info.max_tokens or default

    @staticmethod
    def temperature(req: ChatRequest, info: ModelInfo) -> Optional[float]:
        return req.temperature if req.temperature is not None else info.temperature

    @staticmethod
    def system_text(req: ChatRequest) -> str:
        parts = [req.system] if req.system else []
        parts += [m.content for m in req.messages if m.role == "system"]
        return "\n".join(parts)


class Bound:
    def __init__(self, info: ModelInfo, provider: BaseProvider) -> None:
        self.info = info
        self._p = provider

    def chat(self, request: ChatRequest) -> Any:
        return self._p.complete(self.info, request)


class BoundEmbed:
    def __init__(self, info: ModelInfo, provider: BaseProvider) -> None:
        self.info = info
        self._p = provider

    def embed(self, texts: List[str]) -> List[List[float]]:
        return self._p.embed(self.info, texts)


__all__ = ["BaseProvider", "Bound", "BoundEmbed", "CHAT_EXTRAS", "opener", "ssl_context"]
