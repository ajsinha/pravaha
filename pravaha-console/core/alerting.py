"""The alert screens (ADR-057): what is firing, one alert's keys and notifications, and the four
things a person does to an alert -- pause, resume, snooze, acknowledge.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The engine is the authority. Every answer here is its ``/api/v1/alerts`` endpoint, called as the
signed-in person, and every refusal is its: an alert this person may not see is not there
(``PRV-8040``), one they may see and not change is refused (``PRV-7002``), and a node serving no
alerts answers ``PRV-8047``, which the screens show as their own state.
"""
from __future__ import annotations

import re

from core.engine import Engine
from core.services import ServiceError, _refusal

#: The engine's code for a node that serves no alerts.
ALERTS_OFF = "PRV-8047"

_DURATION = re.compile(r"^(\d{1,9}\s*(ms|s|m|h|d)|P[0-9A-Z.]+)$", re.IGNORECASE)


def key_text(key: object) -> str:
    """``sku=sku-100, warehouse=LDN``: how a key is written, and what an acknowledgement names."""
    if not isinstance(key, dict) or not key:
        return "(the whole answer)"
    return ", ".join(f"{k}={v}" for k, v in key.items())


class AlertsService:
    """The engine's alerts, as this person may see and change them."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def _call(self, fn, status: int = 400):
        try:
            return fn()
        except Exception as exc:
            raise _refusal(exc, status) from exc

    @staticmethod
    def is_off(exc: ServiceError) -> bool:
        return (exc.code or "") == ALERTS_OFF

    def alerts(self) -> list[dict]:
        return self._call(self._engine.alerts, 503)

    def alert(self, name: str) -> dict:
        return self._call(lambda: self._engine.alert(name))

    def channels(self) -> list[dict]:
        return self._call(self._engine.alert_channels, 503)

    def pause(self, name: str) -> dict:
        return self._call(lambda: self._engine.pause_alert(name))

    def resume(self, name: str) -> dict:
        return self._call(lambda: self._engine.resume_alert(name))

    def snooze(self, name: str, duration: str) -> dict:
        # Shape only, so a typo is refused before it is sent; how long is the engine's to judge.
        if not _DURATION.match((duration or "").strip()):
            raise ServiceError(f"'{duration}' is not a duration; write 30m, 2h, 1d or PT2H",
                               status=400, code="PRV-8042")
        return self._call(lambda: self._engine.snooze_alert(name, duration.strip()))

    def ack(self, name: str, key: str | None = None) -> dict:
        return self._call(lambda: self._engine.ack_alert(name, (key or "").strip() or None))
