"""The engine's published HTTP API, for the calls that have no Flight form.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Why this is HTTP and not a set of new Flight actions: validation, plans, the stream
catalogue, node status, sinks and per-query descriptions are each implemented once, in
``pravaha-server``'s REST controllers, behind the same authorization the Flight surface
applies. Re-implementing them as Flight actions would be a second copy of each judgement --
two places deciding what a principal may learn about the catalogue -- and two copies of an
authorization rule drift in the direction of whichever one somebody forgot to change. The
REST surface is also the versioned, OpenAPI-locked contract (``api/openapi.lock.json``), so
this module calls exactly what any third party could call.

Standard library only (``urllib``): a client should not add an HTTP dependency to the
environment it is installed into for a dozen JSON calls.
"""

from __future__ import annotations

import json
import ssl
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

from pravaha import tracecontext
from pravaha.errors import InvalidOptionsError, PravahaError
from pravaha.tls import TlsOptions


class ApiError(PravahaError):
    """The engine's HTTP API refused, or did not answer.

    ``status`` is the HTTP status (``0`` when nothing answered). ``code`` is the engine's
    own number when it gave one -- ``7002`` for ``PRV-7002`` -- so one page documents the
    refusal whichever client surfaced it; otherwise the client's ``1040`` (could not reach
    it, retryable) or ``1041`` (refused), matching :class:`pravaha.client.ConnectError` and
    :class:`pravaha.client.QueryError`. ``engine_code`` is the engine's text form, or ``None``.
    The message is the engine's own.
    """

    def __init__(
        self, status: int, message: str, code: str | None = None, body: Any = None
    ) -> None:
        number = _number_of(code)
        if number is None:
            number = 1040 if status == 0 else 1041
        super().__init__(number, message, retryable=status == 0 or status >= 500)
        self.status = status
        self.engine_code = code
        #: The engine's own words, without the ``PRV-nnnn`` prefix ``str()`` adds.
        self.message = message
        #: The decoded JSON body of the refusal, or ``None`` when it had none or was not JSON.
        #: A health probe answers ``503`` with the same document it answers ``200`` with, and
        #: reading that document is the point of asking.
        self.body = body


def _number_of(code: str | None) -> int | None:
    if not code or not code.upper().startswith("PRV-"):
        return None
    try:
        return int(code[4:])
    except ValueError:
        return None


class RestClient:
    """A thin, authenticated JSON client for one engine's HTTP surface."""

    def __init__(
        self,
        base_url: str,
        *,
        token: str | None = None,
        timeout_seconds: float = 30.0,
        tls: TlsOptions | None = None,
        allow_insecure_token: bool = False,
    ) -> None:
        parsed = urllib.parse.urlparse(base_url or "")
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise InvalidOptionsError(
                f"the engine's HTTP URL must be http:// or https:// with a host, got {base_url!r}"
            )
        if token and parsed.scheme == "http" and not allow_insecure_token:
            # The same rule the Flight side applies: a bearer token over plaintext is handed
            # to anyone on the path.
            raise InvalidOptionsError(
                f"refusing to send a token over plaintext HTTP to {base_url}; use https://, "
                "remove the token, or pass allow_insecure_token=True for a loopback engine"
            )
        self._base = base_url.rstrip("/")
        self._token = token
        self._timeout = timeout_seconds
        self._context = _ssl_context(tls) if parsed.scheme == "https" else None

    @property
    def base_url(self) -> str:
        return self._base

    def get(self, path: str, query: dict[str, Any] | None = None) -> Any:
        return json.loads(self._call("GET", path, None, query).decode("utf-8") or "null")

    def post(self, path: str, body: Any, query: dict[str, Any] | None = None) -> Any:
        return json.loads(self._call("POST", path, body, query).decode("utf-8") or "null")

    def put(self, path: str, body: Any, query: dict[str, Any] | None = None) -> Any:
        return self.request("PUT", path, body, query)

    def patch(self, path: str, body: Any, query: dict[str, Any] | None = None) -> Any:
        return self.request("PATCH", path, body, query)

    def delete(self, path: str, query: dict[str, Any] | None = None) -> Any:
        return self.request("DELETE", path, None, query)

    def request(
        self,
        method: str,
        path: str,
        body: Any = None,
        query: dict[str, Any] | None = None,
    ) -> Any:
        """Any verb, with the same headers, TLS and error decoding as :meth:`get` and :meth:`post`.

        The decoded JSON answer, or ``None`` for an empty one -- ``204 No Content`` is how the
        engine answers a logout, a revocation or a password change.
        """
        return json.loads(self._call(method.upper(), path, body, query).decode("utf-8") or "null")

    def text(self, path: str) -> str:
        return self._call("GET", path, None, None, accept="text/plain").decode("utf-8", errors="replace")

    def _call(
        self,
        method: str,
        path: str,
        body: Any,
        query: dict[str, Any] | None,
        accept: str = "application/json",
    ) -> bytes:
        url = self._base + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Accept", accept)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self._token:
            request.add_header("Authorization", "Bearer " + self._token)
        # W3C trace context, when the caller has a trace (pravaha.tracecontext): the node continues it.
        for name, value in tracecontext.headers().items():
            request.add_header(name, value)
        try:
            with urllib.request.urlopen(request, timeout=self._timeout, context=self._context) as response:
                answer: bytes = response.read()
                return answer
        except urllib.error.HTTPError as exc:
            payload = exc.read()
            body_json = _decoded(payload)
            if isinstance(body_json, dict):
                message = str(body_json.get("message") or body_json.get("error") or exc)
                code = str(body_json["code"]) if body_json.get("code") else None
            else:
                message, code = payload.decode("utf-8", errors="replace")[:500] or str(exc), None
            raise ApiError(exc.code, message, code, body_json) from exc
        except (urllib.error.URLError, OSError) as exc:
            reason = getattr(exc, "reason", exc)
            raise ApiError(0, f"the engine's HTTP API at {self._base} did not answer: {reason}") from exc


def _decoded(payload: bytes) -> Any:
    try:
        return json.loads(payload.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None


def _ssl_context(tls: TlsOptions | None) -> ssl.SSLContext:
    """The same trust the Flight connection is given, for the HTTPS one."""
    tls = tls or TlsOptions()
    if tls.disable_hostname_verification:
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        return context
    context = ssl.create_default_context(
        cafile=str(tls.ca_certificate) if tls.ca_certificate is not None else None
    )
    if tls.client_certificate is not None and tls.client_key is not None:
        context.load_cert_chain(str(tls.client_certificate), str(tls.client_key))
    return context
