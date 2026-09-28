"""Builds the assistant's dialect card from docs/CONTINUOUS_QUERIES.md.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The card is what ``pravaha why`` grounds a refusal in (ADR-058 §2): every ``PRV-nnnn`` code the
guide names, what its error-code table says it means, and the guide's own sections about it --
the ones the table row points at (``§13``) and the ones that mention the code. It is generated,
never edited, so it cannot drift from the guide without a test saying so
(``tests/test_assist_dialect.py``)::

    cd sdk/python
    .venv/bin/python tools/build_dialect_card.py           # rewrite the packaged card
    .venv/bin/python tools/build_dialect_card.py --check   # exit 1 if it is stale

Standard library only; the output is deterministic (sorted keys, no timestamps), so an unchanged
guide rebuilds a byte-identical card.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
from typing import Any, Optional

HERE = pathlib.Path(__file__).resolve().parent
SDK = HERE.parent
SOURCE = SDK.parent.parent / "docs" / "CONTINUOUS_QUERIES.md"
CARD = SDK / "pravaha" / "assist" / "resources" / "dialect-card.json"
SOURCE_NAME = "docs/CONTINUOUS_QUERIES.md"
#: The error-code table's section, and the general advice every refusal explanation gets.
CODES_SECTION = "19"
GENERAL_SECTION = "17"
#: A section longer than this is cut, with a marker; a card is context, not a copy of the guide.
SECTION_LIMIT = 8000

_HEADING = re.compile(r"^(#{2,6})\s+(.+?)\s*$")
_FENCE = re.compile(r"^\s*(```|~~~)")
_NUMBER = re.compile(r"^(\d+(?:\.\d+)*)\.?\s")
_CODE = re.compile(r"\bPRV-\d{4}\b")
_ROW = re.compile(r"^\|\s*`(PRV-\d{4})`(?:\s*[–-]\s*`(PRV-\d{4})`)?\s*\|\s*(.+?)\s*\|\s*$")
_SECTION_REF = re.compile(r"§(\d+(?:\.\d+)*)")


def slug(title: str, taken: "dict[str, int]") -> str:
    """The anchor GitHub (and the console's renderer) gives a heading."""
    text = re.sub(r"[`*_]", "", title.lower())
    text = re.sub(r"[^\w\- ]", "", text).replace(" ", "-")
    count = taken.get(text, 0)
    taken[text] = count + 1
    return text if count == 0 else f"{text}-{count}"


def sections(markdown: str) -> "list[dict[str, Any]]":
    """Every heading from ``##`` down, with its own text up to the next heading of any level.
    Headings inside code fences are code, not headings."""
    found: list[dict[str, Any]] = []
    taken: dict[str, int] = {}
    fenced = False
    current: Optional[dict[str, Any]] = None
    for line in markdown.splitlines():
        if _FENCE.match(line):
            fenced = not fenced
        heading = None if fenced else _HEADING.match(line)
        if heading:
            title = heading.group(2).strip()
            number = _NUMBER.match(title)
            current = {
                "anchor": slug(title, taken),
                "level": len(heading.group(1)),
                "number": number.group(1) if number else None,
                "title": title,
                "lines": [],
            }
            found.append(current)
        elif current is not None:
            current["lines"].append(line)
    for entry in found:
        text = "\n".join(entry.pop("lines")).strip()
        text = re.sub(r"\n{3,}", "\n\n", text)
        if text.endswith("---"):  # the guide's rule between parts
            text = text[:-3].strip()
        entry["text"] = _trim(text)
    return found


def _trim(text: str) -> str:
    """A long section's first paragraphs up to :data:`SECTION_LIMIT`, then only the later
    paragraphs that name a code -- which is why the section is on the card at all. Paragraphs
    split on blank lines outside code fences, so a fenced example is kept whole or not at all."""
    if len(text) <= SECTION_LIMIT:
        return text
    groups: list[list[str]] = [[]]
    fenced = False
    for line in text.split("\n"):
        if _FENCE.match(line):
            fenced = not fenced
        if not fenced and not line.strip():
            if groups[-1]:
                groups.append([])
            continue
        groups[-1].append(line)
    blocks = ["\n".join(group).strip() for group in groups if group]
    kept: list[str] = []
    size = 0
    cut = False
    for block in blocks:
        if not cut and size + len(block) <= SECTION_LIMIT:
            kept.append(block)
            size += len(block) + 2
            continue
        cut = True
        if _CODE.search(block):
            kept.append("[...]")
            kept.append(block)
    kept.append("[... the rest of this section is in the guide]")
    return "\n\n".join(kept)


def _expand(first: str, last: Optional[str]) -> "list[str]":
    if not last:
        return [first]
    low, high = int(first[4:]), int(last[4:])
    return [f"PRV-{n:04d}" for n in range(low, high + 1)]


def build(markdown: str) -> "dict[str, Any]":
    """The card, as a JSON-ready dict."""
    every = sections(markdown)
    by_number = {s["number"]: s for s in every if s["number"]}
    table = by_number.get(CODES_SECTION)
    means: dict[str, str] = {}
    refs: dict[str, list[str]] = {}
    if table is not None:
        for line in table["text"].splitlines():
            row = _ROW.match(line)
            if not row:
                continue
            for code in _expand(row.group(1), row.group(2)):
                means[code] = row.group(3)
                refs[code] = _SECTION_REF.findall(row.group(3))
    codes: dict[str, dict[str, Any]] = {}
    used: set[str] = set()
    mentioned = sorted(set(_CODE.findall(markdown)) | set(means))
    order = {entry["anchor"]: i for i, entry in enumerate(every)}
    for code in mentioned:
        # Most relevant first, because a reader of the card stops at a size limit: the sections
        # the table row points at, then a section whose heading names the code, then by how often
        # a section names it, then the guide's own order.
        rank: dict[str, tuple[int, int, int]] = {}
        referenced = {by_number[n]["anchor"] for n in refs.get(code, []) if n in by_number}
        for entry in every:
            if entry["number"] == CODES_SECTION:
                continue
            count = entry["text"].count(code) + entry["title"].count(code)
            if count or entry["anchor"] in referenced:
                priority = 2 if entry["anchor"] in referenced else 1 if code in entry["title"] else 0
                rank[entry["anchor"]] = (-priority, -count, order[entry["anchor"]])
        anchors = sorted(rank, key=lambda a: rank[a])
        used.update(anchors)
        codes[code] = {"means": means.get(code), "sections": anchors}
    general = by_number.get(GENERAL_SECTION)
    if general is not None:
        used.add(general["anchor"])
    return {
        "$comment": "Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. "
        "PROPRIETARY AND CONFIDENTIAL. Generated by sdk/python/tools/build_dialect_card.py from "
        f"{SOURCE_NAME}; do not edit.",
        "source": SOURCE_NAME,
        "sourceSha256": hashlib.sha256(markdown.encode("utf-8")).hexdigest(),
        "general": general["anchor"] if general is not None else None,
        "codes": codes,
        "sections": {
            entry["anchor"]: {"number": entry["number"], "title": entry["title"], "text": entry["text"]}
            for entry in every
            if entry["anchor"] in used
        },
    }


def render(markdown: str) -> str:
    return json.dumps(build(markdown), indent=1, sort_keys=True, ensure_ascii=False) + "\n"


def main(argv: "Optional[list[str]]" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if the packaged card is stale")
    parser.add_argument("--source", type=pathlib.Path, default=SOURCE)
    parser.add_argument("--out", type=pathlib.Path, default=CARD)
    args = parser.parse_args(argv)
    fresh = render(args.source.read_text(encoding="utf-8"))
    if args.check:
        current = args.out.read_text(encoding="utf-8") if args.out.exists() else ""
        if current != fresh:
            print(f"{args.out} is stale: run tools/build_dialect_card.py", file=sys.stderr)
            return 1
        return 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(fresh, encoding="utf-8")
    print(f"wrote {args.out} ({len(fresh)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
