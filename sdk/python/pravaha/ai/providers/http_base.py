"""The HTTP part the JSON providers share, over ``urllib``: one opener per provider (``tls.*``,
``proxy.*``), JSON calls, retries on 429 and 5xx, and the error mapping. A status becomes an ``LlmError``
without the body (a body can echo a prompt): 429 is ``rate_limited``, a timeout is ``timeout``, everything
else ``failed`` with a hint for the usual causes. Subclasses override ``auth`` for headers that depend on
the request (an Entra token, SigV4).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
import re
import socket
import urllib.error
import urllib.request
from typing import Any, Dict, Optional

from pravaha.ai.providers.base import BaseProvider, opener
from pravaha.ai.types import LlmError, ProviderConfig

_HINTS = {401: "authentication failed: check the api key and where it is read from",
          403: "access denied: the key lacks permission for this model or endpoint",
          404: "not found: check base-url, the path and the model id",
          408: "the endpoint timed out",
          413: "the request is too large",
          429: "rate limited: slow down, raise the account limit or add retries"}
SECRETISH = re.compile(r"(?i)(^|.*[-_.])(key|secret|token|password|passwd|authorization|auth|cookie|credential|credentials)([-_.].*)?$")


def status_error(code: int) -> LlmError:
    hint = _HINTS.get(code)
    return LlmError("rate_limited" if code == 429 else "failed",
                    f"the model endpoint answered HTTP {code}" + (f" ({hint})" if hint else ""))


def _timed_out(e: BaseException) -> bool:
    reason = getattr(e, "reason", e)
    return isinstance(reason, (socket.timeout, TimeoutError)) or "timed out" in str(reason)


class JsonHttpProvider(BaseProvider):
    """A provider that speaks JSON over HTTP to ``base``."""

    def __init__(self, cfg: ProviderConfig, base: str) -> None:
        super().__init__(cfg)
        self.base = base.rstrip("/")
        self._opener = opener(cfg)

    def _headers(self) -> Dict[str, str]:
        h = {"Content-Type": "application/json", "Accept": "application/json"}
        h.update({k[7:]: v for k, v in self.cfg.settings.items() if k.startswith("header.")})
        return h

    def auth(self, method: str, url: str, data: bytes) -> Dict[str, str]:
        """The authentication headers of one request (the whole signed request, for SigV4). Default: none."""
        return {}

    def request(self, method: str, url: str, body: Optional[Dict[str, Any]] = None,
                timeout: Optional[float] = None) -> Dict[str, Any]:
        data = json.dumps(body).encode("utf-8") if body is not None else b""
        err: Optional[LlmError] = None
        for _ in range(self.cfg.retries + 1):
            h = self._headers()
            h.update(self.auth(method, url, data))
            req = urllib.request.Request(url, data=data if body is not None else None, method=method, headers=h)
            try:
                with self._opener.open(req, timeout=timeout or self.cfg.timeout) as resp:
                    raw = resp.read()
            except urllib.error.HTTPError as e:
                e.close()
                err = status_error(e.code)
                if e.code < 500 and e.code != 429:
                    break
                continue
            except (urllib.error.URLError, OSError) as e:
                err = (LlmError("timeout", "the model endpoint did not answer in time") if _timed_out(e)
                       else LlmError("failed", "the model endpoint could not be reached or answered something unreadable"))
                continue
            try:
                j = json.loads(raw.decode("utf-8"))
            except ValueError:
                err = LlmError("failed", "the model endpoint answered something unreadable")
                continue
            if isinstance(j, dict):
                return j
            err = LlmError("failed", "the model endpoint answered something unreadable")
        raise err or LlmError("failed", "the model call failed")

    def post(self, url: str, body: Dict[str, Any], timeout: Optional[float] = None) -> Dict[str, Any]:
        return self.request("POST", url, body, timeout)

    def get(self, url: str, timeout: Optional[float] = None) -> Dict[str, Any]:
        return self.request("GET", url, None, timeout)


__all__ = ["JsonHttpProvider", "SECRETISH", "status_error"]
