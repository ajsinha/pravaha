"""One JSON round trip to a model provider over ``urllib``, its failures normalised.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The mapping every built-in provider shares, before its own refinements:

* nothing answered, a timeout, a reset connection -- :class:`ModelUnavailable` (retryable);
* ``429`` -- :class:`ModelRateLimited`, with ``Retry-After`` (seconds or an HTTP date) or
  ``retry-after-ms`` when the provider sent one;
* ``408``, ``409``, ``5xx`` and Anthropic's ``529`` -- :class:`ModelUnavailable` (retryable);
* any other ``4xx`` -- :class:`ModelUnavailable`, not retryable: an unknown model, a refused key,
  a request too large. Another model may still answer it, so the router falls through;
* a ``2xx`` that is not JSON -- :class:`ModelUnavailable`.

A provider passes ``classify`` to recognise its own shapes first -- a content-policy refusal, a
quota that is exhausted rather than rate-limited. A key never appears in a message: keys travel in
headers, and only the URL and the provider's own error text are quoted.
"""

from __future__ import annotations

import email.utils
import json
import socket
import time
import urllib.error
import urllib.request
from typing import Any, Callable, Mapping, Optional

from pravaha.assist.errors import ModelError, ModelRateLimited, ModelUnavailable

#: ``classify(status, body) -> error or None``: a provider's own reading of a failure.
Classifier = Callable[[int, Any], Optional[ModelError]]


def retry_after(headers: Mapping[str, str]) -> Optional[float]:
    """Seconds to wait, from ``retry-after-ms``, or ``Retry-After`` as seconds or a date."""
    lowered = {k.lower(): v for k, v in headers.items()}
    if lowered.get("retry-after-ms"):
        try:
            return max(0.0, float(lowered["retry-after-ms"]) / 1000.0)
        except ValueError:
            pass
    value = lowered.get("retry-after")
    if not value:
        return None
    try:
        return max(0.0, float(value))
    except ValueError:
        pass
    try:
        when = email.utils.parsedate_to_datetime(value)
    except (TypeError, ValueError):
        return None
    return max(0.0, when.timestamp() - time.time())


def error_text(body: Any, fallback: str) -> str:
    """The provider's own words for a failure, from the shapes providers use."""
    if isinstance(body, dict):
        error = body.get("error")
        if isinstance(error, dict) and error.get("message"):
            return str(error["message"])
        if isinstance(error, str) and error:
            return error
        if body.get("message"):
            return str(body["message"])
    return fallback


def request_json(
    method: str,
    url: str,
    *,
    body: Any = None,
    headers: Optional[Mapping[str, str]] = None,
    timeout_s: float = 60.0,
    provider: str = "",
    model: str = "",
    classify: Optional[Classifier] = None,
) -> Any:
    """Sends ``body`` as JSON and answers the decoded JSON reply, or raises a normalised error."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    who: dict[str, Any] = {"provider": provider, "model": model or None}
    try:
        with urllib.request.urlopen(request, timeout=timeout_s) as response:
            raw = response.read()
    except urllib.error.HTTPError as failure:
        status = failure.code
        try:
            text = failure.read().decode("utf-8", "replace")
        except OSError:
            text = ""
        try:
            parsed: Any = json.loads(text) if text else None
        except ValueError:
            parsed = None
        if classify is not None:
            own = classify(status, parsed)
            if own is not None:
                own.status = own.status or status
                own.provider = own.provider or provider
                own.model = own.model or model or None
                raise own from None
        words = error_text(parsed, text.strip()[:300] or failure.reason or "no detail")
        header_map = dict(failure.headers.items()) if failure.headers else {}
        if status == 429:
            raise ModelRateLimited(
                f"rate limited: {words}", retry_after=retry_after(header_map), status=status, **who
            ) from None
        retryable = status in (408, 409) or status >= 500
        raise ModelUnavailable(
            f"HTTP {status} from {url}: {words}", retryable=retryable, status=status, **who
        ) from None
    except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        raise ModelUnavailable(f"nothing answered at {url}: {reason}", **who) from None
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except ValueError:
        raise ModelUnavailable(f"{url} answered with something that is not JSON", **who) from None


__all__ = ["Classifier", "error_text", "request_json", "retry_after"]
