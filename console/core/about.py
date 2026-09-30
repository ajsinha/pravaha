"""
Pravaha console — what the About page says, and where each part of it comes from.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The page follows MAYA's About, section for section: the hero; what it is; the problem it
solves; what makes it different; how it works; what is built; this release and the honest
limits; the numbers measured; the competitive summary; the principles; further reading; the
technology; the author and the licence.

The About page makes claims -- what is built, what was measured, who owns it -- and a claim
written twice drifts. So wherever the repository already states a thing, it is read from there
rather than retyped:

* **What is built, and what is not, in full** -- the README's "What works" list and its "What is
  not built" section, the ones the build's DocumentationFreshnessTest keeps honest.
* **Provenance and legal** -- the README's "Legal" section and the LICENSE, verbatim.
* **In this release** -- the newest entry of docs/RELEASE_NOTES.md, read on each request.
* **The competitive summary** -- the scored table and its rows, from docs/COMPETITIVE_LANDSCAPE.md
  through core/competitive.py, which also draws the whole of it at /about/competitive.

What is written here is what no document states in a form the page can use, each item naming
where its claim is held: the problem narrative and its problem-and-fix pairs, the cards of what
makes it different, the capabilities by area in MAYA's short lines, the honest limits, the
measured numbers (each with where and on what it was measured), the design principles, the
technology, and the long-form reading -- the research paper, the deck and the Medium post, the
first two served at /about/papers/ from a fixed list when the installation carries them.
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
    Measured("1,000 → 8", "queries over one source, and the lanes they run on",
             "With lane sharing on, each row is written into 8 lanes instead of 1,000. Sharing is "
             "automatic from a node's 65th query; a query may still ask for a lane of its own.",
             "README, \"Many queries on one node\", on the development machine.", "README · ADR-036",
             "/help/decisions/036-one-node-thousands-of-queries"),
    Measured("28–42 %", "of linear, one lane to eight: the scaling gate, not reached",
             "Against a 90 % target. 33–46 % when re-measured on 2026-09-26 without the coverage agent "
             "every earlier run carried. Recorded as measured rather than restated.",
             "Gate pack of 2026-09-20 on the development machine: a 12-core heterogeneous laptop part "
             "running other work, not reference hardware.", "Gate pack · ADR-042",
             "/help/decisions/042-the-throughput-bar-is-the-requirement"),
    Measured("12 of 23", "Nexmark queries that run",
             "5 ran at the 2026-09-20 measurement and 12 after the SQL batch of 2026-09-26 (self joins, "
             "top-N, exact DECIMAL). The eleven that do not run are missing SQL, not speed; the "
             "head-to-head comparison with a dataflow engine has not been run.",
             "NexmarkCoverageIT, recorded in the gate pack of 2026-09-20.", "README, \"Performance\"",
             "/help/decisions/042-the-throughput-bar-is-the-requirement"),
]

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
    """One problem the engine exists for, and what it does instead (MAYA's bad/fix pair). Each
    names the page that holds the claim, so the pair is a pointer rather than a slogan."""
    problem: str
    fix: str
    href: str


#: The problem narrative's lead, from the research paper's abstract and the Medium post: asking
#: again against keeping the answer.
PROBLEM_LEAD: list[str] = [
    "An application that needs the current answer to a question over changing data usually asks "
    "again: it polls a database, reruns a batch job, or rebuilds a cache on a timer. Between two "
    "askings the answer is stale, each asking pays for the whole question rather than for what "
    "changed, and ten desks asking the same thing cost ten executions.",
    "A continuous query inverts the arrangement. The question is registered once, the engine keeps "
    "its answer current as the data changes, and reading the answer is a lookup. The inversion is "
    "old; what makes it hard to trust is not the incremental arithmetic but the hand-overs — a "
    "subscriber joining, a restart, a query attaching to a shared reader, a new version taking the "
    "name — each a place where a system can be almost right in a way nothing reports. Pravaha makes "
    "each one exact: a seam is a position, not a moment.",
]

PROBLEMS: list[Problem] = [
    Problem("A job runs SELECT … WHERE on_hand <= reorder_point every few minutes. It misses a line "
            "that dips and recovers between two polls, cannot say when anything changed, and puts a "
            "scan on the database that serves the tills.",
            "Register the SQL once. The view changes when the data does — from the store's own change "
            "log where there is one — and reading it is a lookup by key.",
            "/help/topics/getting-started"),
    Problem("A nightly batch rebuilds revenue per region per hour from the raw lines, so by "
            "mid-morning the analysts are looking at yesterday, and every rebuild pays for all of it.",
            "Every change is a Z-set delta with a weight, so work is proportional to what changed, and "
            "the answer is current whenever it is read.",
            "/help/topics/zset-weights"),
    Problem("A stream processor computes the answer and writes it to a second database, which has to "
            "be run, loaded, secured and kept in step — and is only as right as the last time they "
            "agreed.",
            "The maintained view is the serving store: read by key, scanned with SQL, subscribed to "
            "commit by commit, or read over the PostgreSQL protocol, with grants, row filters and "
            "masks applied on every read.",
            "/help/decisions/014-serve-maintained-views"),
    Problem("A late or corrected row either vanishes or produces a second, contradictory answer, and "
            "every consumer invents its own way to reconcile the two.",
            "A late row inside the allowed lateness is a correction: the old answer withdrawn and the new "
            "one inserted, in one commit every reader sees.",
            "/help/topics/event-time-watermarks#late-data"),
    Problem("A query that grows its state without limit is accepted, runs for months, and fails at "
            "three in the morning when the heap fills.",
            "It is refused when it is registered, with a PRV code and the reason — an unwindowed "
            "GROUP BY over a stream is PRV-2050.",
            "/help/topics/sql-refusals"),
    Problem("Changing a running query means an outage or a window of wrong answers, so nobody changes "
            "it, and the SQL drifts from what the business needs.",
            "The new version backfills beside the old and takes the name at the exact position both "
            "have reached: no gap, nothing counted twice, rollback in one step.",
            "/help/topics/backfill-cutover"),
]


@dataclass(frozen=True)
class Feature:
    """One card of "What makes it different" (MAYA's about-feature): an icon, a title, a sentence
    or two, and where the claim is held -- a note on /about/competitive or a decision record."""
    icon: str
    title: str
    text: str
    href: str


DIFFERENT: list[Feature] = [
    Feature("scissors", "Exact cuts",
            "A checkpoint is one consistent cut across every input: operator state, source offsets and "
            "the served view at the same point. A restart resumes rather than replaying, and a two-phase "
            "sink receives each change exactly once.",
            "/about/competitive#exactly-once-sinks"),
    Feature("bezier2", "Exact seams",
            "A subscriber joining from a snapshot, or a query attaching to a reader others already share, "
            "meets the stream at a named position: every later commit, and every record, exactly once "
            "and in order.",
            "/help/decisions/054-an-ordered-source-is-shared-at-an-exact-seam"),
    Feature("arrow-left-right", "Lossless cutover",
            "Change a running query's SQL and the new version replays history beside the old, splices "
            "onto the live stream where the old one stands, and takes the name only when both have read "
            "the same input.",
            "/about/competitive#bluegreen-replacement-with-backfill"),
    Feature("journal-bookmark", "Governed live answers",
            "The Pravaha Catalog names every stream, view and alert, with owners, tags, grants, and row "
            "filters and column masks as objects — enforced on every read and subscription, and "
            "revocation ends a stream already open.",
            "/about/competitive#a-governed-catalogue-of-live-answers"),
    Feature("bell", "Alerts that clear",
            "An alert fires when a key's row enters a view and clears when it leaves, because the view "
            "is fed retractions — with every decision journalled, so no re-fire and no lost clear across "
            "a restart.",
            "/about/competitive#alerts-that-fire-and-clear"),
    Feature("layers", "Queries on queries",
            "A query can read another query's answer — its snapshot, then each commit's rows leaving and "
            "entering — and stays exact across restarts, whichever of the two checkpointed later.",
            "/about/competitive#queries-on-queries"),
    Feature("slash-circle", "Refuses rather than guesses",
            "A query it cannot keep bounded, a masked column used as a key, a filter that restricts "
            "nothing: each is refused when it is registered, with a PRV code and the reason, never "
            "answered wrongly later.",
            "/about/competitive#refusing-unbounded-state-at-plan-time"),
    Feature("chat-square-text", "Any model, the engine as judge",
            "Describe a question in plain English and any model drafts the SQL — Anthropic, OpenAI, "
            "Bedrock, Vertex, Ollama or your own — while the engine plans, fingerprints and explains "
            "it, and a person confirms.",
            "/about/competitive#plain-english-to-continuous-sql-with-the-engine-as-judge"),
    Feature("hdd-network", "The view is the serving store",
            "The answer is read by key where it is maintained, over Flight SQL or the PostgreSQL "
            "protocol — psql, Grafana, Power BI — so there is no second database to load and keep in "
            "step.",
            "/about/competitive#serving-its-own-results"),
    Feature("funnel", "Store-native pushdown",
            "Filters and projections are pushed into JDBC, Aerospike and Cassandra, and a continuous "
            "COUNT or SUM into a JDBC poll, so rows a query does not need never leave the store.",
            "/about/competitive#store-native-pushdown"),
    Feature("bug", "A debugger over a running query",
            "Fork a query from a checkpoint into a copy nothing can read, step it row by row with every "
            "operator's rows in and out, and export the incident as a JUnit test that passes.",
            "/about/competitive#time-travel-debugging"),
    Feature("box", "Embedded or served",
            "The whole engine runs inside an application with no Spring and no network, as a Spring "
            "Boot bean, or as a server — the same SQL in all three.",
            "/about/competitive#embeddable-in-process"),
]


#: What is built, by area, as MAYA's Capabilities card draws it: short lines, each true of the
#: tree today. The README's "What works" says each at length, and the page offers it in full.
CAPABILITIES: list[tuple[str, str, list[str]]] = [
    ("code-square", "Continuous SQL", [
        "Calcite plans; Pravaha's own operators run it, as generated code over off-heap rows",
        "Tumbling and hopping windows, event time, watermarks, late-data corrections",
        "Stream-stream, self and temporal lookup joins; top-N; exact DECIMAL",
        "Identical questions share one computation; queries read other queries",
        "Blue/green replacement at an exact position; a time-travel debugger",
    ]),
    ("plug", "Sources and sinks", [
        "Files, feed directories, Delta, JDBC, Aerospike, Cassandra, Kafka",
        "PostgreSQL and MySQL change data capture, with no Debezium",
        "Filters, projections and COUNT/SUM pushed into the store",
        "JDBC, Kafka, Delta and Iceberg sinks exactly once; Aerospike and files",
        "A plugin SPI with its TCK; TLS to every networked store",
    ]),
    ("shield-lock", "Serving and governance", [
        "Read by key, SQL scan or subscription over Arrow Flight SQL",
        "The PostgreSQL wire protocol for psql, DBeaver, Grafana and Power BI",
        "The Pravaha Catalog: namespaces, owners, tags, inherited grants",
        "Row filters and column masks as objects, on every read and stream",
        "Users, passwords, scoped API keys and sessions; an audit trail",
    ]),
    ("activity", "Operating it", [
        "Checkpoints, a registry journal, a standby that takes over",
        "Lanes: automatic sharing, a lane of its own, rebalance by an administrator",
        "Alerts that fire and clear, to signed webhooks",
        "Prometheus metrics, Grafana dashboards, JSON logs, OpenTelemetry traces",
        "Java and Python SDKs, a CLI, REST with OpenAPI, this console",
    ]),
]


#: Stated so nobody has to discover them. Each is in the README's "What is not built" or the
#: gate pack, and the competitive landscape scores it.
LIMITS: list[str] = [
    "One node. Multi-node execution is designed and on hold by the owner's decision; a node refuses "
    "PARTITIONED mode (PRV-9002) rather than pretend.",
    "The eight-lane scaling gate is not reached: 28–42 % of linear against a 90 % target, measured on a "
    "development laptop, with no reference hardware.",
    "12 of Nexmark's 23 queries run; what is missing is SQL — session windows, recursive queries — not "
    "speed. The head-to-head comparison with a dataflow engine has not been run.",
    "MFA and single sign-on were dropped by the owner: the engine keeps its own users, passwords, API "
    "keys and sessions.",
    "The Iceberg sink writes local-filesystem tables only; MySQL CDC has no initial snapshot yet; a CDC "
    "binding feeds one query.",
    "The catalogue's lineage, labels, cross-tenant shares and access history (phases 3 and 4) are not "
    "built.",
    "No managed service, no published artefact, no vendor: one author's work, tried by a QA team on one "
    "node. The manual WCAG 2.2 AA audit is a person's task, not yet done.",
]

#: The technology, as MAYA's badges.
TECHNOLOGY: list[str] = [
    "Java 21", "Apache Calcite", "Janino", "Off-heap binary rows", "Z-sets (DBSP)", "Apache Arrow Flight SQL",
    "PostgreSQL wire protocol", "Spring Boot (server only)", "Delta Kernel", "iceberg-core",
    "Micrometer", "OpenTelemetry", "Argon2id", "Python SDK", "FastAPI", "Bootstrap 5", "Monaco",
    "ECharts",
]


@dataclass(frozen=True)
class Reading:
    """A long-form piece about Pravaha: served by the console when the installation carries the
    file (``key`` is its name under /about/papers/), otherwise named by its repository path."""
    icon: str
    title: str
    what: str
    path: str           # relative to the repository root
    key: str = ""       # the name /about/papers/ serves it under; empty when not served


READING: list[Reading] = [
    Reading("file-earmark-pdf", "Continuous Queries as Maintained Answers",
            "The research paper: exact cuts, exact seams and lossless cutover, stated and proved under "
            "listed assumptions, with a ledger of what is tested and what is argued.",
            "docs/research/continuous-queries-as-maintained-answers.pdf",
            "continuous-queries-as-maintained-answers.pdf"),
    Reading("file-earmark-slides", "A continuous SQL engine: design and evidence",
            "The deck, 91 slides: why ask once, the vocabulary, a query's life, correctness, scale on one "
            "node, connectors, security, operating it, and what is measured.",
            "docs/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx",
            "pravaha-design-and-evidence.pptx"),
    Reading("journal-text", "Keeping the Answer: Inside Pravaha",
            "The Medium post: the design decisions, what each one costs, and the alternatives turned "
            "down, from the retail stock case outwards.",
            "docs/medium/pravaha-medium-post.md"),
]

_MEDIA = {"pdf": "application/pdf",
          "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation"}

#: What /about/papers/<key> may serve, and nothing else: a name is looked up here, never joined
#: onto a path, so no request can reach another file.
PAPERS: dict[str, tuple[str, str]] = {r.key: (r.path, _MEDIA[r.key.rsplit(".", 1)[-1]])
                                      for r in READING if r.key}

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
        return self._labelled(_section(self._read("README.md"), "What works"))

    def not_built(self) -> str:
        """The README's list of what is not built, or not finished, rendered as it is written."""
        return self.renderer.render(_section(self._read("README.md"), "What is not built, or not finished"))[0]

    def how_built(self) -> list[dict[str, str]]:
        """The README's "How it is built" table."""
        return self._labelled(_section(self._read("README.md"), "How it is built"))

    def _labelled(self, section: str) -> list[dict[str, str]]:
        """One entry per ``- **Area** — text`` item, or per ``| **Area** | text |`` row: the README
        holds these as lists (a long table stops GitHub's mobile app rendering the page), and a table
        is still read, so either form of the file gives the page the same rows."""
        rows = []
        for line in section.splitlines():
            stripped = line.strip()
            item = re.match(r"^- \*\*(.+?)\*\* — (.*)$", stripped)
            if item:
                rows.append({"area": item.group(1), "html": self.renderer.render(item.group(2))[0]})
                continue
            cells = [c.strip() for c in stripped.strip("|").split("|")]
            if stripped.startswith("|") and len(cells) >= 2 and cells[0].startswith("**"):
                rows.append({"area": cells[0].strip("*"),
                             "html": self.renderer.render("|".join(cells[1:]))[0]})
        return rows

    def status_line(self) -> str:
        match = re.search(r"^> \*\*Project status:(.*?)(?=\n\n)", self._read("README.md"), re.MULTILINE | re.DOTALL)
        if not match:
            return ""
        text = "**Project status:" + re.sub(r"\n> ?", " ", match.group(1))
        return self.renderer.render(text)[0]

    def release(self) -> dict[str, str]:
        """The newest entry of docs/RELEASE_NOTES.md -- its heading and its body, rendered.

        Read on every request, so the page says what the notes say the moment they change; an
        empty dict when the notes are missing, and the template then says nothing about a release.
        """
        text = self._read("docs/RELEASE_NOTES.md")
        match = re.search(r"^## (.+?)\n(.*?)(?=^## |^---\s*$|\Z)", text, re.MULTILINE | re.DOTALL)
        if not match:
            return {}
        body = match.group(2).strip()
        # MAYA's "In this release" lists highlights: here, the bold lead of each top-level entry,
        # which is how every entry of the notes begins.
        leads = re.findall(r"^- \*\*(.+?)\*\*", body, re.MULTILINE | re.DOTALL)
        highlights = [self._inline(re.sub(r"\s*\n\s*", " ", lead).rstrip(".:")) for lead in leads]
        return {"title": match.group(1).strip(), "html": self.renderer.render(body)[0],
                "highlights": highlights}

    def _inline(self, markdown: str) -> str:
        html = self.renderer.render(markdown)[0].strip()
        return html[3:-4] if html.startswith("<p>") and html.endswith("</p>") else html

    def readings(self) -> list[dict[str, str]]:
        """The paper, the deck and the post: a link where the console serves the file (it is in
        this installation and has a name under /about/papers/), otherwise its repository path."""
        out = []
        for r in READING:
            served = bool(r.key) and (self.root / r.path).is_file()
            out.append({"icon": r.icon, "title": r.title, "what": r.what, "path": r.path,
                        "href": f"/about/papers/{r.key}" if served else ""})
        return out

    def paper(self, key: str) -> tuple[Path, str] | None:
        """The file /about/papers/<key> serves and its media type, or None: only a name PAPERS
        lists, and only when this installation carries the file."""
        entry = PAPERS.get(key)
        if entry is None:
            return None
        path = self.root / entry[0]
        return (path, entry[1]) if path.is_file() else None

    def landscape(self):
        """The competitive landscape, from docs/COMPETITIVE_LANDSCAPE.md (core/competitive.py)."""
        from core.competitive import Landscape

        return Landscape(self.root, self.renderer)

    def legal(self) -> str:
        return self.renderer.render(_section(self._read("README.md"), "Legal"))[0]

    def licence(self) -> str:
        return self._read("LICENSE")
