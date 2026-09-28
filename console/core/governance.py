"""The catalogue's screens (ADR-059): namespaces, owners, descriptions, tags and grants.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The engine is the authority. Every answer here is its ``/api/v1/catalog`` endpoint, called as
the signed-in person, and every refusal is its: who may see an object (``USE`` on its namespace),
who may grant on it (``MANAGE``), who may give it away (its owner). The console decides nothing
and filters nothing -- it renders what the engine chose to show this person, and a refusal as
the screen's own state. A node with the catalogue off answers ``PRV-7030``, which the screens
show as "the catalogue is off" rather than as an error.
"""
from __future__ import annotations

from typing import Any

from core.engine import Engine
from core.services import ServiceError, _refusal

#: The engine's code for a node whose catalogue is off.
CATALOG_OFF = "PRV-7030"

#: Every privilege, in the order the grants editor offers them.
PRIVILEGES = ("USE", "SELECT", "SUBSCRIBE", "BUILD_ON", "CREATE", "WRITE", "MODIFY", "MANAGE")


def owner_text(owner: Any) -> str:
    if isinstance(owner, dict):
        return f"{owner.get('type', '')} {owner.get('name', '')}".strip()
    return str(owner or "")


def tag_pairs(tags: Any) -> list[str]:
    """``key`` or ``key=value``, in the engine's order."""
    if not isinstance(tags, dict):
        return []
    return [key if not value else f"{key}={value}" for key, value in tags.items()]


def parse_tags(text: str) -> dict[str, str]:
    """``domain=finance, certified`` as ``{"domain": "finance", "certified": ""}``."""
    tags: dict[str, str] = {}
    for part in (text or "").split(","):
        key, _, value = part.partition("=")
        if key.strip():
            tags[key.strip()] = value.strip()
    return tags


class GovernanceService:
    """The engine's catalogue, as this person may see and change it."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def _call(self, fn, status: int = 400):
        try:
            return fn()
        except Exception as exc:
            raise _refusal(exc, status) from exc

    @staticmethod
    def is_off(exc: ServiceError) -> bool:
        return (exc.code or "") == CATALOG_OFF

    def objects(self, namespace: str = "", kind: str = "", search: str = "") -> list[dict]:
        return self._call(lambda: self._engine.catalog_objects(
            namespace or None, kind or None, search or None), 503)

    def namespaces(self) -> list[dict]:
        return self._call(self._engine.catalog_namespaces, 503)

    def object(self, name: str) -> dict:
        return self._call(lambda: self._engine.catalog_object(name))

    def create_namespace(self, name: str, description: str = "") -> dict:
        if not (name or "").strip():
            raise ServiceError("a namespace needs a name", status=400, code="PRV-7037")
        return self._call(lambda: self._engine.create_namespace(name.strip(), description or ""))

    def change(self, name: str, *, description: str | None = None, tags: str | None = None,
               unset: str | None = None, owner: tuple[str, str] | None = None,
               namespace: str | None = None) -> dict:
        fields: dict[str, Any] = {}
        if description is not None:
            fields["description"] = description
        if tags:
            fields["setTags"] = parse_tags(tags)
        if unset:
            fields["unsetTags"] = [k.strip() for k in unset.split(",") if k.strip()]
        if owner is not None:
            fields["owner"] = {"type": owner[0], "name": owner[1]}
        if namespace:
            fields["namespace"] = namespace.strip()
        return self._call(lambda: self._engine.change_catalog_object(name, fields))

    def grants(self, on: str = "", grantee_type: str = "", grantee: str = "") -> list[dict]:
        return self._call(lambda: self._engine.grants(on or None, grantee_type or None, grantee or None))

    def grant(self, on: str, privileges: list[str], grantee_type: str, grantee: str) -> list[dict]:
        self._require(on, privileges, grantee_type, grantee)
        return self._call(lambda: self._engine.grant(on, privileges, grantee_type, grantee))

    def revoke(self, on: str, privileges: list[str], grantee_type: str, grantee: str) -> None:
        self._require(on, privileges, grantee_type, grantee)
        self._call(lambda: self._engine.revoke(on, privileges, grantee_type, grantee))

    def access(self, user: str, on: str) -> dict:
        return self._call(lambda: self._engine.access(user, on))

    # ------------------------------------------------ row filters and masks (ADR-059 s4)

    def policies(self, on: str = "") -> list[dict]:
        return self._call(lambda: self._engine.policies(on.strip() or None), 503)

    def create_policy(self, name: str, kind: str, expression: str, column: str = "",
                      except_roles: str = "", description: str = "") -> dict:
        # Shape only: whether the expression is one a policy may hold is the engine's to say.
        if not (name or "").strip() or not (expression or "").strip():
            raise ServiceError("a policy needs a name and an expression", status=400, code="PRV-7037")
        if kind not in ("ROW_FILTER", "MASK"):
            raise ServiceError("a policy is a ROW_FILTER or a MASK", status=400, code="PRV-7037")
        if kind == "MASK" and not (column or "").strip():
            raise ServiceError("a mask names the column it masks", status=400, code="PRV-7038")
        roles = [r.strip() for r in (except_roles or "").split(",") if r.strip()]
        return self._call(lambda: self._engine.create_policy(
            name.strip(), kind, expression.strip(), (column or "").strip() if kind == "MASK" else "",
            roles, description or ""))

    def bind_policy(self, name: str, on: str = "", tag: str = "") -> dict:
        self._place(name, on, tag)
        return self._call(lambda: self._engine.bind_policy(name.strip(), on.strip(), tag.strip()))

    def unbind_policy(self, name: str, on: str = "", tag: str = "") -> dict:
        self._place(name, on, tag)
        return self._call(lambda: self._engine.unbind_policy(name.strip(), on.strip(), tag.strip()))

    def drop_policy(self, name: str) -> None:
        if not (name or "").strip():
            raise ServiceError("say which policy to drop", status=400, code="PRV-7037")
        self._call(lambda: self._engine.drop_policy(name.strip()))

    @staticmethod
    def _place(name: str, on: str, tag: str) -> None:
        if not (name or "").strip() or bool((on or "").strip()) == bool((tag or "").strip()):
            raise ServiceError("a binding names a policy and exactly one of an object or a tag",
                               status=400, code="PRV-7037")

    @staticmethod
    def _require(on: str, privileges: list[str], grantee_type: str, grantee: str) -> None:
        # Shape only, so a half-filled form is refused before it is sent; whether the grant is
        # allowed is the engine's to say.
        if not (on or "").strip() or not privileges or not (grantee or "").strip():
            raise ServiceError("a grant names an object, at least one privilege, and a role or user",
                               status=400, code="PRV-7037")
        if grantee_type not in ("ROLE", "USER"):
            raise ServiceError("a grant is to a ROLE or a USER", status=400, code="PRV-7037")
