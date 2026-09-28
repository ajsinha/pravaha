"""The engine's catalogue (ADR-059), answered from memory for the fake engine.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Keeps the engine's contract where the console can see it: a person sees an object when they may
use its namespace (their tenant's ``default`` always) or hold something on it; changing one needs
MANAGE, which the ``admin`` role and the owner hold; an object the caller may not see is 404
``PRV-7031``, a change they may not make is 403 ``PRV-7033``, a privilege that means nothing on
the kind is 400 ``PRV-7032``, and a node with the catalogue off answers 409 ``PRV-7030``.
The rules themselves are pinned in ``pravaha-catalog``'s tests; this is a stand-in, not a copy.
"""
from __future__ import annotations

from core.engine import EngineHttpError

APPLICABLE = {
    "NAMESPACE": ["USE", "SELECT", "SUBSCRIBE", "BUILD_ON", "CREATE", "WRITE", "MODIFY", "MANAGE"],
    "VIEW": ["SELECT", "SUBSCRIBE", "BUILD_ON", "MODIFY", "MANAGE"],
    "STREAM": ["SELECT", "BUILD_ON", "MODIFY", "MANAGE"],
    "SINK": ["WRITE", "MANAGE"],
}


def _object(name, kind, owner, description="", tags=None, engine_name=""):
    return {"name": name, "kind": kind, "engineName": engine_name, "tenant": name.split(".")[0],
            "namespace": name.rsplit(".", 1)[0] if "." in name else "*", "shortName": name.rsplit(".", 1)[-1],
            "owner": {"type": owner[0], "name": owner[1]}, "description": description, "tags": dict(tags or {}),
            "createdAt": "2026-09-28T09:00:00Z", "createdBy": "admin", "updatedAt": "2026-09-28T09:00:00Z",
            "updatedBy": "admin", "version": 1}


class FakeCatalog:
    def __init__(self, engine) -> None:
        self._engine = engine
        self.on = True
        self.objects: dict[str, dict] = {}
        for item in (
            _object("public.default", "NAMESPACE", ("ROLE", "admin")),
            _object("public.default.big_txn", "VIEW", ("USER", "admin"), "Transactions over 500",
                    {"domain": "payments"}, "big_txn"),
            _object("public.sales", "NAMESPACE", ("USER", "admin"), "Order-to-cash"),
            _object("public.sales.revenue", "VIEW", ("USER", "admin"), "Revenue per region",
                    {"domain": "finance", "certified": ""}, "revenue"),
            _object("node.streams.txn", "STREAM", ("ROLE", "admin"), "", None, "txn"),
        ):
            self.objects[item["name"]] = item
        self.grants: list[dict] = []

    # ------------------------------------------------------------------ who is asking
    def _me(self) -> dict:
        self._engine._check()
        if not self.on:
            raise EngineHttpError(409, "PRV-7030 this node's catalogue is off (pravaha.catalog.enabled is false)",
                                  "PRV-7030")
        return self._engine._as_identity(lambda t: self._engine.identity.me(t))

    @staticmethod
    def _covers(grant: dict, who: dict) -> bool:
        if grant["granteeType"] == "USER":
            return grant["grantee"] == who["username"]
        return grant["grantee"] in who.get("roles", []) or grant["grantee"] in ("public", "authenticated")

    def _holds(self, who: dict, privilege: str, name: str) -> list[str]:
        if "admin" in who.get("roles", []):
            return ["the admin role, which holds every right"]
        found = []
        level = name
        while True:
            item = self.objects.get(level)
            if item and ((item["owner"]["type"] == "USER" and item["owner"]["name"] == who["username"])
                         or (item["owner"]["type"] == "ROLE" and item["owner"]["name"] in who.get("roles", []))):
                found.append("owner of " + level)
            for g in self.grants:
                if g["object"] == level and g["privilege"] == privilege and self._covers(g, who):
                    found.append(f"grant {privilege} on {level} to {g['granteeType']} {g['grantee']}")
            if level == "*" or "." not in level:
                break
            level = level.rsplit(".", 1)[0]
        return found

    def _visible(self, who: dict, item: dict) -> bool:
        if "admin" in who.get("roles", []):
            return True
        namespace = item["name"] if item["kind"] == "NAMESPACE" else item["namespace"]
        if namespace == f"{who.get('tenant') or 'public'}.default" or namespace.startswith("node."):
            return True
        return bool(self._holds(who, "USE", namespace)) or any(
            self._holds(who, p, item["name"]) for p in APPLICABLE.get(item["kind"], []))

    def _resolve(self, who: dict, name: str) -> dict:
        tenant = who.get("tenant") or "public"
        for candidate in (name, f"{tenant}.{name}", f"{tenant}.default.{name}"):
            item = self.objects.get(candidate)
            if item and self._visible(who, item):
                return item
        for item in self.objects.values():
            if item["engineName"] == name and self._visible(who, item):
                return item
        raise EngineHttpError(404, f"PRV-7031 there is no object {name} that you may see", "PRV-7031")

    def _manage(self, who: dict, item: dict) -> None:
        if not self._holds(who, "MANAGE", item["name"]):
            raise EngineHttpError(403, f"PRV-7033 {who['username']} may not change {item['name']}: that needs "
                                       "MANAGE or ownership", "PRV-7033")

    # ------------------------------------------------------------------ the endpoints
    def catalog_objects(self, namespace=None, kind=None, search=None):
        who = self._me()
        needle = (search or "").lower()
        return [dict(o) for o in self.objects.values()
                if self._visible(who, o) and (not namespace or o["namespace"] == namespace)
                and (not kind or o["kind"] == kind.upper())
                and (not needle or needle in o["name"].lower() or needle in o["description"].lower()
                     or any(needle in k.lower() or needle in v.lower() for k, v in o["tags"].items()))]

    def catalog_namespaces(self):
        who = self._me()
        return [dict(o) for o in self.objects.values()
                if o["kind"] == "NAMESPACE" and o["name"].count(".") == 1 and self._visible(who, o)]

    def catalog_object(self, name):
        who = self._me()
        item = self._resolve(who, name)
        manager = bool(self._holds(who, "MANAGE", item["name"]))
        grants = [dict(g) for g in self.grants if g["object"] == item["name"] and (manager or self._covers(g, who))]
        return {"object": dict(item), "grants": grants, "access": self._access(who, item)}

    def _access(self, subject: dict, item: dict) -> dict:
        lines = []
        for privilege in APPLICABLE.get(item["kind"], []):
            via = self._holds(subject, privilege, item["name"])
            lines.append({"privilege": privilege, "allowed": bool(via), "via": via,
                          "refusal": "" if via else f"{subject['username']} holds no {privilege} on {item['name']}"})
        return {"object": item["name"], "user": subject["username"], "privileges": lines}

    def access(self, user, on):
        who = self._me()
        item = self._resolve(who, on)
        if user != who["username"]:
            self._manage(who, item)
        known = self._engine.identity.users.get(user)
        if known is None:
            raise EngineHttpError(400, f"PRV-7037 there is no user '{user}' this node knows of", "PRV-7037")
        subject = {"username": user, "roles": list(known.roles), "tenant": known.tenant}
        return self._access(subject, item)

    def create_namespace(self, name, description=""):
        who = self._me()
        full = name if "." in name else f"{who.get('tenant') or 'public'}.{name}"
        if "admin" not in who.get("roles", []):
            raise EngineHttpError(403, f"PRV-7033 {who['username']} may not create a namespace", "PRV-7033")
        if full in self.objects:
            raise EngineHttpError(409, f"PRV-7036 the namespace '{full}' exists already", "PRV-7036")
        self.objects[full] = _object(full, "NAMESPACE", ("USER", who["username"]), description)
        return dict(self.objects[full])

    def change_catalog_object(self, name, fields):
        who = self._me()
        item = self._resolve(who, name)
        self._manage(who, item)
        if "description" in fields:
            item["description"] = fields["description"]
        item["tags"].update(fields.get("setTags") or {})
        for key in fields.get("unsetTags") or []:
            item["tags"].pop(key, None)
        if fields.get("owner"):
            item["owner"] = dict(fields["owner"])
        item["version"] += 1
        return dict(item)

    def grant_list(self, on=None, grantee_type=None, grantee=None):
        who = self._me()
        if on:
            item = self._resolve(who, on)
            manager = bool(self._holds(who, "MANAGE", item["name"]))
            return [dict(g) for g in self.grants if g["object"] == item["name"] and (manager or self._covers(g, who))]
        return [dict(g) for g in self.grants if g["grantee"] == grantee and g["granteeType"] == (grantee_type or "USER")]

    def grant(self, on, privileges, grantee_type, grantee):
        who = self._me()
        item = self._resolve(who, on)
        self._manage(who, item)
        wanted = [p for p in privileges if p != "ALL"] or APPLICABLE[item["kind"]]
        for privilege in wanted:
            if privilege not in APPLICABLE.get(item["kind"], []):
                raise EngineHttpError(400, f"PRV-7032 {privilege} means nothing on a {item['kind']}", "PRV-7032")
        made = []
        for privilege in wanted:
            grant = {"object": item["name"], "privilege": privilege, "granteeType": grantee_type,
                     "grantee": grantee, "grantedBy": who["username"], "grantedAt": "2026-09-28T10:00:00Z"}
            if not any(g["object"] == grant["object"] and g["privilege"] == privilege
                       and g["granteeType"] == grantee_type and g["grantee"] == grantee for g in self.grants):
                self.grants.append(grant)
            made.append(grant)
        return made

    def revoke(self, on, privileges, grantee_type, grantee):
        who = self._me()
        item = self._resolve(who, on)
        self._manage(who, item)
        self.grants = [g for g in self.grants
                       if not (g["object"] == item["name"] and g["privilege"] in privileges
                               and g["granteeType"] == grantee_type and g["grantee"] == grantee)]
