"""The signed-in person's account, and the administration of users, keys and sessions (ADR-052).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Every answer here is the engine's, and every decision is too. The console checks no password,
key or session and keeps no user table: it asks the engine, as the person signed in, and shows
what came back. A refusal keeps the engine's code and message (``PRV-7010`` credentials refused,
``PRV-7011`` locked, ``PRV-7012`` a password the policy refuses, ``PRV-7015`` a key that would
widen its holder's roles), because the engine's words are the ones that say which rule was broken.

What the console does add is shape: a record the engine answers is read defensively (a field it
did not send is absent, never invented), and a secret the engine hands back once -- a new key,
a reset token -- goes straight to the page that shows it and is not kept anywhere else.
"""
from __future__ import annotations

from typing import Any

from core.services import ServiceError, _refusal

#: A key's expiry, as the ADR bounds it: 90 days unless asked, at most 365.
KEY_DAYS_DEFAULT = 90
KEY_DAYS_MAX = 365


class AccountService:
    """The engine's identity endpoints, as the account and admin screens use them."""

    def __init__(self, engine: Any) -> None:
        self._engine = engine

    def _call(self, fn, *args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except ServiceError:
            raise
        except Exception as exc:  # every refusal keeps the engine's code
            raise _refusal(exc) from exc

    # ------------------------------------------------------------------ signing in
    def login(self, username: str, password: str) -> dict:
        return self._call(self._engine.login, username, password)

    def logout(self) -> None:
        self._call(self._engine.logout)

    def me(self) -> dict:
        return self._call(self._engine.me)

    def change_password(self, current: str, new: str) -> None:
        self._call(self._engine.change_password, current, new)

    def redeem_reset(self, token: str, password: str) -> None:
        self._call(self._engine.redeem_reset, token, password)

    # ------------------------------------------------------------------ keys and sessions
    def keys(self, all_keys: bool = False) -> list[dict]:
        return [key_record(k) for k in self._call(self._engine.keys, all_keys)]

    def create_key(self, name: str, roles: list[str], days: int, for_user: str | None = None) -> dict:
        return self._call(self._engine.create_key, name, roles, days, for_user)

    def rotate_key(self, key_id: str) -> dict:
        return self._call(self._engine.rotate_key, key_id)

    def revoke_key(self, key_id: str) -> None:
        self._call(self._engine.revoke_key, key_id)

    def key_report(self) -> dict:
        raw = self._call(self._engine.key_report) or {}
        return {part: [key_record(k) for k in (raw.get(part) or []) if isinstance(k, dict)]
                for part in ("unused", "expiring", "superseded")}

    def sessions(self, all_sessions: bool = False) -> list[dict]:
        return [session_record(s) for s in self._call(self._engine.sessions, all_sessions)]

    def end_session(self, session_id: str) -> None:
        self._call(self._engine.end_session, session_id)

    # ------------------------------------------------------------------ users (admin)
    def users(self) -> list[dict]:
        rows = [user_record(u) for u in self._call(self._engine.users)]
        return sorted(rows, key=lambda u: u["username"])

    def create_user(self, fields: dict) -> dict:
        return self._call(self._engine.create_user, fields)

    def set_status(self, username: str, status: str) -> dict:
        return self._call(self._engine.update_user, username, {"status": status})

    def set_roles(self, username: str, roles: list[str]) -> dict:
        return self._call(self._engine.set_roles, username, roles)

    def reset_password(self, username: str) -> dict:
        return self._call(self._engine.reset_password, username)


def roles_from(text: str | list | None) -> list[str]:
    """Roles as a person types them -- "operator, developer" -- or as checkboxes send them."""
    if isinstance(text, list):
        parts = text
    else:
        parts = str(text or "").replace(";", ",").split(",")
    seen: list[str] = []
    for part in parts:
        role = str(part).strip()
        if role and role not in seen:
            seen.append(role)
    return seen


def expiry_days(text: str | int | None) -> int:
    """A key's expiry in days, as the form sent it. The engine enforces the bound; this only
    refuses what is not a number, so a typo is named before a round trip."""
    raw = str(text if text is not None else "").strip()
    if not raw:
        return KEY_DAYS_DEFAULT
    try:
        return int(raw)
    except ValueError:
        raise ServiceError(f"'{raw}' is not a number of days", status=400) from None


def _text(value: Any) -> str:
    return "" if value is None else str(value)


def user_record(raw: dict) -> dict:
    return {
        "username": _text(raw.get("username")),
        "displayName": _text(raw.get("displayName")),
        "email": _text(raw.get("email")),
        "tenant": _text(raw.get("tenant")),
        "roles": [str(r) for r in (raw.get("roles") or [])],
        "status": _text(raw.get("status") or "active"),
        "mustChangePassword": bool(raw.get("mustChangePassword")),
        "lockedUntil": _text(raw.get("lockedUntil")),
        "lastLoginAt": _text(raw.get("lastLoginAt")),
    }


def key_record(raw: dict) -> dict:
    """A key as the engine lists it: its id, never a secret. A field named like a secret is
    dropped even if an engine ever sent one, because this record reaches a page."""
    return {
        "keyId": _text(raw.get("keyId")),
        "name": _text(raw.get("name")),
        "holder": _text(raw.get("holder") or raw.get("username") or raw.get("user")),
        "roles": [str(r) for r in (raw.get("roles") or [])],
        "createdAt": _text(raw.get("createdAt")),
        "expiresAt": _text(raw.get("expiresAt")),
        "lastUsedAt": _text(raw.get("lastUsedAt")),
        "status": _text(raw.get("status") or ("revoked" if raw.get("revokedAt") else "active")),
        "supersededBy": _text(raw.get("supersededBy")),
    }


def session_record(raw: dict) -> dict:
    return {
        "id": _text(raw.get("id") or raw.get("sessionId")),
        "username": _text(raw.get("username") or raw.get("user")),
        "createdAt": _text(raw.get("createdAt")),
        "lastSeenAt": _text(raw.get("lastSeenAt") or raw.get("lastUsedAt")),
        "expiresAt": _text(raw.get("expiresAt")),
        "current": bool(raw.get("current")),
        "client": _text(raw.get("client") or raw.get("userAgent") or raw.get("address")),
    }
