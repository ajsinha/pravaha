"""
Pravaha console — the help catalog.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

One declarative catalog, and the help index is rendered from it.

Three kinds of help live side by side, and each has one home:

* **Topics** -- ``content/topics/*.md``. Short enough to finish, deep enough that a reader
  never has to open the repository: an options table, a complete worked example with its
  expected output, the pitfalls, and a link to the long-form guide for the rest. A topic
  **registers itself**: its front matter names its ``category`` and the index picks it up.
  Nothing here lists topics by name, so adding one is adding a file.
* **Guides** -- ``content/help/*.md``, each an ``include:`` of a document in ``docs/``. The
  long form, rendered in place. A topic names its companion guide (``guide:``), which the
  shared footer turns into "Full reference"; a category names the default one.
* **Codes** -- every ``PRV-nnnn``, at ``/help/codes/{code}``, gathered from the documents.

What this module owns: the categories and their order, the extra cards a category carries
that are not topics (a guide, the code browser), which topics each product screen offers as
contextual help, and the lookups the routes and templates need. What it does not own: any
topic's words.

The checks that keep it honest live in ``tests/test_help.py``: a category nobody fills, a
topic whose category does not exist (a page no card links to), a card or screen entry that
names a page that does not exist, and a companion guide that is not served all fail the build.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from core.content.library import ContentLibrary, Topic

#: The area under content/ that holds the topics, and the URL they are served under.
TOPIC_AREA = "topics"
TOPIC_PATH = "/help/topics"
#: The area that holds the long-form guides, and where they are served.
GUIDE_AREA = "help"
GUIDE_PATH = "/help"


@dataclass(frozen=True)
class Card:
    """One card on the index: a topic, a guide, or a page such as the code browser."""
    kind: str                 # "topic" | "guide" | "page"
    slug: str                 # topic or guide slug; for a page, its path
    title: str
    summary: str
    icon: str
    badge: str = ""
    keywords: str = ""        # extra words the index search matches, never shown
    anchor: str = ""          # a section of the topic, for a screen card that wants one part of it

    @property
    def href(self) -> str:
        if self.kind == "topic":
            return f"{TOPIC_PATH}/{self.slug}" + (f"#{self.anchor}" if self.anchor else "")
        if self.kind == "guide":
            return f"{GUIDE_PATH}/{self.slug}"
        return self.slug


@dataclass
class Category:
    id: str
    name: str
    icon: str
    blurb: str
    #: The long-form guide a topic in this category points at when it names none itself.
    guide: str = ""
    #: Cards that are not topics: a guide, the code browser. Shown after the topics.
    extras: list[Card] = field(default_factory=list)


def _guide(slug: str, title: str, icon: str, summary: str, badge: str = "GUIDE") -> Card:
    return Card("guide", slug, title, summary, icon, badge)


def _page(path: str, title: str, icon: str, summary: str, badge: str = "", keywords: str = "") -> Card:
    return Card("page", path, title, summary, icon, badge, keywords)


CATEGORIES: list[Category] = [
    Category("start", "Getting started", "flag",
             "What Pravaha is, a first maintained view end to end, and which way in suits you.",
             guide="quickstart",
             extras=[_guide("quickstart", "Quick start (long form)", "flag",
                            "From an empty engine to a maintained view, including the refusals — "
                            "every command real."),
                     _page("/tutorials", "Tutorials", "journal-code",
                           "Learn Pravaha step by step: a first maintained view, following a view, changing a "
                           "running query safely, investigating an incident, and joining Aerospike with a CSV file.",
                           "TUTORIALS", "tutorial lesson learn walkthrough aerospike"),
                     _page("/help/case-studies", "Case studies", "journal-code",
                           "Worked systems, one domain each, from their data to their continuous queries — "
                           "each study's README, with the output of a real run.",
                           "CASE STUDIES", "case study worked example domain banking trading biology"),
                     _guide("developing-in-an-ide", "Running in IntelliJ and PyCharm", "laptop",
                            "The server in IntelliJ IDEA and the console in PyCharm: shared run configurations, "
                            "the JVM flags Flight needs, debugging and tests.")]),
    Category("concepts", "Concepts", "lightbulb",
             "The ideas the engine is built on: streams, continuous queries, views and keys, "
             "weights and retractions, windows, event time, corrections and sharing.",
             guide="concepts",
             extras=[_guide("concepts", "Concepts (long form)", "lightbulb",
                            "The model underneath everything else, with the soundness rule it rests on.")]),
    Category("sql", "Writing SQL", "code-square",
             "The SQL the engine runs and the SQL it refuses — with the reason for every refusal — "
             "the statements that register and manage queries, windows, joins and parameters.",
             guide="continuous-queries",
             extras=[_guide("continuous-queries", "Streams, queries and SQL (long form)", "code-square",
                            "The single source of truth for what you write and what happens when you write it.")]),
    Category("reading", "Reading answers", "eye",
             "A view is read, not re-run: point reads, subscriptions to its changes, what a read "
             "is consistent with, the PostgreSQL gateway, Power BI, the HTTP API, and every client and SDK.",
             guide="user-guide",
             extras=[_guide("user-guide", "User guide (long form)", "book",
                            "Registering, reading and subscribing from the CLI and both SDKs."),
                     _guide("python-sdk", "Python SDK", "code-slash",
                            "The Python client: queries, subscriptions, registration, the catalog, errors."),
                     _guide("python-api-guide", "Python integration guide", "plug",
                            "Every SDK call and REST endpoint, one by one, each with a verified sample: "
                            "the page to hand a team connecting an application.")]),
    Category("sources", "Sources", "box-arrow-in-right",
             "Where rows come from. One page per connector: its options, a complete binding, what "
             "it pushes down, what it guarantees, and how it goes wrong.",
             guide="continuous-queries#21-every-source-type-configured",
             extras=[_guide("connectors", "Connectors (long form)", "plug",
                            "The plugin SPI, a worked source end to end, the TCK, cross-source joins "
                            "and change-data-capture.")]),
    Category("sinks", "Sinks", "box-arrow-right",
             "Where answers go. How a query names a sink, the shape check, delivery from at-least-once "
             "to exactly-once, and one page per shipped sink.",
             guide="continuous-queries#4-reading-the-answer"),
    Category("operating", "Operating", "speedometer2",
             "Running a node: configuration and every setting, lanes (sized, shared, dedicated), state and "
             "spill, checkpoints and recovery, standby, observability and alerts, changing a running query.",
             guide="operations",
             extras=[_guide("operations", "Operations (long form)", "gear",
                            "Every setting, every metric, and the runbooks."),
                     _guide("execution-model", "Execution model", "cpu",
                            "How a registered query executes: lanes, batches, commits and checkpoints."),
                     _guide("deploying", "Deploying", "box-seam",
                            "The one root under /opt/pravaha, a QA host from two images and two files, "
                            "the container image, the Helm chart, upgrades and releases.")]),
    Category("security", "Security", "shield-lock",
             "Who may connect, what they may read and do, the grants and namespaces of the catalogue, the "
             "row filters and masks that follow them into every view, the audit trail, and TLS everywhere.",
             guide="security",
             extras=[_guide("security", "Security (long form)", "shield-lock",
                            "Authentication, the policies, row-level security and the audit trail."),
                     _guide("connector-tls", "TLS everywhere (long form)", "shield-lock-fill",
                            "Every encrypted connection Pravaha makes or accepts.")]),
    Category("embedding", "Embedding", "plug",
             "Pravaha inside your own JVM process: the embedded engine and the Spring Boot starter.",
             guide="architecture-hosts"),
    Category("errors", "Errors", "exclamation-octagon",
             "Every PRV code, range by range: what it means, why the engine says it, and what to do.",
             guide="troubleshooting",
             extras=[_page("/help/codes", "Every code", "list-ol",
                           "All PRV codes in one table, each opening its own page.", "INDEX",
                           "prv code error number list"),
                     _guide("troubleshooting", "Troubleshooting (long form)", "life-preserver",
                            "Nothing is happening, the numbers are wrong, it ran out of memory — by symptom.")]),
    Category("reference", "Reference", "journal-bookmark",
             "The glossary, the CLI and the assistant — "
             "and the long-form guides behind all of it.",
             guide="operations",
             extras=[_page("/help/guides", "All guides", "journal-richtext",
                           "Every long-form document the engine ships with, rendered in place.",
                           "BROWSER", "guide document long form reference manual"),
                     _guide("architecture", "Architecture", "diagram-3",
                            "How every component fits together: the module graph, a row and a registration "
                            "traced end to end, and the query and feed lifecycles."),
                     _guide("architecture-components", "Architecture, component by component", "diagram-3",
                            "One page per layer — planning, runtime, registry, ingest, serving, governance, "
                            "hosts, clients — with key types, threads, invariants, codes and examples."),
                     _guide("decisions", "Decision records", "signpost-split",
                            "Every architecture decision, numbered and never renumbered."),
                     _guide("system-design", "System design", "diagram-2",
                            "The whole design, including what is not built yet.")]),
    # What the product is as a product: the questions people actually ask, what changed, what
    # is left, and where it stands against the alternatives. The notes and the roadmap are guides
    # over documents in docs/, and the landscape is its own page drawn from one, so no page can
    # say something its document does not.
    Category("about", "About Pravaha", "info-circle",
             "The questions a QA team or an integrator asks first, what changed in each cut, what "
             "is still to build, and where Pravaha stands against the alternatives — losses included.",
             guide="whats-new",
             extras=[_page("/about", "About Pravaha", "info-circle",
                           "What it is, the problems it solves, what is built and measured, and who made it.",
                           "", "about overview author licence version"),
                     _guide("whats-new", "What's new", "megaphone",
                            "The release notes: what changed in each cut, with the numbers and the "
                            "defects still open.", "NOTES"),
                     _guide("roadmap", "Roadmap", "map",
                            "What is left to build, in batches, and what is deliberately not scheduled.",
                            "PLAN"),
                     _page("/about/competitive", "Competitive landscape", "bar-chart-steps",
                           "Scored by category against dataflow engines, streaming databases and "
                           "Kafka-native tools, with a card per row saying how — and where Pravaha loses.",
                           "COMPARE", "competitive landscape comparison flink materialize risingwave "
                           "ksqldb kafka streams hazelcast versus alternative")]),
]

CATEGORY_IDS = [c.id for c in CATEGORIES]

#: The most cards a screen offers. Three is what a person reads at the foot of a page; the rest
#: of the help is one click away on the index.
SCREEN_CARDS = 3

#: The contextual help each product screen offers, most relevant first. The first is also the
#: target of the screen's "?" link. An entry is a topic, or ``topic#section`` for one part of a
#: longer page. Checked by a test: every entry is a topic that exists, every section one it has,
#: no screen offers more than SCREEN_CARDS, and every screen a template names is here.
SCREEN_HELP: dict[str, list[str]] = {
    "start": ["getting-started#your-first-maintained-view", "streams", "views-and-keys"],
    "overview": ["getting-started#the-console-screen-by-screen", "observability", "query-lifecycle"],
    "workbench": ["sql-reference", "sql-refusals", "backfill-cutover#comparing-two-versions"],
    "catalog": ["streams", "catalog-and-grants", "sources-overview"],
    "catalog-object": ["catalog-and-grants", "row-filters-and-masks", "streams"],
    "stream": ["streams", "event-time-watermarks", "sources-overview"],
    "views": ["views-and-keys", "clients", "pgwire"],
    "view": ["views-and-keys#point-reads", "clients", "power-bi"],
    "live": ["subscriptions", "zset-weights", "event-time-watermarks#late-data"],
    "operations": ["observability", "reading-a-plan", "lanes"],
    "alerts": ["alerts", "observability", "views-and-keys"],
    "queries": ["query-lifecycle", "sharing", "create-continuous-query#queries-on-queries"],
    "query": ["query-lifecycle", "sharing", "backfill-cutover"],
    # B9. The blue/green screen (design 23.10): comparing the versions, what the backfill reads and
    # why there is no ETA on it, what a cutover moves, and how long a rollback stays open.
    "replacement": ["backfill-cutover", "query-lifecycle", "sharing"],
    # B9. The debugger (design 23.9, ADR-048): what a fork starts from, and what the weights on
    # its view changes mean.
    "debug": ["time-travel-debugger", "checkpoints-recovery", "zset-weights"],
    "dead-letters": ["dead-letters", "sources-overview", "observability"],
    "plugins": ["sources-overview", "sinks-overview", "connector-security"],
    "admin-users": ["authentication", "catalog-and-grants#user-attributes-as-claims", "audit"],
    "admin-keys": ["authentication", "authorization", "audit"],
    "admin-sessions": ["authentication", "audit", "authorization"],
    "admin-lanes": ["lanes", "observability", "reading-a-plan"],
    "admin-grants": ["catalog-and-grants", "row-filters-and-masks", "authorization"],
    "admin-policies": ["row-filters-and-masks", "catalog-and-grants", "audit"],
    # ADR-058 phase 3: Admin · AI models, and an assist answer drawn as a page of its own.
    "ai-models": ["assistant#admin-ai-models", "assistant", "observability"],
    "assistant": ["assistant", "sql-refusals", "create-continuous-query"],
    # The rest of the pages, for their "About this page" panel (core/page_help.py), whose
    # "More in Help" links are these.
    "alert": ["alerts", "views-and-keys", "subscriptions"],
    "admin-access": ["authorization", "authentication", "catalog-and-grants"],
    "admin-audit": ["audit", "authorization", "authentication"],
    "admin-tenants": ["configuration", "sharing", "audit"],
    "components": ["getting-started#the-console-screen-by-screen"],
    "landing": ["getting-started", "zset-weights", "views-and-keys"],
    "login": ["authentication"],
    "login-reset": ["authentication"],
    "account": ["authentication", "authorization", "clients"],
    "account-password": ["authentication"],
    "about": ["getting-started", "faq", "glossary"],
    "competitive": ["faq", "glossary"],
    "not-found": ["getting-started", "faq"],
    "refused": ["errors-overview", "sql-refusals", "authorization"],
}

#: Topics that were merged into another, and where each one's content now is. The old address
#: answers 301 to the new one, so a bookmark, a link in somebody's runbook or a page not yet
#: updated keeps working. Checked by a test: every target is a topic and section that exists, and
#: no page the help serves still links to an old address.
MOVED: dict[str, str] = {
    "start-here": "getting-started",
    "first-view": "getting-started#your-first-maintained-view",
    "console-tour": "getting-started#the-console-screen-by-screen",
    "choosing-a-client": "clients",
    "client-snippets": "clients#snippets",
    "sdk-reference": "clients#sdk-reference",
    "point-reads": "views-and-keys#point-reads",
    "windows-worked": "windows#worked-examples",
    "late-data": "event-time-watermarks#late-data",
    "sql-types": "sql-reference#types-nulls-and-expressions",
    "sql-parameters": "sql-reference#parameters",
    "temporal-joins": "joins#temporal-joins",
    "compare-versions": "backfill-cutover#comparing-two-versions",
    "sizing-lanes": "lanes#sizing-lanes",
    "lane-sharing": "lanes#sharing-lanes",
    "metrics-alerts": "observability",
    "metrics-index": "observability#every-metric",
    "settings-index": "configuration#every-setting",
    "row-filters": "row-filters-and-masks",
    "admin-ai-models": "assistant#admin-ai-models",
    "cluster-mode": "standby#cluster-mode",
}

#: Each PRV range and the errors topic that explains it (the /help/codes browser and each
#: code's own page link here). Checked by a test like the screens.
ERROR_FAMILIES: dict[str, tuple[str, str]] = {
    "1": ("Configuration, API and client", "errors-config"),
    "2": ("SQL", "errors-sql"),
    "3": ("Runtime and code generation", "errors-runtime"),
    "4": ("State, backfill and serving", "errors-state"),
    "5": ("Plugins", "errors-plugins"),
    "6": ("The Flight gateway and the PostgreSQL gateway", "errors-gateway"),
    "7": ("Security", "errors-security"),
    "8": ("The query registry", "errors-registry"),
    "9": ("Clustering and partition ownership", "errors-cluster"),
}

_WORD = re.compile(r"[a-z0-9][a-z0-9._-]*")
_CODE = re.compile(r"PRV-\d{4}")


class HelpCatalog:
    """The catalog over the content on disk: categories filled with the topics that name them."""

    def __init__(self, content: ContentLibrary) -> None:
        self.content = content

    # ---------------------------------------------------------------- topics
    def topics(self) -> list[Topic]:
        return self.content.topics(TOPIC_AREA)

    def topic(self, slug: str) -> Topic | None:
        return self.content.get(TOPIC_AREA, slug)

    def guides(self) -> list[Topic]:
        return self.content.topics(GUIDE_AREA)

    def guide(self, slug: str) -> Topic | None:
        return self.content.get(GUIDE_AREA, slug.split("#", 1)[0])

    def category(self, category_id: str) -> Category | None:
        return next((c for c in CATEGORIES if c.id == category_id), None)

    def topics_in(self, category_id: str) -> list[Topic]:
        return [t for t in self.topics() if t.meta.get("category") == category_id]

    @staticmethod
    def card(topic: Topic) -> Card:
        meta = topic.meta
        headings = " ".join(h["name"] for h in topic.headings)
        codes = " ".join(sorted(set(_CODE.findall(topic.body))))
        keywords = " ".join(str(k) for k in (meta.get("keywords") or []))
        return Card("topic", topic.slug, topic.title, topic.summary,
                    meta.get("icon", topic.icon) or "file-text", str(meta.get("badge", "") or ""),
                    f"{keywords} {headings} {codes}".strip())

    # ----------------------------------------------------------------- index
    def index(self) -> list[dict[str, Any]]:
        """Every category with its cards, in order: its topics, then its extras.

        A topic whose front matter says ``listed_on: <slug>`` -- one connector's page, one range
        of error codes -- is not a card of its own: the page it names lists it (the Sources card
        opens every source), so the index stays a page somebody can scan. It is still searched,
        still in the reading order, and a test holds that its list page links to it."""
        out = []
        for category in CATEGORIES:
            cards = [self.card(t) for t in self.topics_in(category.id) if not t.meta.get("listed_on")]
            cards += list(category.extras)
            out.append({"category": category, "cards": cards})
        return out

    # ---------------------------------------------------------------- footer
    def companion(self, topic: Topic) -> dict[str, str] | None:
        """The long-form guide behind a topic: its own ``guide:``, else its category's."""
        category = self.category(str(topic.meta.get("category", "")))
        target = str(topic.meta.get("guide") or (category.guide if category else "") or "")
        if not target:
            return None
        slug, _, anchor = target.partition("#")
        guide = self.guide(slug)
        if guide is None:
            return None
        return {"href": f"{GUIDE_PATH}/{slug}" + (f"#{anchor}" if anchor else ""),
                "title": guide.title, "summary": guide.summary}

    def related(self, topic: Topic) -> dict[str, Any]:
        """The shared footer: the companion guide, the topics this one names, its category's others."""
        category = self.category(str(topic.meta.get("category", "")))
        named = [self.topic(s) for s in (topic.meta.get("related") or [])]
        named_cards = [self.card(t) for t in named if t is not None and t.slug != topic.slug]
        siblings = [self.card(t) for t in self.topics_in(category.id)
                    if t.slug != topic.slug and t.slug not in {c.slug for c in named_cards}] if category else []
        return {"category": category, "companion": self.companion(topic),
                "named": named_cards, "siblings": siblings}

    def neighbours(self, topic: Topic) -> tuple[Card | None, Card | None]:
        """The previous and next topic in reading order: within the category, then across."""
        ordered = [t for c in CATEGORIES for t in self.topics_in(c.id)]
        slugs = [t.slug for t in ordered]
        if topic.slug not in slugs:
            return None, None
        i = slugs.index(topic.slug)
        before = self.card(ordered[i - 1]) if i > 0 else None
        after = self.card(ordered[i + 1]) if i + 1 < len(ordered) else None
        return before, after

    # --------------------------------------------------------------- screens
    def for_screen(self, screen: str) -> list[Card]:
        """The topics a product screen offers as contextual help. Unknown slugs are skipped here
        and fail the catalog test, rather than breaking a screen in production. A ``topic#section``
        entry is a card titled by that section, opening at it."""
        cards = []
        for entry in SCREEN_HELP.get(screen, [])[:SCREEN_CARDS]:
            slug, _, anchor = entry.partition("#")
            topic = self.topic(slug)
            if topic is None:
                continue
            card = self.card(topic)
            if anchor:
                heading = next((h["name"] for h in topic.headings if h["id"] == anchor), None)
                if heading is None:
                    continue
                card = Card("topic", slug, heading, _section_lede(topic.body, anchor) or topic.summary,
                            card.icon, card.badge, card.keywords, anchor)
            cards.append(card)
        return cards

    @staticmethod
    def moved(slug: str) -> str | None:
        """Where a merged topic's content is now (``slug#section``), or None."""
        return MOVED.get(slug)

    # ---------------------------------------------------------------- search
    def search(self, query: str, limit: int = 40) -> list[dict[str, Any]]:
        """Full-text search over topics and guides, best first.

        Every word must appear somewhere in a page for it to match. A title hit outweighs a
        heading, a heading outweighs the summary and keywords, and those outweigh the body --
        a page *about* checkpoints beats one that mentions them in passing. A query that is a
        code (``PRV-2050``) puts that code's own page first.
        """
        words = [w for w in _WORD.findall(query.lower()) if len(w) > 1]
        results: list[dict[str, Any]] = []
        code = _CODE.search(query.upper())
        if code:
            results.append({"kind": "code", "title": code.group(0), "href": f"/help/codes/{code.group(0)}",
                            "summary": "What the documentation says about this code.", "score": 10_000,
                            "category": "Errors"})
        if not words:
            return results
        pages: list[tuple[str, Topic, str]] = [("topic", t, f"{TOPIC_PATH}/{t.slug}") for t in self.topics()]
        pages += [("guide", g, f"{GUIDE_PATH}/{g.slug}") for g in self.guides()]
        for kind, page, href in pages:
            title = page.title.lower()
            headings = " ".join(h["name"] for h in page.headings).lower()
            summary = (page.summary + " " + " ".join(str(k) for k in (page.meta.get("keywords") or []))).lower()
            body = page.body.lower()
            score = 0
            for word in words:
                in_title, in_head = word in title, word in headings
                in_summary, hits = word in summary, body.count(word)
                if not (in_title or in_head or in_summary or hits):
                    score = 0
                    break
                score += 40 * in_title + 12 * in_head + 10 * in_summary + min(hits, 20)
            if score:
                if kind == "guide":
                    score = score * 2 // 3          # the topic is the better first answer
                category = self.category(str(page.meta.get("category", "")))
                results.append({"kind": kind, "title": page.title, "href": href, "summary": page.summary,
                                "score": score,
                                "category": category.name if category else "Guide",
                                "snippet": _snippet(page.body, words)})
        results.sort(key=lambda r: (-r["score"], r["title"]))
        return results[:limit]


def _section_lede(body: str, anchor: str, limit: int = 220) -> str:
    """The first paragraph of the section a heading ``{#anchor}`` opens, as plain text: what a
    screen card says when it opens one part of a longer topic, rather than the whole topic's summary."""
    heading = re.search(r"^#{2,3} .*\{#" + re.escape(anchor) + r"\}\s*$", body, re.MULTILINE)
    if not heading:
        return ""
    for paragraph in re.split(r"\n\s*\n", body[heading.end():]):
        text = paragraph.strip()
        if not text or text.startswith(("#", "|", "```", "!!!", "<!--", "-", ">")):
            continue
        text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
        text = re.sub(r"`|\*\*|(?<![\w*])\*(?![\s*])|(?<=\S)\*(?![\w*])", "", re.sub(r"\s+", " ", text))
        if len(text) <= limit:
            return text
        cut = text[:limit].rsplit(" ", 1)[0]
        return cut.rstrip(",;:—-") + " …"
    return ""


def _snippet(body: str, words: list[str], width: int = 180) -> str:
    """A line of plain text around the first hit, markdown syntax stripped."""
    text = re.sub(r"```.*?```", " ", body, flags=re.DOTALL)
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.DOTALL)
    text = re.sub(r"[`*_#>|]+", " ", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"\s+", " ", text).strip()
    lower = text.lower()
    at = min((lower.find(w) for w in words if w in lower), default=0)
    start = max(0, at - width // 3)
    snippet = text[start:start + width]
    return ("… " if start else "") + snippet + (" …" if start + width < len(text) else "")
