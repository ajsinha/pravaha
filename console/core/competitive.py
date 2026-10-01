"""
Pravaha console — the competitive landscape, read from docs/publications/COMPETITIVE_LANDSCAPE.md.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The document is canonical, and laid out as MAYA's comparison is:

* **The landscape** -- the families of product, well-known examples of each, what each is good
  at, and the pattern. Products are named here and only here.
* **The scored table** -- a capability per row, a category of product per column, each cell
  Yes / Partial / No. Categories, never vendors.
* **One note per row** -- under "Where Pravaha shines, and how" for every row Pravaha scores Yes,
  under "Where Pravaha is partial or behind, and why" for the rest. A note is a ``### `` heading
  whose text is the row's capability, word for word, so a row and its note are joined by the
  heading's anchor. Each note holds **The problem elsewhere.** (a paragraph), **How Pravaha does
  it.** (a list) and **Why it matters.** (a paragraph).
* **What the rows have in common**, **The practical reading**, then the sections after, and the
  dated **Disclaimer**.

Two pages draw on it: ``/about/competitive`` shows all of it, and the About page a summary -- the
table, the rows where Pravaha shines and where it is behind. Nothing about any product is written
here; this module only finds the parts. ``tests/test_help.py`` fails the build when a row has no
note, a note no row, a note sits under the wrong heading for its score, or lacks a part.
"""
from __future__ import annotations

import re
from pathlib import Path

from markdown.extensions.toc import slugify

SOURCE = "docs/publications/COMPETITIVE_LANDSCAPE.md"

LANDSCAPE = "The landscape"
TABLE = "The scored table"
SHINE = "Where Pravaha shines, and how"
BEHIND = "Where Pravaha is partial or behind, and why"
COMMON = "What the rows have in common"
READING = "The practical reading"
DISCLAIMER = "Disclaimer"

#: The sections the full page shows after the closing card, in the document's order.
AFTER = ["Adjacent tools", "What Pravaha borrows"]

#: The chip each score is drawn with (MAYA's ``cmp cmp-yes`` and so on). A word the table does not
#: use is shown as written, in the neutral chip, so a new word in the document is visible rather
#: than dropped.
SCORE_CHIPS = {"Yes": "yes", "Partial": "partial", "No": "no"}

#: A short name for each column, for the chips on a note (MAYA's "MRM Yes", "ML Partial").
SHORT = {"Dataflow SQL": "Dataflow", "Streaming databases": "Streaming DB", "Kafka-native": "Kafka",
         "Dataflow libraries": "Libraries", "Embeddable JVM": "JVM", "Governance catalogues": "Catalogues"}

#: A picture for each note. The words are the document's; only the icon is chosen here.
ICONS = {
    "refusing-unbounded-state-at-plan-time": "slash-circle",
    "time-travel-debugging": "bug",
    "bluegreen-replacement-with-backfill": "arrow-left-right",
    "store-native-pushdown": "funnel",
    "serving-its-own-results": "hdd-network",
    "sharing-identical-queries": "diagram-2",
    "a-governed-catalogue-of-live-answers": "journal-bookmark",
    "row-level-security-at-read-time": "shield-lock",
    "alerts-that-fire-and-clear": "bell",
    "bi-tools-over-the-postgresql-protocol-with-security-applied": "bar-chart-line",
    "plain-english-to-continuous-sql-with-the-engine-as-judge": "chat-square-text",
    "a-lane-of-its-own-or-a-shared-one-changed-without-loss": "signpost-split",
    "native-change-data-capture": "hdd-stack",
    "embeddable-in-process": "box",
    "event-time-watermarks-and-late-data-corrections": "clock-history",
    "incremental-maintenance-with-retractions": "arrow-counterclockwise",
    "exactly-once-sinks": "check2-circle",
    "observability-built-in": "activity",
    "queries-on-queries": "layers",
    "delta-and-iceberg-table-sinks": "water",
    "recursive-queries": "arrow-repeat",
    "sql-breadth": "code-square",
    "connector-breadth": "plug",
    "governing-many-engines-and-data-at-rest": "globe",
    "scale-out-and-ha-maturity": "diagram-3",
    "mfa-and-single-sign-on": "key",
    "a-managed-cloud-service": "cloud",
    "ecosystem-and-support": "people",
}

_LINK = re.compile(r"^\[(.+?)\]\(#[^)]*\)$")
_PART = r"\*\*{}\.\*\*\s*(.+?)(?=\n\s*\n|\Z)"
_PROBLEM = re.compile(_PART.format("The problem elsewhere"), re.DOTALL)
_WHY = re.compile(_PART.format("Why it matters"), re.DOTALL)
_HOW = re.compile(r"\*\*How Pravaha does it\.\*\*\s*\n(.*?)(?=\n\*\*Why it matters\.\*\*|\Z)", re.DOTALL)


def section(text: str, heading: str) -> str:
    """The body of one ``## heading`` section, up to the next ``## ``."""
    match = re.search(r"^## " + re.escape(heading) + r"[^\n]*\n(.*?)(?=^## |\Z)", text, re.MULTILINE | re.DOTALL)
    return match.group(1).strip() if match else ""


def anchor(title: str) -> str:
    """The id a heading gets, here and on GitHub alike for the plain titles the table uses."""
    return slugify(title, "-")


def items(markdown: str) -> list[str]:
    """The items of a markdown list, each with its continuation lines joined."""
    out: list[str] = []
    for line in markdown.splitlines():
        if line.startswith("- "):
            out.append(line[2:].strip())
        elif out and line.startswith("  ") and line.strip():
            out[-1] += " " + line.strip()
    return out


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

    # ------------------------------------------------------------------ landscape
    def landscape(self) -> str:
        """The families of product, their examples and the pattern -- the page's introduction."""
        return self._html(section(self.text, LANDSCAPE))

    # ------------------------------------------------------------------ table
    def table(self) -> tuple[list[str], list[dict]]:
        """The scored table: the column names (the categories, then Pravaha) and one row per
        capability, each with its anchor and a score per column."""
        body = section(self.text, TABLE)
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
        """The prose around the table: what the scores mean, and anything after it."""
        lines = section(self.text, TABLE).splitlines()
        rows = [i for i, line in enumerate(lines) if line.startswith("|")]
        if not rows:
            return {"before": self._html("\n".join(lines).strip()), "after": ""}
        before = "\n".join(lines[:rows[0]]).strip()
        after = "\n".join(lines[rows[-1] + 1:]).strip()
        return {"before": self._html(before), "after": self._html(after)}

    @staticmethod
    def short(columns: list[str]) -> list[str]:
        return [SHORT.get(c, c) for c in columns]

    # ------------------------------------------------------------------ notes
    def cards(self, heading: str) -> list[dict]:
        """One note per ``### `` under a heading: its title, anchor, the problem elsewhere, how
        Pravaha does it (a list), why it matters, and its row's scores."""
        _, rows = self.table()
        by_id = {r["id"]: r for r in rows}
        body = section(self.text, heading)
        out = []
        for block in re.split(r"^### ", body, flags=re.MULTILINE)[1:]:
            title, _, rest = block.partition("\n")
            title = title.strip()
            ident = anchor(title)
            problem, how, why = _PROBLEM.search(rest), _HOW.search(rest), _WHY.search(rest)
            row = by_id.get(ident)
            out.append({"title": title, "id": ident, "icon": ICONS.get(ident, "stars"),
                        "html": self._html(rest.strip()),
                        "problem": self._inline(problem.group(1).strip()) if problem else "",
                        "how": [self._inline(i) for i in items(how.group(1))] if how else [],
                        "why": self._inline(why.group(1).strip()) if why else "",
                        "scores": row["scores"] if row else [], "row": row is not None,
                        "pravaha": row["pravaha"] if row else ""})
        return out

    def intro(self) -> str:
        """The text between the heading on the shine notes and the first note."""
        body = section(self.text, SHINE)
        return self._html(body.split("\n### ", 1)[0].strip())

    # ------------------------------------------------------------------ after the notes
    def common(self) -> list[str]:
        """What the rows have in common, one rendered item each."""
        return [self._inline(i) for i in items(section(self.text, COMMON))]

    def reading(self) -> str:
        return self._html(section(self.text, READING))

    def sections(self) -> list[dict]:
        """The sections after the closing card, each rendered, in the document's order."""
        return [{"title": h, "id": anchor(h), "html": self._html(section(self.text, h))}
                for h in AFTER if section(self.text, h)]

    def disclaimer(self) -> str:
        return self._html(section(self.text, DISCLAIMER))
