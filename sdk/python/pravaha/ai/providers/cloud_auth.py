"""Microsoft Entra ID tokens for Azure OpenAI, with ``urllib`` and no SDK. Two flows: ``client_secret``
(the client-credentials grant with ``tenant-id``, ``client-id`` and ``client-secret`` as an ``${ENV}``) and
``workload_identity`` (the same grant with a federated token read from ``federated-token-file``). ``auto``
takes the secret if there is one, else the federated file. **Managed identity is not built.** A token is
cached until a minute before it expires; the secret and the token are never logged. The request goes
through the provider's own opener, so the proxy and TLS settings apply to it too.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Dict, Tuple

from pravaha.ai.types import LlmError

ASSERTION = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"


class EntraTokenSource:
    def __init__(self, opener: urllib.request.OpenerDirector, settings: Dict[str, str]) -> None:
        self._opener = opener
        self.mode = settings.get("entra-mode", "auto").lower()
        self.tenant = settings.get("tenant-id", "")
        self.client_id = settings.get("client-id", "")
        self.secret = settings.get("client-secret", "")
        self.federated = settings.get("federated-token-file", "")
        self.scope = settings.get("entra-scope", "https://cognitiveservices.azure.com/.default")
        self.authority = settings.get("entra-authority", "https://login.microsoftonline.com").rstrip("/")
        self._token, self._expires = "", 0.0
        self._lock = threading.Lock()

    def effective_mode(self) -> str:
        if self.mode == "auto":
            return "client_secret" if self.secret else "workload_identity" if self.federated else "managed_identity"
        return self.mode

    def check(self) -> Tuple[bool, str]:
        m = self.effective_mode()
        if m == "managed_identity":
            return False, "entra: managed identity is not built; give client-secret or federated-token-file"
        if not (self.tenant and self.client_id):
            return False, "entra: tenant-id and client-id are required (AZURE_TENANT_ID, AZURE_CLIENT_ID)"
        if m == "client_secret" and not self.secret:
            return False, "entra: client-secret (an ${ENV} placeholder) is required"
        if m == "workload_identity" and not self.federated:
            return False, "entra: federated-token-file is required"
        return True, f"entra ({m})"

    def token(self) -> str:
        with self._lock:
            if self._token and time.time() < self._expires - 60:
                return self._token
            ok, why = self.check()
            if not ok:
                raise LlmError("config", why)
            form = {"grant_type": "client_credentials", "client_id": self.client_id, "scope": self.scope}
            if self.effective_mode() == "client_secret":
                form["client_secret"] = self.secret
            else:
                try:
                    form.update(client_assertion_type=ASSERTION,
                                client_assertion=Path(self.federated).read_text(encoding="utf-8").strip())
                except OSError:
                    raise LlmError("config", "entra: the federated token file cannot be read") from None
            req = urllib.request.Request(f"{self.authority}/{self.tenant}/oauth2/v2.0/token",
                                         data=urllib.parse.urlencode(form).encode("utf-8"), method="POST",
                                         headers={"Content-Type": "application/x-www-form-urlencoded",
                                                  "Accept": "application/json"})
            try:
                with self._opener.open(req, timeout=30) as resp:
                    j = json.loads(resp.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                e.close()
                raise LlmError("failed", f"entra: the token request was refused (HTTP {e.code}); check tenant-id, "
                               f"client-id and the secret") from None
            except (urllib.error.URLError, OSError, ValueError):
                raise LlmError("failed", "entra: the token endpoint could not be reached or answered something "
                               "unreadable") from None
            if not isinstance(j, dict) or not isinstance(j.get("access_token"), str):
                raise LlmError("failed", "entra: the token answer is unreadable")
            self._token, self._expires = j["access_token"], time.time() + float(j.get("expires_in", 3600))
            return self._token


__all__ = ["ASSERTION", "EntraTokenSource"]
