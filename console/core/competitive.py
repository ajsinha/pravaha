"""
Pravaha console — the competitive landscape, read from docs/COMPETITIVE_LANDSCAPE.md.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The document is canonical. It holds, in one file, the scored table (a capability per row, a
category of product per column, each cell Yes / Partial / No) and one card per row: under
"Where Pravaha shines, and how" for every row where Pravaha scores Yes, under "Where Pravaha
is partial or behind, and why" for the rest. A card is a ``### `` heading whose text is the row's
capability, word for word, so a row and its card are joined by the heading's anchor.

Two pages draw on it: ``/about/competitive`` shows all of it, and the About page shows the
table, the shine cards' "Why it matters" and "Where Pravaha loses". Nothing about any product
is written here; this module only finds the parts. ``tests/test_help.py`` fails the build when
a row has no card, a card has no row, or a card sits under the wrong heading for its score.
"""
from __future__ import annotations

import re
from pathlib import Path

from markdown.extensions.toc import slugify

SOURCE = "docs/COMPETITIVE_LANDSCAPE.md"

SHINE = "Where Pravaha shines, and how"
BEHIND = "Where Pravaha is partial or behind, and why"

#: The sections the full page shows after the cards, in the document's order.
AFTER = ["Where Pravaha loses", "The categories", "Adjacent tools", "What Pravaha borrows",
         "Disclaimer", "Verdict"]

#: The chip each score is drawn with. A word the table does not use is shown as written, in the
#: neutral chip, so a new word in the document is visible rather than dropped.
SCORE_CHIPS = {"Yes": "ok", "Partial": "warn", "No": "bad"}

#: A picture for each card. The words are the document's; only the icon is chosen here.
ICONS = {
    "refusing-unbounded-state-at-plan-time": "slash-circle",
    "time-travel-debugging": "bug",
    "bluegreen-replacement-with-backfill": "arrow-left-right",
    "store-native-pushdown": "funnel",
    "serving-its-own-results": "hdd-network",
    "sharing-identical-queries": "diagram-2",
    "row-level-security-at-read-time": "shield-lock",
    "embeddable-in-process": "box",
    "event-time-watermarks-and-late-data-corrections": "clock-history",
    "incremental-maintenance-with-retractions": "arrow-counterclockwise",
    "exactly-once-sinks": "check2-circle",
    "recursive-queries": "arrow-repeat",
    "sql-breadth": "code-square",
    "connector-breadth": "plug",
    "scale-out-and-ha-maturity": "diagram-3",
    "ecosystem-and-support": "people",
}

_LINK = re.compile(r"^\[(.+?)\]\(#[^)]*\)$")
_WHY = re.compile(r"\*\*Why it matters\.\*\*\s*(.+?)(?:\n\s*\n|\Z)", re.DOTALL)


def section(text: str, heading: str) -> str:
    """The body of one ``## heading`` section, up to the next ``## ``."""
    match = re.search(r"^## " + re.escape(heading) + r"[^\n]*\n(.*?)(?=^## |\Z)", text, re.MULTILINE | re.DOTALL)
    return match.group(1).strip() if match else ""


def anchor(title: str) -> str:
    """The id a heading gets, here and on GitHub alike for the plain titles the table uses."""
    return slugify(title, "-")


def _cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def _score(word: str) -> dict[str, str]:
    word = word.strip("*")
    return {"word": word, "chip": SCORE_CHIPS.get(word, "info")}


class Landscape:
    """The parts of the document, rendered with the console's renderer."""

    def __init__(self, repo_root: Path, renderer) -> None:
        self.renderer = renderer
        path = Path(repo_root) / SOURCE
        self.text = path.read_text(encoding="utf-8") if path.exists() else ""

    def _html(self, markdown: str) -> str:
        return self.renderer.render(markdown)[0] if markdown else ""

    def _inline(self, markdown: str) -> str:
        html = self._html(markdown).strip()
        return html[3:-4] if html.startswith("<p>") and html.endswith("</p>") else html

    # ------------------------------------------------------------------ table
    def table(self) -> tuple[list[str], list[dict]]:
        """The scored table: the column names (the categories, then Pravaha) and one row per
        capability, each with its anchor and a score per column."""
        body = section(self.text, "The scored table")
        lines = [line for line in body.splitlines() if line.startswith("|")]
        if len(lines) < 3:
            return [], []
        header = _cells(lines[0])
        rows = []
        for line in lines[2:]:
            cells = _cells(line)
            if len(cells) != len(header):
                continue
            link = _LINK.match(cells[0])
            capability = link.group(1) if link else cells[0]
            rows.append({"capability": capability, "id": anchor(capability),
                         "scores": [_score(c) for c in cells[1:]],
                         "pravaha": cells[-1].strip("*")})
        return header[1:], rows

    def table_notes(self) -> dict[str, str]:
        """The prose around the table: what the scores mean, and what each column holds."""
        lines = section(self.text, "The scored table").splitlines()
        rows = [i for i, line in enumerate(lines) if line.startswith("|")]
        if not rows:
            return {"before": self._html("\n".join(lines).strip()), "after": ""}
        before = "\n".join(lines[:rows[0]]).strip()
        after = "\n".join(lines[rows[-1] + 1:]).strip()
        return {"before": self._html(before), "after": self._html(after)}

    # ------------------------------------------------------------------ cards
    def cards(self, heading: str) -> list[dict]:
        """One card per ``### `` under a heading: its title, anchor, the body rendered, and the
        "Why it matters" paragraph on its own for the About page."""
        _, rows = self.table()
        by_id = {r["id"]: r for r in rows}
        body = section(self.text, heading)
        out = []
        for block in re.split(r"^### ", body, flags=re.MULTILINE)[1:]:
            title, _, rest = block.partition("\n")
            title = title.strip()
            ident = anchor(title)
            why = _WHY.search(rest)
            row = by_id.get(ident)
            out.append({"title": title, "id": ident, "icon": ICONS.get(ident, "stars"),
                        "html": self._html(rest.strip()),
                        "why": self._inline(why.group(1).strip()) if why else "",
                        "scores": row["scores"] if row else [], "row": row is not None})
        return out

    def intro(self) -> str:
        """The text between the heading on "the shine cards" and the first card."""
        body = section(self.text, SHINE)
        return self._html(body.split("\n### ", 1)[0].strip())

    def sections(self) -> list[dict]:
        """The sections after the cards, each rendered, in the document's order."""
        return [{"title": h, "id": anchor(h), "html": self._html(section(self.text, h))}
                for h in AFTER if section(self.text, h)]

    def loses(self) -> str:
        return self._html(section(self.text, "Where Pravaha loses"))

    def disclaimer(self) -> str:
        return self._html(section(self.text, "Disclaimer"))
