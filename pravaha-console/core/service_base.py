"""The service layer's shared vocabulary: its error, and engine values as JSON.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Split out of ``core.services`` (CONSOLESIZE-1), which re-exports every name here.
A module importing ``ServiceError`` or ``_refusal`` from ``core.services`` keeps working.
"""
from __future__ import annotations

from typing import Any


class ServiceError(Exception):
    """Something the caller should be told about, with a status to send."""

    def __init__(self, message: str, status: int = 400, code: str | None = None) -> None:
        super().__init__(message)
        self.status = status
        # The engine's PRV code when there is one, so the UI can link to TROUBLESHOOTING
        # rather than showing a wall of text with the useful part buried in it.
        self.code = code


#: The admission refusals ADR-050 answers with 409: the tenant's query quota and its state quota.
QUOTA_CODES = frozenset({"PRV-8020", "PRV-8021"})


def _code_in(message: str) -> str | None:
    """Pulls a PRV code out of an engine message, if it carries one."""
    marker = message.find("PRV-")
    if marker < 0:
        return None
    tail = message[marker : marker + 12]
    code = tail.split()[0].strip(".,:;")
    return code if len(code) > 4 else None


def plain_decimal(value: Any) -> str:
    """A decimal as its digits, never in exponent form.

    ``str`` writes a ``DECIMAL(38, 10)`` zero as ``0E-10`` and one ten-billionth as ``1E-10``:
    the same number, in a form nobody reading a ledger expects and a spreadsheet pasting it
    may not parse. ``format(value, "f")`` keeps every digit at the column's scale (FLIGHTDECIMAL-1).
    """
    return format(value, "f")


def json_default(value: Any) -> Any:
    """``json.dumps``'s ``default`` for engine values: a decimal plain, anything else as text."""
    import decimal as _decimal

    if isinstance(value, _decimal.Decimal):
        return plain_decimal(value)
    return str(value)


def jsonable(value: Any) -> Any:
    """Engine values as JSON: a timestamp as ISO-8601, a decimal as a string, bytes as hex.

    A decimal becomes a string rather than a float on purpose -- a money column rounded
    by the console on its way to the browser would be a wrong number on the one screen
    people copy numbers from -- and a plain one, at its column's scale (:func:`plain_decimal`).
    """
    import datetime as _dt
    import decimal as _decimal
    import math as _math

    if isinstance(value, list):
        return [jsonable(v) for v in value]
    if isinstance(value, tuple):
        return [jsonable(v) for v in value]
    if isinstance(value, dict):
        return {str(k): jsonable(v) for k, v in value.items()}
    if isinstance(value, (_dt.datetime, _dt.date, _dt.time)):
        return value.isoformat()
    if isinstance(value, _dt.timedelta):
        return value.total_seconds()
    if isinstance(value, _decimal.Decimal):
        return plain_decimal(value)
    if isinstance(value, (bytes, bytearray)):
        return bytes(value).hex()
    if isinstance(value, float) and (_math.isnan(value) or _math.isinf(value)):
        return None
    return value


def _refusal(exc: Exception, status: int = 400) -> ServiceError:
    """An engine refusal as a ServiceError, keeping the engine's own code and status."""
    code = getattr(exc, "code", None) or _code_in(str(exc))
    http = getattr(exc, "status", None)
    if http == 0:
        # Did not answer at all: the engine is down or the URL is wrong, which is retryable.
        return ServiceError(str(exc), status=503, code=code)
    if isinstance(http, int) and 400 <= http < 600:
        status = 503 if http >= 500 else http
    return ServiceError(str(exc), status=status, code=code)
