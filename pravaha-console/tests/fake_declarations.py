"""The engine's declaration files (ADR-064), answered from memory, for ``fake_engine.FakeEngine``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Keeps the engine's contract for the console's declaration pages: a write is a file, a dry run writes
nothing and answers the file it would write, a change or removal of what a query uses is refused
(``PRV-1065``) and writes nothing, disabling is never refused for being in use, a literal secret is
refused (``PRV-1063``), and a connection's options come back with every secret masked.
"""
from __future__ import annotations

import re

from core.engine import EngineHttpError

_SECRET = re.compile(r".*(password|secret|token|api[_-]?key).*", re.IGNORECASE)
_SAFE = re.compile(r"|\$\{[^}]*\}|true|false|[0-9]+|[0-9]+(ms|s|m|h|d)", re.IGNORECASE)


def _secret(key: str) -> bool:
    return bool(_SECRET.fullmatch(key.rsplit(".", 1)[-1]))


def _render(kind: str, name: str, body: dict) -> str:
    if body.get("yaml"):
        return str(body["yaml"])
    lines = [f"# {kind[:-1].capitalize()} {name}, written through the Pravaha API by admin."]
    if kind == "streams":
        lines.append(f'schema: "{body.get("schema") or ""}"')
        for key, field in (("event-time", "eventTime"), ("out-of-orderness", "outOfOrderness"),
                           ("allowed-lateness", "allowedLateness")):
            if body.get(field):
                lines.append(f"{key}: {body[field]}")
        if body.get("enabled") is False:
            lines.append("enabled: false")
        source = body.get("source")
        if source:
            lines.append("source:")
            for key in ("connector", "plugin"):
                if source.get(key):
                    lines.append(f"  {key}: {source[key]}")
            if source.get("options"):
                lines.append("  options:")
                lines += [f"    {k}: {v}" for k, v in sorted(source["options"].items())]
    else:
        if body.get("enabled") is False:
            lines.append("enabled: false")
        for key in ("connector", "plugin"):
            if body.get(key):
                lines.append(f"{key}: {body[key]}")
        if body.get("options"):
            lines.append("options:")
            lines += [f"  {k}: {v}" for k, v in sorted(body["options"].items())]
    return "\n".join(lines) + "\n"


def _options(body: dict) -> dict:
    if body.get("yaml"):
        found = {}
        for line in str(body["yaml"]).splitlines():
            key, sep, value = line.strip().partition(":")
            if sep and key not in ("schema", "plugin", "connector", "options", "source", "enabled"):
                found[key] = value.strip()
        return found
    return dict((body.get("source") or {}).get("options") or body.get("options") or {})


class FakeDeclarations:
    """Files by kind and name, and the connections they declare."""

    def __init__(self, engine) -> None:
        self._engine = engine
        self.files: dict[tuple[str, str], dict] = {
            ("streams", "txn"): {"yaml": 'schema: "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP"\n'
                                         "event-time: event_time\nout-of-orderness: 10s\nsource:\n  connector: files\n"
                                         "  options:\n    path: data/incoming/txn.csv\n", "enabled": True},
            ("connectors", "files"): {"yaml": "plugin: filesystem\noptions:\n  delimiter: ','\n", "enabled": True,
                                      "plugin": "filesystem", "options": {"delimiter": ","}},
        }
        #: Every write the console made, as (verb, kind, name, dryRun).
        self.calls: list[tuple] = []
        #: The outcome a connection test answers.
        self.test_outcome = {"ok": True, "role": "source", "latencyMillis": 42, "state": "HEALTHY",
                             "detail": "reachable", "code": None}

    @staticmethod
    def _path(kind: str, name: str) -> str:
        return f"/opt/pravaha/conf/{kind}/{name}.yaml"

    def _users(self, kind: str, name: str) -> list[str]:
        if kind == "connectors":
            return [f"source:{n}" for (k, n), f in self.files.items()
                    if k == "streams" and f"connector: {name}" in f["yaml"]]
        if kind == "streams":
            return [q.name for q in self._engine._queries if re.search(rf"\bFROM\s+{re.escape(name)}\b", q.sql, re.I)]
        return []

    def _held(self, kind: str, name: str) -> dict:
        held = self.files.get((kind, name))
        if held is None:
            raise EngineHttpError(404, f"PRV-1071 no file in the {kind} folder declares '{name}'", "PRV-1071")
        return held

    def _answer(self, kind, name, action, dry_run, yaml, previous, dependents) -> dict:
        return {"kind": kind[:-1], "name": name, "action": action, "dryRun": dry_run, "applied": not dry_run,
                "valid": True, "path": self._path(kind, name), "yaml": yaml, "previous": previous,
                "dependents": dependents, "code": None, "message": None}

    def connectors(self) -> list[dict]:
        self._engine._check()
        return [{"name": n, "plugin": f.get("plugin", ""), "origin": "file:" + self._path("connectors", n),
                 "options": {k: ("****" if _secret(k) else v) for k, v in (f.get("options") or {}).items()},
                 "usedBy": self._users("connectors", n), "enabled": f["enabled"]}
                for (k, n), f in sorted(self.files.items()) if k == "connectors"]

    def declaration_file(self, kind: str, name: str) -> dict:
        self._engine._check()
        held = self._held(kind, name)
        return {"kind": kind[:-1], "name": name, "path": self._path(kind, name), "yaml": held["yaml"],
                "enabled": held["enabled"], "dependents": self._users(kind, name)}

    def write_declaration(self, kind: str, name: str, body: dict, *, update: bool, dry_run: bool) -> dict:
        self._engine._check()
        self.calls.append(("PUT" if update else "POST", kind, name, dry_run))
        previous = self.files.get((kind, name))
        if not update and previous is not None:
            raise EngineHttpError(409, f"PRV-1070 {kind[:-1]} '{name}' is already declared", "PRV-1070")
        if update and previous is None:
            raise EngineHttpError(404, f"PRV-1071 no file in the {kind} folder declares '{name}'", "PRV-1071")
        for key, value in _options(body).items():
            if _secret(key) and not _SAFE.fullmatch(str(value).strip()):
                raise EngineHttpError(400, f"PRV-1063 {kind}/{name}.yaml: {key} holds a literal value. Write it as "
                                           "${ENV_NAME}.", "PRV-1063")
        yaml = _render(kind, name, body)
        users = self._users(kind, name) if previous else []
        changed = previous is not None and previous["yaml"].replace("enabled: false\n", "") != yaml.replace(
            "enabled: false\n", "")
        if users and changed:
            raise EngineHttpError(409, f"PRV-1065 {kind[:-1]} '{name}' is in use by {', '.join(users)}, so it cannot be "
                                       "changed while they run. Nothing was written.", "PRV-1065")
        if not dry_run:
            entry = {"yaml": yaml, "enabled": body.get("enabled") is not False and "enabled: false" not in yaml}
            if kind == "connectors":
                entry.update(plugin=body.get("plugin") or "", options=_options(body))
            self.files[(kind, name)] = entry
            if kind == "streams" and previous is None:
                schema = body.get("schema") or ""
                fields = [{"name": p.split(":")[0], "type": p.split(":")[-1], "nullable": p.endswith("?"),
                           "ordinal": i} for i, p in enumerate(schema.split(",")) if p]
                self._engine.streams_list.append({
                    "name": name, "version": 1, "fieldCount": len(fields), "fields": fields,
                    "eventTime": body.get("eventTime"), "outOfOrderness": None, "source": None,
                    "origin": "file:" + self._path(kind, name), "notApplied": None, "enabled": entry["enabled"],
                    "connector": None, "dependents": 0})
        return self._answer(kind, name, "update" if update else "create", dry_run, yaml,
                            previous["yaml"] if previous else None, users)

    def delete_declaration(self, kind: str, name: str, *, dry_run: bool) -> dict:
        self._engine._check()
        self.calls.append(("DELETE", kind, name, dry_run))
        held = self._held(kind, name)
        users = self._users(kind, name)
        if users:
            raise EngineHttpError(409, f"PRV-1065 {kind[:-1]} '{name}' is in use by {', '.join(users)}, so it cannot be "
                                       "removed while they run. Nothing was written.", "PRV-1065")
        if not dry_run:
            del self.files[(kind, name)]
            self._engine.streams_list[:] = [s for s in self._engine.streams_list
                                            if not (kind == "streams" and s["name"] == name)]
        return self._answer(kind, name, "delete", dry_run, None, held["yaml"], users)

    def switch_declaration(self, kind: str, name: str, enabled: bool, *, dry_run: bool) -> dict:
        self._engine._check()
        self.calls.append(("ENABLE" if enabled else "DISABLE", kind, name, dry_run))
        held = self._held(kind, name)
        before = held["yaml"]
        text = before.replace("enabled: false\n", "") + ("" if enabled else "enabled: false\n")
        if not dry_run:
            held.update(yaml=text, enabled=enabled)
            for stream in self._engine.streams_list:
                if kind == "streams" and stream["name"] == name:
                    stream["enabled"] = enabled
        return self._answer(kind, name, "enable" if enabled else "disable", dry_run, text, before,
                            self._users(kind, name))

    def test_connector(self, name: str, options=None, timeout_seconds: int = 10) -> dict:
        self._engine._check()
        self.calls.append(("TEST", "connectors", name, False))
        held = self._held("connectors", name)
        return {"name": name, "plugin": held.get("plugin", ""), **self.test_outcome}
