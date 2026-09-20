"""Every ``PRV-nnnn`` code, resolved to what the documentation says about it.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Design 23.4b: every code resolves to a page. The engine's own ``helpUrl`` points at a host
that does not exist (TROUBLESHOOTING.md says so), and an air-gapped browser could not reach
it if it did, so the workbench links each diagnostic here instead.

Read from the repository's own documents, never copied: the code table in
TROUBLESHOOTING.md is generated from the source, and every other mention of a code is a
paragraph somebody wrote about it. A page assembled from those cannot drift from them.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

CODE = re.compile(r"^PRV-\d{4}$")

#: Where a code is discussed, in reading order. Each is a document shipped in docs/.
SOURCES = ("TROUBLESHOOTING.md", "CONTINUOUS_QUERIES.md", "OPERATIONS.md", "USER_GUIDE.md")


@dataclass
class CodeEntry:
    code: str
    constant: str = ""
    area: str = ""
    sections: list[tuple[str, str, str]] = field(default_factory=list)  # (document, heading, markdown)
    mentions: list[tuple[str, str]] = field(default_factory=list)       # (document, markdown line)

    @property
    def known(self) -> bool:
        return bool(self.constant or self.sections or self.mentions)


def _sections(text: str) -> list[tuple[str, str]]:
    """``(heading, body)`` for every heading of level 2 or 3."""
    out: list[tuple[str, str]] = []
    heading: str = ""
    body: list[str] = []
    for line in text.splitlines():
        if re.match(r"^#{2,3} ", line):
            if heading:
                out.append((heading, "\n".join(body).strip()))
            heading, body = line.lstrip("#").strip(), []
        else:
            body.append(line)
    if heading:
        out.append((heading, "\n".join(body).strip()))
    return out


def lookup(code: str, docs_root: Path) -> CodeEntry | None:
    """What the documentation says about one code, or None for a malformed one."""
    code = code.upper()
    if not CODE.match(code):
        return None
    entry = CodeEntry(code)
    for name in SOURCES:
        path = docs_root / name
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        row = re.search(r"^\|\s*`" + re.escape(code) + r"`\s*\|\s*([A-Z0-9_]+)\s*\|\s*([^|]+)\|",
                        text, re.MULTILINE)
        if row and not entry.constant:
            entry.constant, entry.area = row.group(1).strip(), row.group(2).strip()
        for heading, body in _sections(text):
            if code in heading:
                entry.sections.append((name, heading.replace("`", ""), body))
        for line in text.splitlines():
            stripped = line.strip()
            if code in stripped and not stripped.startswith("#") and not re.match(
                    r"^\|\s*`" + re.escape(code) + r"`\s*\|\s*[A-Z0-9_]+\s*\|", stripped):
                already = any(stripped in body for _doc, _heading, body in entry.sections)
                if len(entry.mentions) < 12 and not already:
                    entry.mentions.append((name, stripped))
    return entry


_ROW = re.compile(r"^\|\s*`(PRV-\d{4})`\s*\|\s*([A-Z0-9_]+)\s*\|\s*([^|]+?)\s*\|", re.MULTILINE)


def every_code(docs_root: Path) -> list[dict[str, str]]:
    """Every code in TROUBLESHOOTING.md's generated table, in code order.

    That table is written from the source's own error-code constants, so a code the engine can
    raise is a row there; the browser at /help/codes lists exactly these, each linking to its page.
    """
    path = docs_root / "TROUBLESHOOTING.md"
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    start = text.find("## Every code")
    table = text[start:] if start >= 0 else text
    seen: dict[str, dict[str, str]] = {}
    for code, constant, area in _ROW.findall(table):
        seen.setdefault(code, {"code": code, "constant": constant, "range": area.strip(),
                               "family": code[4] + "xxx"})
    return sorted(seen.values(), key=lambda e: e["code"])
