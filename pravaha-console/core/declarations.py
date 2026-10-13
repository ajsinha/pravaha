"""Streams, named connections, lookups and sinks as files the console writes (ADR-064).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The files are the single source of truth. A node reads ``config/streams/<name>.yaml`` and its
siblings; the console's forms are another way to write the same file, through the engine
(``POST/PUT/DELETE /api/v1/streams/{name}`` and its siblings), which writes it atomically in its
folder and applies it at once. Every refusal is the engine's -- a change to an object a query uses
(``PRV-1065``), a literal secret (``PRV-1063``), an unknown plugin -- and so is every permission. The
console adds two things: a form that builds the file's fields, and a word beside a secret-looking
option before it is sent, because a literal secret is refused and a person deserves to know why
before they press Save.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

import yaml

from core.engine import Engine
from core.services import ServiceError, _refusal

#: The column types a stream's schema may name (the engine's ``name:TYPE`` grammar); ``?`` makes one
#: nullable. ``DECIMAL(p,s)`` takes its precision and scale.
TYPES = ("BOOLEAN", "INT8", "INT16", "INT32", "INT64", "FLOAT32", "FLOAT64", "STRING", "BYTES",
         "DATE", "TIME", "TIMESTAMP", "DECIMAL(18,2)")

#: A setting that holds a secret, by the engine's rule (``DeclarationFile.isSecretKey``).
_SECRET = re.compile(r".*(password|secret|token|api[_-]?key).*", re.IGNORECASE)

#: What may stand in a secret setting: an ``${ENV_NAME}`` reference (or a flag, count or duration).
_SAFE = re.compile(r"|\$\{[^}]*\}|true|false|[0-9]+|[0-9]+(ms|s|m|h|d)", re.IGNORECASE)

#: The names a file can have: letters, digits, ``_`` and ``-``, at most 128.
NAME = re.compile(r"[A-Za-z0-9_-]{1,128}")

#: The kinds of declaration file, as the engine's folders and paths name them.
KINDS = ("streams", "connectors", "lookups", "sinks")


def is_secret_key(key: str) -> bool:
    """Whether an option's key names a secret: its last segment says password, secret, token or key."""
    last = re.sub(r"\[\d+]$", "", key.rsplit(".", 1)[-1])
    lower = last.lower()
    return bool(_SECRET.fullmatch(last)) and not lower.endswith(("-file", "-env"))


def literal_secret(key: str, value: str) -> bool:
    """A secret-looking option holding a value rather than ``${ENV_NAME}``: refused by the engine."""
    return is_secret_key(key) and not _SAFE.fullmatch((value or "").strip())


@dataclass
class Column:
    name: str = ""
    type: str = "STRING"
    nullable: bool = False


def parse_schema(text: str) -> list[Column]:
    """``id:INT64,amount:DECIMAL(18,2)?`` as columns. Commas inside a type's brackets are kept."""
    columns: list[Column] = []
    depth, part = 0, ""
    for ch in (text or "") + ",":
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            name, _, kind = part.strip().partition(":")
            if name.strip():
                kind = kind.strip() or "STRING"
                nullable = kind.endswith("?")
                columns.append(Column(name.strip(), kind.rstrip("?").strip().upper(), nullable))
            part = ""
        else:
            part += ch
    return columns


def schema_text(columns: list[Column]) -> str:
    """Columns as the engine's spelling: ``name:TYPE``, ``?`` for nullable."""
    return ",".join(f"{c.name.strip()}:{c.type.strip().upper()}{'?' if c.nullable else ''}"
                    for c in columns if c.name.strip())


def _flat(prefix: str, value: Any, out: dict[str, str]) -> None:
    if isinstance(value, dict):
        for key, inner in value.items():
            _flat(f"{prefix}.{key}" if prefix else str(key), inner, out)
    else:
        out[prefix] = "" if value is None else str(value).lower() if isinstance(value, bool) else str(value)


def _options(value: Any) -> list[tuple[str, str]]:
    flat: dict[str, str] = {}
    if isinstance(value, dict):
        _flat("", value, flat)
    return list(flat.items())


def _text(value: Any) -> str:
    return "" if value is None else str(value)


def _rows(form: Any, prefix: str, count_field: str, maximum: int = 200) -> int:
    try:
        return max(0, min(maximum, int(str(form.get(count_field) or "0"))))
    except ValueError:
        return 0


def _option_rows(form: Any) -> list[tuple[str, str]]:
    rows = []
    for i in range(_rows(form, "opt", "options")):
        key = str(form.get(f"opt-{i}-key") or "").strip()
        value = str(form.get(f"opt-{i}-value") or "")
        rows.append((key, value))
    return rows


def _option_problems(options: list[tuple[str, str]]) -> list[str]:
    return [key for key, value in options if key and literal_secret(key, value)]


@dataclass
class StreamForm:
    """The New/Edit stream form: the fields of ``config/streams/<name>.yaml``."""

    name: str = ""
    columns: list[Column] = field(default_factory=lambda: [Column()])
    event_time: str = ""
    out_of_orderness: str = ""
    allowed_lateness: str = ""
    enabled: bool = True
    source: str = "none"  # none | plugin | connector
    plugin: str = ""
    connector: str = ""
    options: list[tuple[str, str]] = field(default_factory=list)
    yaml_text: str = ""

    @classmethod
    def from_form(cls, form: Any) -> StreamForm:
        columns = []
        for i in range(_rows(form, "col", "columns")):
            columns.append(Column(str(form.get(f"col-{i}-name") or "").strip(),
                                  str(form.get(f"col-{i}-type") or "STRING").strip().upper() or "STRING",
                                  bool(form.get(f"col-{i}-nullable"))))
        source = str(form.get("source") or "none")
        return cls(name=str(form.get("name") or "").strip(), columns=columns or [Column()],
                   event_time=str(form.get("event_time") or "").strip(),
                   out_of_orderness=str(form.get("out_of_orderness") or "").strip(),
                   allowed_lateness=str(form.get("allowed_lateness") or "").strip(),
                   enabled=str(form.get("enabled") or "true") != "false",
                   source=source if source in ("none", "plugin", "connector") else "none",
                   plugin=str(form.get("plugin") or "").strip(),
                   connector=str(form.get("connector") or "").strip(),
                   options=_option_rows(form), yaml_text=str(form.get("yaml") or ""))

    @classmethod
    def from_file(cls, name: str, text: str) -> StreamForm:
        """A stream file's fields, for the Edit form. Comments are not fields: saving the form
        rewrites the file without them, which the page says."""
        data = yaml.safe_load(text or "") or {}
        if not isinstance(data, dict):
            data = {}
        source = data.get("source") if isinstance(data.get("source"), dict) else None
        return cls(name=name, columns=parse_schema(_text(data.get("schema"))) or [Column()],
                   event_time=_text(data.get("event-time")),
                   out_of_orderness=_text(data.get("out-of-orderness")),
                   allowed_lateness=_text(data.get("allowed-lateness")),
                   enabled=data.get("enabled") is not False and _text(data.get("enabled")).lower() != "false",
                   source=("connector" if source and source.get("connector") else
                           "plugin" if source and source.get("plugin") else "none"),
                   plugin=_text((source or {}).get("plugin")), connector=_text((source or {}).get("connector")),
                   options=_options((source or {}).get("options")), yaml_text=text)

    def schema(self) -> str:
        return schema_text(self.columns)

    def secret_problems(self) -> list[str]:
        return _option_problems(self.options) if self.source != "none" else []

    def body(self) -> dict:
        """The engine's ``StreamFileRequest``."""
        source = None
        if self.source != "none":
            source = {"plugin": (self.plugin or None) if self.source == "plugin" else None,
                      "connector": (self.connector or None) if self.source == "connector" else None,
                      "options": {k: v for k, v in self.options if k}}
        return {"schema": self.schema(), "eventTime": self.event_time or None,
                "outOfOrderness": self.out_of_orderness or None, "allowedLateness": self.allowed_lateness or None,
                "enabled": self.enabled, "source": source}


@dataclass
class ConnectionForm:
    """The New/Edit connection form: the fields of ``config/connectors/<name>.yaml``."""

    name: str = ""
    plugin: str = ""
    options: list[tuple[str, str]] = field(default_factory=lambda: [("", "")])
    enabled: bool = True
    yaml_text: str = ""

    @classmethod
    def from_form(cls, form: Any) -> ConnectionForm:
        return cls(name=str(form.get("name") or "").strip(), plugin=str(form.get("plugin") or "").strip(),
                   options=_option_rows(form) or [("", "")],
                   enabled=str(form.get("enabled") or "true") != "false", yaml_text=str(form.get("yaml") or ""))

    @classmethod
    def from_file(cls, name: str, text: str) -> ConnectionForm:
        data = yaml.safe_load(text or "") or {}
        if not isinstance(data, dict):
            data = {}
        return cls(name=name, plugin=_text(data.get("plugin")), options=_options(data.get("options")) or [("", "")],
                   enabled=_text(data.get("enabled")).lower() != "false", yaml_text=text)

    def secret_problems(self) -> list[str]:
        return _option_problems(self.options)

    def body(self) -> dict:
        """The engine's ``BindingFileRequest``."""
        return {"plugin": self.plugin or None, "options": {k: v for k, v in self.options if k},
                "enabled": self.enabled}


class DeclarationService:
    """The engine's declaration files, as this person may see and change them."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    @staticmethod
    def _call(fn, status: int = 400):
        try:
            return fn()
        except Exception as exc:
            raise _refusal(exc, status) from exc

    @staticmethod
    def _kind(kind: str) -> str:
        if kind not in KINDS:
            raise ServiceError(f"'{kind}' is not one of {', '.join(KINDS)}", status=404)
        return kind

    def connectors(self) -> list[dict]:
        return self._call(self._engine.connectors, 503)

    def declarations(self) -> dict:
        return self._call(self._engine.declarations, 503)

    def file(self, kind: str, name: str) -> dict:
        return self._call(lambda: self._engine.declaration_file(self._kind(kind), name))

    def write(self, kind: str, name: str, body: dict, *, update: bool, dry_run: bool) -> dict:
        if not NAME.fullmatch(name or ""):
            raise ServiceError("a name is letters, digits, '_' and '-', at most 128", status=400, code="PRV-1061")
        return self._call(lambda: self._engine.write_declaration(
            self._kind(kind), name, body, update=update, dry_run=dry_run))

    def delete(self, kind: str, name: str, *, dry_run: bool) -> dict:
        return self._call(lambda: self._engine.delete_declaration(self._kind(kind), name, dry_run=dry_run))

    def switch(self, kind: str, name: str, enabled: bool, *, dry_run: bool) -> dict:
        return self._call(lambda: self._engine.switch_declaration(self._kind(kind), name, enabled, dry_run=dry_run))

    def test(self, name: str, options: dict | None = None, timeout_seconds: int = 10) -> dict:
        return self._call(lambda: self._engine.test_connector(name, options, timeout_seconds))


__all__ = ["KINDS", "TYPES", "Column", "ConnectionForm", "DeclarationService", "StreamForm", "is_secret_key",
           "literal_secret", "parse_schema", "schema_text"]
