"""Fake vendor HTTP servers (loopback, an ephemeral port) that speak each API well enough for the AI
provider tests: no real network, ever. Ported from Drishti's ``tests/llm_fakes.py``; the engine's
``LlmFakes`` (pravaha-ai tests) keeps the same rules.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

``FakeServer(api)`` answers by one rule set in every API: the last user text is echoed (``echo: <text>``),
``weather`` asks for the tool ``get_weather`` with ``{"city":"Oslo"}``, ``boom`` is HTTP 500 (its body
echoes "secret prompt echo", which must never reach a message), ``limit`` is HTTP 429, ``slow`` sleeps two
seconds (a timeout test), and a request without the right credential is HTTP 401. Everything received is
kept in ``seen`` as ``(method, path-with-query, headers with lower-case names, body-text)``.
"""
from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

KEY = "k-123"
VEC = [0.1, 0.2, 0.3, 0.4]


def _last_user(messages):
    for m in reversed(messages):
        if m.get("role", "user") == "user":
            c = m.get("content")
            if isinstance(c, list):
                return "".join(p.get("text", "") for p in c)
            return c or ""
    return ""


class FakeServer:
    def __init__(self, api, key=KEY, sigv4_check=None):
        self.api, self.key, self.seen, self.sigv4_check = api, key, [], sigv4_check
        outer = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def _do(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
                hdr = {k: v for k, v in self.headers.items()}
                outer.seen.append((self.command, self.path, {k.lower(): v for k, v in hdr.items()}, body))
                status, out = outer.handle(self.command, self.path, hdr, body)
                data = json.dumps(out).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_POST = do_GET = _do

            def log_message(self, *a):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def authorised(self, hdr):
        h = {k.lower(): v for k, v in hdr.items()}
        a = self.api
        if a in ("openai", "cohere"):
            return h.get("authorization") == "Bearer " + self.key
        if a == "azure":
            return h.get("api-key") == self.key
        if a == "anthropic":
            return h.get("x-api-key") == self.key and bool(h.get("anthropic-version"))
        if a == "gemini":
            return h.get("x-goog-api-key") == self.key
        if a == "azure-entra":
            return h.get("authorization") == "Bearer entra-token-1"
        if a == "bedrock":
            return h.get("authorization", "").startswith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/")
        return True

    def handle(self, method, path, hdr, body):
        p = urlsplit(path).path
        if p.endswith("/oauth2/v2.0/token"):
            return 200, {"access_token": "entra-token-1", "expires_in": 3600, "token_type": "Bearer"}
        if not self.authorised(hdr):
            return 401, {"error": {"message": "bad credential"}}
        if self.sigv4_check and not self.sigv4_check(method, self.url + path, body.encode(), hdr):
            return 403, {"message": "signature does not match"}
        try:
            j = json.loads(body) if body else {}
        except ValueError:
            return 400, {"error": "bad json"}
        return getattr(self, "_" + self.api.replace("-", "_"))(method, p, j, hdr)

    def _gate(self, text):
        if "boom" in text:
            return 500, {"error": {"message": "secret prompt echo"}}
        if "limit" in text:
            return 429, {"error": {"message": "slow down"}}
        if "slow" in text:
            time.sleep(2)
        return None

    def _openai(self, method, p, j, hdr):
        if p.endswith("/models"):
            return 200, {"data": [{"id": "served-model"}, {"id": "served-embed"}]}
        if p.endswith("/embeddings"):
            return 200, {"data": [{"index": i, "embedding": VEC} for i, _ in enumerate(j["input"])]}
        text = _last_user(j["messages"])
        g = self._gate(text)
        if g:
            return g
        msg = {"role": "assistant", "content": "echo: " + text}
        fin = "stop"
        if "weather" in text:
            msg = {"role": "assistant", "content": None, "tool_calls": [{"id": "c1", "type": "function", "function": {
                "name": "get_weather", "arguments": "{\"city\":\"Oslo\"}"}}]}
            fin = "tool_calls"
        return 200, {"id": "x", "model": j["model"], "choices": [{"index": 0, "message": msg, "finish_reason": fin}],
                     "usage": {"prompt_tokens": 5, "completion_tokens": 3}}

    _azure = _azure_entra = _openai

    def _anthropic(self, method, p, j, hdr):
        text = _last_user(j["messages"])
        g = self._gate(text)
        if g:
            return g
        if "weather" in text:
            content, stop = [{"type": "tool_use", "id": "c1", "name": "get_weather", "input": {"city": "Oslo"}}], "tool_use"
        else:
            content, stop = [{"type": "text", "text": "echo: " + text}], "end_turn"
        return 200, {"id": "msg_1", "type": "message", "model": j["model"], "content": content, "stop_reason": stop,
                     "usage": {"input_tokens": 5, "output_tokens": 3}}

    def _gemini(self, method, p, j, hdr):
        if p.endswith("/models"):
            return 200, {"models": [{"name": "models/served-model", "supportedGenerationMethods": ["generateContent"]},
                                    {"name": "models/served-embed", "supportedGenerationMethods": ["embedContent"]}]}
        if p.endswith(":batchEmbedContents"):
            return 200, {"embeddings": [{"values": VEC} for _ in j["requests"]]}
        text = "".join(x.get("text", "") for x in j["contents"][-1]["parts"])
        g = self._gate(text)
        if g:
            return g
        parts = [{"text": "echo: " + text}]
        if "weather" in text:
            parts = [{"functionCall": {"name": "get_weather", "args": {"city": "Oslo"}}}]
        return 200, {"candidates": [{"content": {"role": "model", "parts": parts}, "finishReason": "STOP"}],
                     "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 3}}

    def _cohere(self, method, p, j, hdr):
        if p.endswith("/models"):
            return 200, {"models": [{"name": "served-model", "endpoints": ["chat"]}, {"name": "served-embed", "endpoints": ["embed"]}]}
        if p.endswith("/embed"):
            return 200, {"embeddings": {"float": [VEC for _ in j["texts"]]}}
        text = _last_user(j["messages"])
        g = self._gate(text)
        if g:
            return g
        msg = {"role": "assistant", "content": [{"type": "text", "text": "echo: " + text}]}
        fin = "COMPLETE"
        if "weather" in text:
            msg = {"role": "assistant", "tool_calls": [{"id": "c1", "type": "function", "function": {
                "name": "get_weather", "arguments": "{\"city\":\"Oslo\"}"}}]}
            fin = "TOOL_CALL"
        return 200, {"message": msg, "finish_reason": fin, "usage": {"tokens": {"input_tokens": 5, "output_tokens": 3}}}

    def _ollama(self, method, p, j, hdr):
        if p.endswith("/api/tags"):
            return 200, {"models": [{"name": "llama3.2:latest"}, {"name": "nomic-embed-text:latest"}]}
        if p.endswith("/api/embed"):
            return 200, {"embeddings": [VEC for _ in j["input"]]}
        text = _last_user(j["messages"])
        g = self._gate(text)
        if g:
            return g
        msg = {"role": "assistant", "content": "echo: " + text}
        if "weather" in text:
            msg = {"role": "assistant", "content": "", "tool_calls": [{"function": {"name": "get_weather", "arguments": {"city": "Oslo"}}}]}
        return 200, {"model": j["model"], "message": msg, "done": True, "done_reason": "stop", "prompt_eval_count": 5, "eval_count": 3}

    def _bedrock(self, method, p, j, hdr):
        if p.endswith("/foundation-models"):
            return 200, {"modelSummaries": [{"modelId": "anthropic.claude-sonnet-5-5", "outputModalities": ["TEXT"]},
                                            {"modelId": "amazon.titan-embed-text-v2:0", "outputModalities": ["EMBEDDING"]}]}
        if p.endswith("/invoke"):
            if "texts" in j:
                return 200, {"embeddings": {"float": [VEC for _ in j["texts"]]}}
            return 200, {"embedding": VEC}
        text = "".join(b.get("text", "") for b in j["messages"][-1]["content"])
        g = self._gate(text)
        if g:
            return g
        blocks = [{"text": "echo: " + text}]
        stop = "end_turn"
        if "weather" in text:
            blocks, stop = [{"toolUse": {"toolUseId": "c1", "name": "get_weather", "input": {"city": "Oslo"}}}], "tool_use"
        return 200, {"output": {"message": {"role": "assistant", "content": blocks}}, "stopReason": stop,
                     "usage": {"inputTokens": 5, "outputTokens": 3}}


class FakeEngine:
    """The engine's AI API on loopback, for the ``server`` provider: answers ``POST /api/v1/ai/chat`` as the
    engine would (echo; ``nope`` as a model is PRV-1082; ``limit`` PRV-1085; ``off`` PRV-1080), and keeps
    every request."""

    def __init__(self, token="tok-1"):
        self.seen, self.token = [], token
        outer = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode() or "{}")
                outer.seen.append((self.path, dict(self.headers.items()), body))
                status, out = outer.answer(self.headers.get("Authorization", ""), body)
                data = json.dumps(out).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *a):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def answer(self, auth, body):
        def err(status, code, message):
            return status, {"code": code, "message": message, "helpUrl": "", "timestamp": "", "path": "/api/v1/ai/chat"}
        if auth != "Bearer " + self.token:
            return err(401, "PRV-7001", "the credential was not accepted")
        text = body["messages"][-1]["content"] if body.get("messages") else ""
        if body.get("model") == "nope":
            return err(404, "PRV-1082", "no chat model 'nope' is configured")
        if "limit" in text:
            return err(429, "PRV-1085", "too many calls; try again in 30 seconds")
        if "off" in text:
            return err(409, "PRV-1080", "the AI layer is switched off")
        if "slow" in text:
            return err(504, "PRV-1086", "the model endpoint did not answer within 30s")
        return 200, {"text": "engine: " + text, "toolCalls": [], "model": "mock-1", "finishReason": "stop",
                     "usage": {"promptTokens": 4, "completionTokens": 2, "cost": 0.5}}

    def close(self):
        self.server.shutdown()
        self.server.server_close()
