"""``type: bedrock``: Amazon Bedrock over its REST API, signed with SigV4 by hand (``sigv4``) -- the same
choice the engine made: no boto3, which the SDK may not depend on. The Converse API for chat
(``POST /model/{id}/converse``), InvokeModel for embeddings (Titan one text per call, Cohere a batch),
``GET /foundation-models`` on the control plane for the live model list.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Credentials: ``aws-access-key-id`` / ``aws-secret-access-key`` / ``aws-session-token`` as ``${ENV}``, else
``AWS_ACCESS_KEY_ID`` / ``AWS_SECRET_ACCESS_KEY`` / ``AWS_SESSION_TOKEN`` in the environment. **Profiles,
SSO and instance or container roles are not read.** Settings: ``region`` (``AWS_REGION`` /
``AWS_DEFAULT_REGION``, then us-east-1), ``base-url`` (an endpoint override), ``control-url``,
``embedding-input-type``.
"""

from __future__ import annotations

import datetime
import hashlib
import hmac
import json
import os
import urllib.parse
from typing import Any, Callable, Dict, List, Optional, Tuple

from pravaha.ai.providers.base import CHAT_EXTRAS
from pravaha.ai.providers.http_base import JsonHttpProvider
from pravaha.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

FINISH = {"end_turn": "stop", "stop_sequence": "stop", "max_tokens": "length", "tool_use": "tool_calls",
          "content_filtered": "content_filter", "guardrail_intervened": "content_filter"}


def _encode(s: str) -> str:
    return urllib.parse.quote(s, safe="-_.~")


def sign(method: str, url: str, payload: bytes, content_type: str, access_key: str, secret_key: str,
         session_token: str, region: str, service: str,
         now: Optional[datetime.datetime] = None) -> Dict[str, str]:
    """AWS Signature Version 4: the headers that authenticate one request. The canonical URI encodes each
    path segment again, as every service but S3 requires."""
    when = now or datetime.datetime.now(datetime.timezone.utc)
    amz_date = when.strftime("%Y%m%dT%H%M%SZ")
    date = amz_date[:8]
    u = urllib.parse.urlsplit(url)
    default_port = (u.scheme == "https" and u.port == 443) or (u.scheme == "http" and u.port == 80)
    host = u.hostname or ""
    if u.port and not default_port:
        host = f"{host}:{u.port}"
    signed = {"host": host, "x-amz-date": amz_date}
    if content_type:
        signed["content-type"] = content_type
    if session_token:
        signed["x-amz-security-token"] = session_token
    names = sorted(signed)
    canonical_headers = "".join(f"{k}:{signed[k].strip()}\n" for k in names)
    signed_headers = ";".join(names)
    path = "/".join(_encode(seg) for seg in (u.path or "/").split("/")) or "/"
    query = "&".join(f"{k}={v}" for k, v in sorted(
        (p.split("=", 1)[0], p.split("=", 1)[1] if "=" in p else "") for p in u.query.split("&") if p))
    canonical = "\n".join([method, path, query, canonical_headers, signed_headers, hashlib.sha256(payload).hexdigest()])
    scope = f"{date}/{region}/{service}/aws4_request"
    to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical.encode("utf-8")).hexdigest()])
    key = ("AWS4" + secret_key).encode("utf-8")
    for part in (date, region, service, "aws4_request"):
        key = hmac.new(key, part.encode("utf-8"), hashlib.sha256).digest()
    signature = hmac.new(key, to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    out = {"x-amz-date": amz_date}
    if session_token:
        out["x-amz-security-token"] = session_token
    out["Authorization"] = (f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, SignedHeaders={signed_headers}, "
                            f"Signature={signature}")
    return out


class BedrockProvider(JsonHttpProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime: Any = None,
                 env: Callable[[str], Optional[str]] = os.environ.get) -> None:
        self.region = cfg.setting("region", "us-east-1")
        super().__init__(cfg, cfg.base_url or f"https://bedrock-runtime.{self.region}.amazonaws.com")
        self._control = (cfg.setting("control-url") or f"https://bedrock.{self.region}.amazonaws.com").rstrip("/")
        key, secret = cfg.setting("aws-access-key-id"), cfg.setting("aws-secret-access-key")
        if bool(key) != bool(secret):
            raise LlmError("config", "aws-access-key-id and aws-secret-access-key go together (as ${ENV} placeholders)")
        if key:
            self._creds = (key, secret, cfg.setting("aws-session-token"))
        else:
            self._creds = ((env("AWS_ACCESS_KEY_ID") or "").strip(), (env("AWS_SECRET_ACCESS_KEY") or "").strip(),
                           (env("AWS_SESSION_TOKEN") or "").strip())

    def auth(self, method: str, url: str, data: bytes) -> Dict[str, str]:
        if not (self._creds[0] and self._creds[1]):
            raise LlmError("config", self.check()[1])
        return sign(method, url, data, "application/json", self._creds[0], self._creds[1], self._creds[2], self.region, "bedrock")

    def _headers(self) -> Dict[str, str]:
        return super()._headers()

    def _model_url(self, model_id: str, verb: str) -> str:
        return f"{self.base}/model/{urllib.parse.quote(model_id, safe='')}/{verb}"

    def check(self) -> Tuple[bool, str]:
        if not (self._creds[0] and self._creds[1]):
            return False, ("no AWS credentials: set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (or aws-access-key-id / "
                           "aws-secret-access-key as ${ENV}); profiles and roles are not read")
        return True, f"configured for {self.region}; no call is made to find out"

    def live_models(self) -> List[Dict[str, str]]:
        j = self.get(self._control + "/foundation-models", 10.0)
        return [{"id": m["modelId"], "kind": "embedding" if "EMBEDDING" in (m.get("outputModalities") or []) else "chat"}
                for m in j.get("modelSummaries") or [] if m.get("modelId")]

    def probe(self) -> Tuple[bool, str]:
        ok, msg = self.check()
        if not ok:
            return ok, msg
        try:
            return True, f"{self.region}: {len(self.live_models())} foundation models listed"
        except LlmError as e:
            return False, e.message

    def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs = [{"role": "assistant" if m.role == "assistant" else "user", "content": [{"text": m.content}]}
                for m in req.messages if m.role != "system"]
        inf: Dict[str, Any] = {"maxTokens": self.max_tokens(req, info, 1024)}
        t = self.temperature(req, info)
        if t is not None:
            inf["temperature"] = t
        body: Dict[str, Any] = {"messages": msgs, "inferenceConfig": inf}
        sys_text = self.system_text(req)
        if sys_text:
            body["system"] = [{"text": sys_text}]
        if req.tools:
            body["toolConfig"] = {"tools": [{"toolSpec": {"name": s.name, **({"description": s.description} if s.description else {}),
                                                          "inputSchema": {"json": json.loads(s.parameters_json_schema)}}}
                                            for s in req.tools]}
        j = self.post(self._model_url(info.id, "converse"), body, req.timeout)
        blocks = ((j.get("output") or {}).get("message") or {}).get("content") or []
        text = "".join(b.get("text", "") for b in blocks if "text" in b)
        calls = tuple(ToolCall(b["toolUse"].get("toolUseId", ""), b["toolUse"].get("name", ""),
                               json.dumps(b["toolUse"].get("input") or {}, separators=(",", ":")))
                      for b in blocks if "toolUse" in b)
        if not text and not calls:
            raise LlmError("failed", "the model endpoint's answer has no text content")
        u = j.get("usage") or {}
        pi, co = int(u.get("inputTokens") or 0), int(u.get("outputTokens") or 0)
        finish = "tool_calls" if calls else FINISH.get(j.get("stopReason", "end_turn"), "stop")
        return ChatResponse(text, calls, info.id, finish, Usage(pi, co, info.cost(pi, co)))

    def embed(self, info: ModelInfo, texts: List[str]) -> List[List[float]]:
        out: Any
        if "cohere" in info.id:
            j = self.post(self._model_url(info.id, "invoke"),
                          {"texts": texts, "input_type": self.cfg.setting("embedding-input-type", "search_document")}, None)
            out = j.get("embeddings")
            if isinstance(out, dict):
                out = out.get("float")
        else:
            out = [self.post(self._model_url(info.id, "invoke"), {"inputText": t}, None).get("embedding") for t in texts]
        if not isinstance(out, list) or len(out) != len(texts) or not all(isinstance(v, list) for v in out):
            raise LlmError("failed", "the model endpoint returned the wrong number of embeddings")
        return out


def factory(cfg: ProviderConfig, runtime: Any = None) -> BedrockProvider:
    return BedrockProvider(cfg, runtime)


__all__ = ["BedrockProvider", "factory", "sign"]
