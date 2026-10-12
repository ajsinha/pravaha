"""
Pravaha console — the help catalog.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

One declarative catalogue, ``config/help.yaml`` (drishti's design: its ``help.yaml`` and
``core/guides.py``), and the help index is rendered from it: five categories -- Start, Use,
Build, Operate, AI -- each a list of topic sections and the guides it offers; the retired
addresses (``moved:``); and the help each screen opens (``contextual:``, F1's target first).

Three kinds of help live side by side, and each has one home:

* **Topics** -- ``content/topics/*.md``. Short enough to finish, deep enough that a reader
  never has to open the repository: an options table, a complete worked example with its
  expected output, the pitfalls, and a link to the long-form guide for the rest. A topic
  **registers itself**: its front matter names its ``category`` -- one of the catalogue's topic
  sections -- and the index picks it up.
  Nothing here lists topics by name, so adding one is adding a file.
* **Guides** -- ``content/help/*.md``, each an ``include:`` of a document in ``docs/``. The
  long form, rendered in place. A topic names its companion guide (``guide:``), which the
  shared footer turns into "Full reference"; a category names the default one.
* **Codes** -- every ``PRV-nnnn``, at ``/help/codes/{code}``, gathered from the documents.

What this module owns: reading the catalogue, and the lookups the routes and templates need.
What it does not own: the categories, which are in help.yaml, or any topic's words.

The checks that keep it honest live in ``tests/test_help.py``: a category nobody fills, a
topic whose category does not exist (a page no card links to), a card or screen entry that
names a page that does not exist, and a companion guide that is not served all fail the build.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from core.content.library import ContentLibrary, Topic

#: The one catalogue (drishti's design): categories, the guides each lists, moved, contextual.
CATALOGUE = Path(__file__).resolve().parents[1] / "config" / "help.yaml"

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
    badge: str = ""           # the upper-case label a topic may carry (SOURCE, PRV-2XXX)
    keywords: str = ""        # extra words the index search matches, never shown
    anchor: str = ""          # a section of the topic, for a screen card that wants one part of it
    #: drishti's ``badge-kind``: tutorial, reference, guide or examples.
    type: str = "guide"

    @property
    def href(self) -> str:
        if self.kind == "topic":
            return f"{TOPIC_PATH}/{self.slug}" + (f"#{self.anchor}" if self.anchor else "")
        if self.kind == "guide":
            return f"{GUIDE_PATH}/{self.slug}"
        return self.slug


@dataclass(frozen=True)
class Section:
    """A group of topics inside a category -- what a topic's front matter ``category:`` names --
    and the long-form guide a topic in it points at when it names none itself."""
    id: str
    guide: str = ""


@dataclass
class Category:
    id: str
    name: str
    icon: str
    blurb: str
    #: The long-form guide of the category's first section, for a caller that wants one.
    guide: str = ""
    #: Cards that are not topics: a guide, the code browser. Shown after the topics.
    extras: list[Card] = field(default_factory=list)
    #: The topic sections gathered here, in order.
    sections: list[Section] = field(default_factory=list)


def _extra(entry: dict[str, Any]) -> Card:
    """A ``guides:`` entry of help.yaml as a card: ``guide:`` a long-form guide, ``href:`` a page."""
    common = {"title": str(entry["title"]), "summary": str(entry.get("summary", "")),
              "icon": str(entry.get("icon", "journal-text")), "keywords": str(entry.get("keywords", ""))}
    if entry.get("guide"):
        return Card("guide", str(entry["guide"]), type=str(entry.get("kind", "reference")), **common)
    return Card("page", str(entry["href"]), type=str(entry.get("kind", "reference")), **common)


def load(path: Path = CATALOGUE) -> tuple[list[Category], dict[str, str], dict[str, list[str]]]:
    """help.yaml as its categories, its retired addresses and its screens' help."""
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    categories = []
    for c in data.get("categories") or []:
        sections = [Section(str(s["id"]), str(s.get("guide", ""))) for s in c.get("sections") or []]
        categories.append(Category(str(c["id"]), str(c["name"]), str(c.get("icon", "book")),
                                   str(c.get("blurb", "")), sections[0].guide if sections else "",
                                   [_extra(g) for g in c.get("guides") or []], sections))
    moved = {str(k): str(v) for k, v in (data.get("moved") or {}).items()}
    contextual = {str(k): [str(e) for e in v] for k, v in (data.get("contextual") or {}).items()}
    return categories, moved, contextual


CATEGORIES, MOVED, SCREEN_HELP = load()

CATEGORY_IDS = [c.id for c in CATEGORIES]
#: Every topic section, and the category that holds it.
SECTIONS: dict[str, tuple[Category, Section]] = {s.id: (c, s) for c in CATEGORIES for s in c.sections}

#: The most cards a screen offers. Three is what a person reads at the foot of a page; the rest
#: of the help is one click away on the index.
SCREEN_CARDS = 3

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
        """A category by its id, or the category that holds a topic section of that id."""
        found = next((c for c in CATEGORIES if c.id == category_id), None)
        return found or (SECTIONS[category_id][0] if category_id in SECTIONS else None)

    @staticmethod
    def listing(slug_or_href: str) -> Category | None:
        """The category whose ``guides:`` offer a guide (by slug) or a page (by its path)."""
        return next((c for c in CATEGORIES for card in c.extras
                     if card.slug == slug_or_href), None)

    @staticmethod
    def section(topic: Topic) -> Section | None:
        """The topic section a topic's front matter names, or None."""
        held = SECTIONS.get(str(topic.meta.get("category", "")))
        return held[1] if held else None

    def topics_in(self, category_id: str) -> list[Topic]:
        """A category's topics, section by section in the catalogue's order; or one section's."""
        category = next((c for c in CATEGORIES if c.id == category_id), None)
        wanted = [s.id for s in category.sections] if category else [category_id]
        found = self.topics()
        return [t for sid in wanted for t in found if t.meta.get("category") == sid]

    @staticmethod
    def card(topic: Topic) -> Card:
        meta = topic.meta
        headings = " ".join(h["name"] for h in topic.headings)
        codes = " ".join(sorted(set(_CODE.findall(topic.body))))
        keywords = " ".join(str(k) for k in (meta.get("keywords") or []))
        return Card("topic", topic.slug, topic.title, topic.summary,
                    meta.get("icon", topic.icon) or "file-text", str(meta.get("badge", "") or ""),
                    f"{keywords} {headings} {codes}".strip(), type=str(meta.get("kind", "guide")))

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
        section = self.section(topic)
        target = str(topic.meta.get("guide") or (section.guide if section else "") or "")
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
        section = self.section(topic)
        siblings = [self.card(t) for t in self.topics() if t.meta.get("category") == section.id
                    if t.slug != topic.slug and t.slug not in {c.slug for c in named_cards}] if section else []
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

    def context(self, screen: str) -> str:
        """Where F1 goes on a screen (drishti's ``/help/context/{screen}``): its first help topic,
        at its section when it names one; the help index for a screen with none."""
        for card in self.for_screen(screen)[:1]:
            return card.href
        return "/help"

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
