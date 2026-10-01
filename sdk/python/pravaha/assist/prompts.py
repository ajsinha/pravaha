"""The assistant's packaged resources: versioned prompts, their answer schemas, and the dialect
card generated from docs/guides/CONTINUOUS_QUERIES.md.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A prompt is a file, not a string in the code: ``resources/prompts/<name>.v<N>.txt`` beside
``<name>.v<N>.schema.json``. Changing what the assistant asks is a new version -- the old file
stays, so an answer can always be traced to the exact words that produced it (every result
carries ``prompt: "<name>@v<N>"``). A prompt file is ``#`` comment lines, then a ``=== system``
section and a ``=== user`` section, and optionally further named sections for later turns of the
same conversation (``=== repair``); ``$name`` placeholders are filled with
:class:`string.Template`, which fails loudly on one left unfilled.
"""

from __future__ import annotations

import dataclasses
import functools
import json
import re
import string
from importlib import resources
from typing import Any, Mapping, Optional

from pravaha.assist.schema import check_schema

_PACKAGE = "pravaha.assist"
_PROMPT_FILE = re.compile(r"^(?P<name>[a-z0-9_]+)\.v(?P<version>\d+)\.txt$")


def _resource_dir(*parts: str) -> Any:
    node = resources.files(_PACKAGE).joinpath("resources")
    for part in parts:
        node = node.joinpath(part)
    return node


@dataclasses.dataclass(frozen=True)
class Prompt:
    """One version of one prompt, and the schema its answer must satisfy."""

    name: str
    version: int
    system: str
    user: str
    schema: Optional[Mapping[str, Any]]
    #: Further named sections, for later turns (``repair``).
    extra: Mapping[str, str] = dataclasses.field(default_factory=dict)

    @property
    def id(self) -> str:
        return f"{self.name}@v{self.version}"

    def render(self, **values: str) -> "tuple[str, str]":
        """``(system, user)`` with every ``$placeholder`` filled."""
        return (
            string.Template(self.system).substitute(values),
            string.Template(self.user).substitute(values),
        )

    def render_section(self, section: str, **values: str) -> str:
        """The named further section, with every ``$placeholder`` filled."""
        if section not in self.extra:
            raise LookupError(f"prompt {self.id} has no section {section!r}")
        return string.Template(self.extra[section]).substitute(values)


def _parse(name: str, version: int, text: str, schema: Optional[Mapping[str, Any]]) -> Prompt:
    body = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
    parts = re.split(r"^=== ([a-z_]+)\s*$", body, flags=re.MULTILINE)
    sections: dict[str, str] = {}
    for i in range(1, len(parts) - 1, 2):
        if parts[i] in sections:
            raise ValueError(f"prompt {name} v{version} has two '=== {parts[i]}' sections")
        sections[parts[i]] = parts[i + 1].strip("\n")
    if not {"system", "user"} <= set(sections):
        raise ValueError(f"prompt {name} v{version} needs a '=== system' and a '=== user' section")
    extra = {k: v.strip() for k, v in sections.items() if k not in ("system", "user")}
    return Prompt(name, version, sections["system"].strip(), sections["user"].strip(), schema,
                  extra)


def prompt_versions(name: str) -> "list[int]":
    """Every packaged version of ``name``, oldest first."""
    versions = []
    for entry in _resource_dir("prompts").iterdir():
        match = _PROMPT_FILE.match(entry.name)
        if match and match.group("name") == name:
            versions.append(int(match.group("version")))
    return sorted(versions)


@functools.lru_cache(maxsize=None)
def load_prompt(name: str, version: Optional[int] = None) -> Prompt:
    """Prompt ``name`` at ``version``, or its newest."""
    versions = prompt_versions(name)
    if not versions:
        raise LookupError(f"no packaged prompt {name!r}")
    chosen = versions[-1] if version is None else version
    if chosen not in versions:
        raise LookupError(f"prompt {name!r} has no version {chosen} (has {versions})")
    folder = _resource_dir("prompts")
    text = folder.joinpath(f"{name}.v{chosen}.txt").read_text(encoding="utf-8")
    schema_file = folder.joinpath(f"{name}.v{chosen}.schema.json")
    schema: Optional[dict[str, Any]] = None
    if schema_file.is_file():
        schema = json.loads(schema_file.read_text(encoding="utf-8"))
        schema.pop("$comment", None)  # the file's header; not something to send a provider
        check_schema(schema)
    return _parse(name, chosen, text, schema)


# ---------------------------------------------------------------------------------- the card


@dataclasses.dataclass(frozen=True)
class Excerpt:
    anchor: str
    title: str
    text: str


class DialectCard:
    """What docs/guides/CONTINUOUS_QUERIES.md says about each ``PRV-nnnn`` code."""

    def __init__(self, document: Mapping[str, Any]) -> None:
        self._document = document

    @property
    def source(self) -> str:
        return str(self._document.get("source", ""))

    @property
    def version(self) -> str:
        """The first twelve hex digits of the guide's SHA-256 the card was built from."""
        return str(self._document.get("sourceSha256", ""))[:12]

    def codes(self) -> "list[str]":
        return sorted(self._document.get("codes", {}))

    def knows(self, code: str) -> bool:
        return code in self._document.get("codes", {})

    def means(self, code: str) -> Optional[str]:
        entry = self._document.get("codes", {}).get(code) or {}
        value = entry.get("means")
        return str(value) if value else None

    def table(self) -> "list[tuple[str, str]]":
        """Every code the guide's error table explains, with its line, in code order."""
        return [(code, str(entry.get("means"))) for code, entry in
                sorted(self._document.get("codes", {}).items()) if entry.get("means")]

    def section(self, anchor: str) -> Optional[Excerpt]:
        """One section of the card by its anchor, or ``None``."""
        section = self._document.get("sections", {}).get(anchor)
        if not section:
            return None
        return Excerpt(anchor, str(section.get("title", anchor)), str(section.get("text", "")))

    def excerpts(self, code: str, *, limit_chars: int = 14000) -> "list[Excerpt]":
        """The guide's sections about ``code``, in the guide's order, then its general advice on
        refusals; up to ``limit_chars`` in all. The first section about the code and the general
        advice are always included."""
        sections: Mapping[str, Any] = self._document.get("sections", {})
        general = self._document.get("general")
        anchors = [a for a in (self._document.get("codes", {}).get(code) or {}).get("sections", [])
                   if a != general and a in sections]

        def excerpt(anchor: str) -> Excerpt:
            section = sections[anchor]
            return Excerpt(anchor, str(section.get("title", anchor)), str(section.get("text", "")))

        closing = [excerpt(str(general))] if general and general in sections else []
        used = sum(len(e.text) for e in closing)
        chosen: list[Excerpt] = []
        for anchor in anchors:
            candidate = excerpt(anchor)
            if chosen and used + len(candidate.text) > limit_chars:
                continue
            chosen.append(candidate)
            used += len(candidate.text)
        return chosen + closing


@functools.lru_cache(maxsize=1)
def load_card() -> DialectCard:
    """The packaged dialect card."""
    text = _resource_dir("dialect-card.json").read_text(encoding="utf-8")
    return DialectCard(json.loads(text))


__all__ = [
    "DialectCard",
    "Excerpt",
    "Prompt",
    "load_card",
    "load_prompt",
    "prompt_versions",
]
