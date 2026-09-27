"""
Pravaha console — what the About page says, and where each part of it comes from.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The About page makes claims -- what is built, what was measured, what was decided, who owns
it -- and a claim written twice drifts. So wherever the repository already states a thing, it
is read from there rather than retyped:

* **What is built, and what is not** -- the README's "What works" table and its "What is not
  built" list, the sections the build's DocumentationFreshnessTest keeps honest.
* **The decisions** -- the one-line summaries in docs/adr/README.md, for the records chosen here.
* **Provenance and legal** -- the README's "Legal" section and the LICENSE, verbatim.
* **In this release** -- the newest entry of docs/RELEASE_NOTES.md, read on each request.
* **The competitive landscape** -- the scored table, the shine cards' "Why it matters" and
  "Where Pravaha loses", from docs/COMPETITIVE_LANDSCAPE.md through core/competitive.py, which
  also draws the whole of it at /about/competitive.

What is written here is what no document states in a form the page can use: the measured
numbers (each with the document it comes from, and where and on what it was measured), the
design principles, each of which names the decision record that holds it, and the four
problem-and-fix pairs, each of which names the page that holds its claim.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Measured:
    value: str
    label: str
    caveat: str
    where: str          # where it was measured, on what
    source: str         # the document or decision record that records it
    href: str           # where in the help that source is readable


#: Every number on the About page. None is a benchmark of the engine against a reference
#: machine; each says what it is, what it is not, and where it was taken.
MEASURED: list[Measured] = [
    Measured("~1,000 rows/s", "the throughput requirement",
             "The rate the owner's workload needs, because the work is maintaining answers to "
             "registered questions rather than moving bulk data. The design's 1.2 M rows/s per lane "
             "and ≥ 90 % scaling to eight lanes are kept as aspirations and are unmeasured.",
             "A requirement, not a measurement.", "ADR-042",
             "/help/decisions/042-the-throughput-bar-is-the-requirement"),
    Measured("21 M rows/s", "lane machinery, one lane",
             "The loop, inbox, arena and handoff — not a query end to end. Profile A's end-to-end "
             "rate under gate P2's conditions has never been measured.",
             "Gate P2 pack, wave 3, on the development machine: a 12-core heterogeneous laptop part, "
             "not the 16 homogeneous physical cores P2 specifies.", "ADR-042",
             "/help/decisions/042-the-throughput-bar-is-the-requirement"),
    Measured("~10×", "generated code over the interpreted path",
             "Whole-stage code generation (Janino) against the interpreted operators, for the same plan.",
             "Gate P2 pack, wave 3, same machine.", "ADR-042",
             "/help/decisions/042-the-throughput-bar-is-the-requirement"),
    Measured("3.7 ms · 61 MiB", "a thousand distinct queries: registration each, off-heap total",
             "At the advised inbox sizing. About 1 MiB off-heap per idle query, and 200 queries add "
             "24 platform threads — one per core — where they once added 400.",
             "NodeScaleTest and SourceScaleTest, on the development machine.", "README · ADR-036",
             "/help/decisions/036-one-node-thousands-of-queries"),
    Measured("3.8 → 1.0", "Aerospike scans per second, four queries over one set",
             "Several queries over the same set share one scan instead of each opening their own.",
             "Finding SRC-3, against a real Aerospike server.", "CONTINUOUS_QUERIES.md §2.1",
             "/help/continuous-queries#aerospike-a-set-scanned-by-last-update-time"),
    Measured("0.44–1.15×", "spilled state against the same load in RAM",
             "Join and windowed-aggregate state driven to 1, 2, 4, 8 and 16 times a 64 MiB ceiling: "
             "flat from 2× to 16×. With the page cache holding the files — state larger than the "
             "machine's free RAM is not measured — and run-to-run noise of about 20–25 %. The tier "
             "stays off by default.",
             "SpillTierMeasurementIT on a 12-core Ryzen AI 9 HX 370 laptop, 61 GiB RAM, NVMe.",
             "ADR-044", "/help/decisions/044-no-rocksdb-the-mapped-tier-is-l1"),
]

#: The decision records the About page puts forward, in the order a newcomer should read them.
DECISIONS: list[str] = ["013", "014", "002", "004", "005", "008", "025", "030", "031", "032",
                        "037", "042", "043", "044", "024", "045"]


@dataclass(frozen=True)
class Principle:
    title: str
    body: str
    ref: str            # a decision record's file stem, or a help topic's slug as "topic:<slug>"

    @property
    def href(self) -> str:
        if self.ref.startswith("topic:"):
            return "/help/topics/" + self.ref[6:]
        return "/help/decisions/" + self.ref

    @property
    def label(self) -> str:
        return "Read more" if self.ref.startswith("topic:") else "ADR-" + self.ref[:3]


PRINCIPLES: list[Principle] = [
    Principle("Refuse rather than approximate",
              "A query the engine cannot run is refused when it is planned, with a PRV code and the "
              "reason — never accepted and then answered wrongly. A refusal costs five minutes; a "
              "plausible wrong number costs whatever was decided on it.",
              "topic:sql-refusals"),
    Principle("Bounded by construction",
              "Every operator that could grow without limit has a bound, and a query with none is "
              "refused at plan time — an unwindowed GROUP BY over a stream, an outer join with no "
              "time bound — rather than at three in the morning when the heap fills.",
              "037-state-that-degrades-instead-of-dying"),
    Principle("Incremental, not recomputed",
              "Every change is a Z-set delta with a weight. A correction is a retraction and an "
              "insert, and work is proportional to what changed, not to how much data exists.",
              "013-zsets-and-dbsp"),
    Principle("Event time decides",
              "A window closes when the data says it is over, not when the clock does; a late row "
              "corrects the answer instead of being dropped.",
              "topic:event-time-watermarks"),
    Principle("One question, one computation",
              "Identical questions share one computation under many names, matched on the "
              "normalised plan and the security predicates, not the SQL text.",
              "025-registration-and-subscription-separated"),
    Principle("The view is the serving database",
              "The maintained answer is read by key where it lives, so there is no second database "
              "to load it into and keep in step.",
              "014-serve-maintained-views"),
    Principle("Authorize what is read, not what it is called",
              "Authentication and authorization happen in Pravaha on every read; a row filter "
              "follows its principal into every view and into the fingerprint.",
              "031-authorization-at-the-pravaha-layer"),
    Principle("Adapt performance, never semantics",
              "Whatever the engine tunes for itself, it never changes what an answer means.",
              "017-auto-tune-performance-not-semantics"),
    Principle("Say what is not built",
              "A status line, a gate or a screen that claims more than the code does is a defect. "
              "The README says what is not finished; the console draws what the engine does not "
              "publish as missing, not as zero.",
              "042-the-throughput-bar-is-the-requirement"),
]


@dataclass(frozen=True)
class Problem:
    """One problem the engine exists for, and what it does instead. Each names the page that
    holds the claim, so the pair is a pointer rather than a slogan."""
    problem: str
    fix: str
    href: str


PROBLEMS: list[Problem] = [
    Problem("Every dashboard and alert re-runs the same query on a timer, paying for the whole "
            "computation to learn what changed since the last run — and is stale in between.",
            "Register the SQL once. The answer is maintained as rows change, with work proportional "
            "to the change, and is current whenever it is read.",
            "/help/topics/start-here"),
    Problem("A stream processor computes the answer and writes it to a second database, which has "
            "to be run, loaded and kept in step — and is only as right as the last time they agreed.",
            "The maintained view is the serving store: read by key, scanned with SQL, subscribed to "
            "commit by commit, or over the PostgreSQL wire protocol.",
            "/help/decisions/014-serve-maintained-views"),
    Problem("A late or corrected row either vanishes or produces a second, contradictory answer, and "
            "every consumer invents its own way to reconcile the two.",
            "A late row inside the allowed lateness is a correction: the old answer withdrawn and the "
            "new one inserted, in one commit every reader sees.",
            "/help/topics/late-data"),
    Problem("A query that grows its state without limit is accepted, runs for months, and fails at "
            "three in the morning when the heap fills.",
            "It is refused when it is registered, with a PRV code and the reason — an unwindowed "
            "GROUP BY over a stream is PRV-2050.",
            "/help/topics/sql-refusals"),
]

def _section(text: str, heading: str) -> str:
    """The body of one ``## heading`` section of a markdown document."""
    match = re.search(r"^## " + re.escape(heading) + r"[^\n]*\n(.*?)(?=^## |\Z)", text, re.MULTILINE | re.DOTALL)
    return match.group(1).strip() if match else ""


_DEAD = re.compile(r'<a href="#">(.*?)</a>', re.DOTALL)


class _Plain:
    """The renderer, with links it could not place (to files the console does not serve --
    HANDOVER.md, the console's own README) turned back into their text."""

    def __init__(self, renderer) -> None:
        self._renderer = renderer

    def render(self, text: str):
        html, headings = self._renderer.render(text)
        return _DEAD.sub(r"\1", html), headings


class AboutSource:
    """Reads what the About page quotes from the repository."""

    def __init__(self, repo_root: Path, renderer) -> None:
        self.root = repo_root
        self.renderer = _Plain(renderer)

    def _read(self, relative: str) -> str:
        path = self.root / relative
        return path.read_text(encoding="utf-8") if path.exists() else ""

    def built(self) -> list[dict[str, str]]:
        """The README's "What works" table: one row per area, rendered."""
        rows = []
        for line in _section(self._read("README.md"), "What works").splitlines():
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) >= 2 and cells[0].startswith("**"):
                rows.append({"area": cells[0].strip("*"),
                             "html": self.renderer.render("|".join(cells[1:]))[0]})
        return rows

    def not_built(self) -> str:
        """The README's list of what is not built, or not finished, rendered as it is written."""
        return self.renderer.render(_section(self._read("README.md"), "What is not built, or not finished"))[0]

    def how_built(self) -> list[dict[str, str]]:
        """The README's "How it is built" table."""
        rows = []
        for line in _section(self._read("README.md"), "How it is built").splitlines():
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) >= 2 and cells[0].startswith("**"):
                rows.append({"area": cells[0].strip("*"),
                             "html": self.renderer.render("|".join(cells[1:]))[0]})
        return rows

    def status_line(self) -> str:
        match = re.search(r"^> \*\*Project status:(.*?)(?=\n\n)", self._read("README.md"), re.MULTILINE | re.DOTALL)
        if not match:
            return ""
        text = "**Project status:" + re.sub(r"\n> ?", " ", match.group(1))
        return self.renderer.render(text)[0]

    def decisions(self) -> list[dict[str, str]]:
        """The chosen records, each with the index's own one-line summary."""
        index = self._read("docs/adr/README.md")
        out = []
        for number in DECISIONS:
            match = re.search(r"^\| \[" + number + r"\]\((" + number + r"-[a-z0-9-]+)\.md\) \| (.*?) \|$",
                              index, re.MULTILINE)
            if match:
                summary = match.group(2)
                plain = re.sub(r"\*\*|`", "", summary)
                if len(plain) > 240:
                    plain = plain[:plain.rfind(" ", 0, 237)] + " …"
                out.append({"number": number, "stem": match.group(1), "summary": plain})
        return out

    def release(self) -> dict[str, str]:
        """The newest entry of docs/RELEASE_NOTES.md -- its heading and its body, rendered.

        Read on every request, so the page says what the notes say the moment they change; an
        empty dict when the notes are missing, and the template then says nothing about a release.
        """
        text = self._read("docs/RELEASE_NOTES.md")
        match = re.search(r"^## (.+?)\n(.*?)(?=^## |^---\s*$|\Z)", text, re.MULTILINE | re.DOTALL)
        if not match:
            return {}
        return {"title": match.group(1).strip(), "html": self.renderer.render(match.group(2).strip())[0]}

    def landscape(self):
        """The competitive landscape, from docs/COMPETITIVE_LANDSCAPE.md (core/competitive.py)."""
        from core.competitive import Landscape

        return Landscape(self.root, self.renderer)

    def legal(self) -> str:
        return self.renderer.render(_section(self._read("README.md"), "Legal"))[0]

    def licence(self) -> str:
        return self._read("LICENSE")
