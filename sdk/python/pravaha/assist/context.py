"""What a model is told before it drafts a query: built from the engine, never from the model's
memory (ADR-058 §2, "context").

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

:class:`ContextBuilder` asks the engine, under the caller's own credentials, for exactly what the
API answers them:

* the streams they may read -- ``GET /api/v1/streams``, which the engine filters by read
  permission, intersected with the streams ``GET /api/v1/me/permissions`` names -- with their
  columns, types, event-time column and out-of-orderness;
* the registered queries whose views they may read (``GET /api/v1/queries``, intersected with the
  views ``/me/permissions`` names), each with its key and retention and, for the most relevant,
  its columns (``GET /api/v1/views/{name}``) -- a continuous query may read one (ADR-056);
* the sinks they may see (``GET /api/v1/sinks``) with the guarantee each gives;
* whether they may register at all.

To that it adds the **dialect** -- the guide's error table and its sections on aggregation, joins
and refusals, from the packaged dialect card -- and two to four **worked examples** from
``examples/case-studies`` chosen by similarity to the description. No row is ever read.

Everything is ordered deterministically, and a size budget keeps the prompt bounded: what is
least relevant to the description is left out first, and what was left out is said, by name, in
the prompt and on the result.
"""

from __future__ import annotations

import dataclasses
import functools
import json
import math
import re
from importlib import resources
from typing import TYPE_CHECKING, Any, Iterable, Mapping, Optional, Sequence

from pravaha.assist.prompts import DialectCard, load_card

if TYPE_CHECKING:
    from pravaha.api import EngineApi

#: The prompt's context, in characters (about a quarter as many tokens), unless told otherwise.
DEFAULT_BUDGET_CHARS = 24000
#: The share of the budget the dialect may take.
DIALECT_SHARE = 0.4
#: How many views are described column by column (one engine call each).
MAX_VIEWS_DESCRIBED = 20
MIN_EXAMPLES = 2
MAX_EXAMPLES = 4
#: The card's sections a drafting model is given, in this order, after the error table.
DIALECT_SECTIONS = (
    "13-aggregation",
    "why-an-unwindowed-group-by-is-refused",
    "when-a-window-emits",
    "14-joins--what-runs",
    "31-a-query-over-another-querys-answer",
    "17-what-to-do-when-something-here-is-refused",
)

_WORD = re.compile(r"[a-z0-9]+")
_STOP = frozenset(
    "a an and are as at be by each every for from has have i in into is it its me my of on "
    "or our per show that the their them there these this those to under we what when where "
    "which who with would you your all any how many much".split()
)


def words(text: str) -> "list[str]":
    """Lower-case words, ``snake_case`` split, stop words out, a plural ``s`` dropped."""
    out = []
    for word in _WORD.findall(text.lower().replace("_", " ")):
        if word in _STOP:
            continue
        if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
            word = word[:-1]
        out.append(word)
    return out


def _similarity(query: "set[str]", other: "Mapping[str, float]") -> float:
    """Weighted overlap, normalised by both sizes so a long document does not win by length."""
    if not query or not other:
        return 0.0
    shared = sum(weight for word, weight in other.items() if word in query)
    return shared / math.sqrt(len(query) * max(1.0, sum(other.values())))


# ---------------------------------------------------------------------------------- examples


@dataclasses.dataclass(frozen=True)
class Example:
    """One continuous query of one case study: a description and its reference SQL."""

    id: str
    study: str
    view: str
    title: str
    description: str
    sql: str
    keys: "tuple[str, ...]"
    options: Mapping[str, Optional[str]]
    streams: "tuple[str, ...]"
    file: str = ""

    @classmethod
    def of(cls, document: Mapping[str, Any]) -> "Example":
        return cls(
            id=str(document["id"]),
            study=str(document.get("study", "")),
            view=str(document.get("view", "")),
            title=str(document.get("title", "")),
            description=str(document.get("description", "")),
            sql=str(document.get("sql", "")),
            keys=tuple(str(k) for k in document.get("keys") or []),
            options=dict(document.get("options") or {}),
            streams=tuple(str(s) for s in document.get("streams") or []),
            file=str(document.get("file", "")),
        )

    def weights(self) -> dict[str, float]:
        found: dict[str, float] = {}
        for word in words(f"{self.title} {self.description} {self.view}"):
            found[word] = 1.0
        for word in words(self.sql):
            found.setdefault(word, 0.5)
        return found

    def render(self) -> str:
        options = ", ".join(f"{k}={v}" for k, v in sorted(self.options.items()) if v)
        lines = [f"### {self.title or self.view} ({self.id})",
                 f"Description: {self.description}",
                 f"Key: ({', '.join(self.keys)})" + (f"; options: {options}" if options else ""),
                 "```sql", self.sql.strip(), "```"]
        return "\n".join(lines)


@functools.lru_cache(maxsize=1)
def _examples_document() -> "Mapping[str, Any]":
    node = resources.files("pravaha.assist").joinpath("resources").joinpath("examples.json")
    return dict(json.loads(node.read_text(encoding="utf-8")))


def load_examples() -> "list[Example]":
    """The packaged worked examples, in id order."""
    return [Example.of(e) for e in _examples_document().get("examples", [])]


def choose_examples(
    description: str,
    examples: Sequence[Example],
    *,
    readable: Iterable[str] = (),
    most: int = MAX_EXAMPLES,
) -> "list[tuple[Example, float]]":
    """The ``most`` examples most like ``description``, best first; ties by id. An example over
    a stream the caller can read here scores higher -- it is the same data."""
    query = set(words(description))
    here = {name.lower() for name in readable}
    scored = []
    for example in examples:
        score = _similarity(query, example.weights())
        score += 0.25 * sum(1 for s in example.streams if s.lower() in here)
        scored.append((example, round(score, 6)))
    scored.sort(key=lambda pair: (-pair[1], pair[0].id))
    return scored[:most]


# ---------------------------------------------------------------------------------- the context


@dataclasses.dataclass(frozen=True)
class DraftContext:
    """What the model is told, and what was left out of it."""

    streams: "list[dict[str, Any]]"
    views: "list[dict[str, Any]]"
    sinks: "list[dict[str, Any]]"
    examples: "list[Example]"
    #: Names left out for the budget: ``{"streams": [...], "views": [...], "sinks": [...],
    #: "examples": [...]}``, least relevant last.
    omitted: "dict[str, list[str]]"
    #: Every stream and view the caller may read, left out or not.
    readable: "frozenset[str]"
    #: Every sink the caller may see, left out or not, by name: what ``/api/v1/sinks`` says.
    sink_info: "Mapping[str, Mapping[str, Any]]"
    #: Every registered query the caller may see, left out or not: ``name``, ``sql``,
    #: ``fingerprint``, ``keyColumns``, ``retention``, ``state``.
    running: "list[dict[str, Any]]"
    #: ``{"allowed": bool, "reason": str|None}`` from ``/me/permissions``, or ``None``.
    may_register: Optional[Mapping[str, Any]]
    principal: Optional[str]
    catalogue_text: str
    dialect_text: str
    examples_text: str
    card_version: str
    notes: "list[str]" = dataclasses.field(default_factory=list)

    @property
    def chars(self) -> int:
        return len(self.catalogue_text) + len(self.dialect_text) + len(self.examples_text)

    def summary(self) -> dict[str, Any]:
        return {
            "principal": self.principal,
            "mayRegister": dict(self.may_register) if self.may_register is not None else None,
            "streams": [s["name"] for s in self.streams],
            "views": [v["name"] for v in self.views],
            "sinks": [s["name"] for s in self.sinks],
            "examples": [e.id for e in self.examples],
            "omitted": {k: list(v) for k, v in self.omitted.items() if v},
            "chars": self.chars,
            "dialectCard": self.card_version,
            "notes": list(self.notes),
        }


def _field_line(fields: Any) -> str:
    parts = []
    for field in fields or []:
        if isinstance(field, Mapping) and field.get("name"):
            kind = str(field.get("type") or "?")
            if field.get("nullable"):
                kind += " NULL"
            parts.append(f"{field['name']} {kind}")
    return ", ".join(parts)


def _key_line(keys: Any) -> str:
    names = []
    for key in keys or []:
        if isinstance(key, Mapping):
            names.append(str(key.get("name")))
        else:
            names.append(str(key))
    return ", ".join(names)


def render_stream(stream: Mapping[str, Any]) -> str:
    head = f"- stream {stream['name']}"
    facts = []
    if stream.get("eventTime"):
        facts.append(f"event time: {stream['eventTime']}")
    if stream.get("outOfOrderness"):
        facts.append(f"out-of-orderness: {stream['outOfOrderness']}")
    if stream.get("allowedLateness"):
        facts.append(f"allowed lateness: {stream['allowedLateness']}")
    if not stream.get("eventTime"):
        facts.append("no event-time column: no window over it can close")
    return head + ("  (" + "; ".join(facts) + ")" if facts else "") + \
        "\n    columns: " + (_field_line(stream.get("fields")) or "(none reported)")


def render_view(view: Mapping[str, Any]) -> str:
    facts = [f"key ({_key_line(view.get('keyColumns'))})"]
    if view.get("retention"):
        facts.append(f"retention {view['retention']}")
    line = f"- view {view['name']}  ({'; '.join(facts)})"
    columns = _field_line(view.get("schema"))
    return line + "\n    columns: " + (columns or "(not described: least relevant)")


def render_sink(sink: Mapping[str, Any]) -> str:
    facts = []
    if sink.get("guarantee"):
        facts.append(f"guarantee {sink['guarantee']}")
    facts.append("accepts retractions" if sink.get("acceptsRetractions")
                 else "append only: a query that revises its answer may not write to it")
    if sink.get("emitModes"):
        facts.append("emit modes " + ", ".join(str(m) for m in sink["emitModes"]))
    if sink.get("keyColumns"):
        facts.append("keyed by (" + ", ".join(str(k) for k in sink["keyColumns"]) + ")")
    line = f"- sink {sink['name']}  ({'; '.join(facts)})"
    if sink.get("fields"):
        line += "\n    columns: " + _field_line(sink.get("fields"))
    return line


def render_dialect(card: DialectCard, limit: int) -> str:
    """The guide's error table, then its sections on aggregation, joins and refusals, cut to
    ``limit`` characters: whole sections are left out first, then the table's last lines."""
    table = "Refusal codes, as the guide's error table gives them:"
    for code, means in card.table():
        line = f"\n- {code}: {means}"
        if len(table) + len(line) > limit:
            table += "\n- (the rest of the table left out for size)"
            break
        table += line
    parts = [table]
    used = len(table)
    for anchor in DIALECT_SECTIONS:
        excerpt = card.section(anchor)
        if excerpt is None:
            continue
        text = f"#### {excerpt.title}\n\n{excerpt.text.strip()}"
        if used + len(text) + 2 > limit:
            continue
        parts.append(text)
        used += len(text) + 2
    rendered = "\n\n".join(parts)
    return rendered


def _names(listing: Any, key: str) -> "Optional[set[str]]":
    if not isinstance(listing, Mapping) or not isinstance(listing.get(key), list):
        return None
    return {str(item.get("name")) for item in listing[key]
            if isinstance(item, Mapping) and item.get("name")}


class ContextBuilder:
    """Builds a :class:`DraftContext` for one description from one engine."""

    def __init__(
        self,
        api: "EngineApi",
        *,
        examples: Optional[Sequence[Example]] = None,
        card: Optional[DialectCard] = None,
        budget_chars: int = DEFAULT_BUDGET_CHARS,
        max_views_described: int = MAX_VIEWS_DESCRIBED,
    ) -> None:
        if budget_chars < 2000:
            raise ValueError("a context budget under 2000 characters leaves no room for a catalogue")
        self.api = api
        self._examples = examples
        self._card = card
        self.budget_chars = budget_chars
        self.max_views_described = max_views_described

    @property
    def examples(self) -> Sequence[Example]:
        if self._examples is None:
            self._examples = load_examples()
        return self._examples

    @property
    def card(self) -> DialectCard:
        if self._card is None:
            self._card = load_card()
        return self._card

    # ------------------------------------------------------------------ what the engine says

    def _permissions(self, notes: "list[str]") -> "Optional[dict[str, Any]]":
        from pravaha.rest import ApiError

        try:
            return self.api.permissions()
        except ApiError as exc:
            if exc.status == 0:
                raise
            notes.append(f"the engine did not say what this principal may do ({exc.status}); the "
                         f"catalogue is what its listings answered")
            return None

    def catalogue(self) -> "tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]], Optional[dict[str, Any]], list[str]]":  # noqa: E501
        """``(streams, views, sinks, permissions, notes)``, each filtered to what the caller may
        read, in name order. Views are not yet described column by column."""
        from pravaha.rest import ApiError

        notes: list[str] = []
        permissions = self._permissions(notes)
        allowed_streams = _names(permissions, "streams")
        allowed_views = _names(permissions, "views")
        streams = [dict(s) for s in self.api.streams() if isinstance(s, Mapping) and s.get("name")]
        if allowed_streams is not None:
            streams = [s for s in streams if str(s["name"]) in allowed_streams]
        try:
            queries = [dict(q) for q in self.api.describe_queries()
                       if isinstance(q, Mapping) and q.get("name")]
        except ApiError as exc:
            if exc.status == 0:
                raise
            queries = []
            notes.append(f"the engine did not list registered queries ({exc.status})")
        if allowed_views is not None:
            queries = [q for q in queries if str(q["name"]) in allowed_views]
        views = [{"name": str(q["name"]), "keyColumns": q.get("keyColumns"),
                  "retention": q.get("retention"), "sql": q.get("sql"),
                  "fingerprint": q.get("fingerprint"), "state": q.get("state")} for q in queries]
        try:
            sinks = [dict(s) for s in self.api.sinks() if isinstance(s, Mapping) and s.get("name")]
        except ApiError as exc:
            if exc.status == 0:
                raise
            sinks = []
            notes.append(f"the engine did not list sinks ({exc.status})")
        order = lambda item: str(item["name"])  # noqa: E731
        return (sorted(streams, key=order), sorted(views, key=order), sorted(sinks, key=order),
                permissions, notes)

    # ------------------------------------------------------------------ the context

    def build(self, description: str) -> DraftContext:
        from pravaha.rest import ApiError

        streams, views, sinks, permissions, notes = self.catalogue()
        query = set(words(description))
        mentioned = {w.lower() for w in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", description)}

        def relevance(name: str, columns: Any) -> float:
            score = _similarity(query, {w: 1.0 for w in words(name)}) * 2
            score += _similarity(query, {w: 0.5 for w in words(_field_line(columns))})
            if name.lower() in mentioned:
                score += 10.0
            return round(score, 6)

        # Describe the most relevant views column by column (one call each), in a stable order.
        ranked_views = sorted(views, key=lambda v: (-relevance(v["name"], None), v["name"]))
        for view in ranked_views[: self.max_views_described]:
            try:
                described = self.api.describe_view(view["name"])
            except ApiError as exc:
                if exc.status == 0:
                    raise
                view["hidden"] = True   # refused now: named nowhere
                continue
            view["schema"] = described.get("schema")
            view["keyColumns"] = described.get("keyColumns") or view.get("keyColumns")
            view["retention"] = described.get("retention") or view.get("retention")
        views = [v for v in views if not v.get("hidden")]

        readable = frozenset([str(s["name"]) for s in streams] + [str(v["name"]) for v in views])
        dialect = render_dialect(self.card, int(self.budget_chars * DIALECT_SHARE))
        room = self.budget_chars - len(dialect)

        chosen = choose_examples(description, self.examples, readable=readable)
        example_texts = [(example, example.render()) for example, _ in chosen]
        reserved = sum(len(t) for _, t in example_texts[:MIN_EXAMPLES])
        room -= reserved

        items: list[tuple[float, str, str, str]] = []   # (relevance, kind, name, text)
        for stream in streams:
            items.append((relevance(stream["name"], stream.get("fields")), "streams",
                          str(stream["name"]), render_stream(stream)))
        for view in views:
            items.append((relevance(view["name"], view.get("schema")), "views", str(view["name"]),
                          render_view(view)))
        for sink in sinks:
            items.append((relevance(sink["name"], sink.get("fields")), "sinks", str(sink["name"]),
                          render_sink(sink)))
        items.sort(key=lambda item: (-item[0], item[1], item[2]))
        kept: dict[str, list[tuple[str, str]]] = {"streams": [], "views": [], "sinks": []}
        omitted: dict[str, list[str]] = {"streams": [], "views": [], "sinks": [], "examples": []}
        for _score, kind, name, text in items:
            if len(text) + 1 <= room:
                kept[kind].append((name, text))
                room -= len(text) + 1
            else:
                omitted[kind].append(name)

        examples: list[Example] = []
        for position, (example, text) in enumerate(example_texts):
            if position < MIN_EXAMPLES:
                examples.append(example)
            elif len(text) <= room:
                examples.append(example)
                room -= len(text)
            else:
                omitted["examples"].append(example.id)

        kept_names = {kind: {n for n, _ in pairs} for kind, pairs in kept.items()}
        catalogue = self._render_catalogue(kept, omitted, permissions)
        return DraftContext(
            streams=[s for s in streams if s["name"] in kept_names["streams"]],
            views=[v for v in views if v["name"] in kept_names["views"]],
            sinks=[s for s in sinks if s["name"] in kept_names["sinks"]],
            examples=examples,
            omitted=omitted,
            readable=readable,
            sink_info={str(s["name"]): s for s in sinks},
            running=[{k: v.get(k) for k in ("name", "sql", "fingerprint", "keyColumns",
                                             "retention", "state")} for v in views],
            may_register=(permissions or {}).get("register") if permissions else None,
            principal=str(permissions.get("principal")) if permissions and
            permissions.get("principal") else None,
            catalogue_text=catalogue,
            dialect_text=dialect,
            examples_text="\n\n".join(e.render() for e in examples) or "(none)",
            card_version=self.card.version,
            notes=notes,
        )

    @staticmethod
    def _render_catalogue(
        kept: "dict[str, list[tuple[str, str]]]",
        omitted: "dict[str, list[str]]",
        permissions: "Optional[Mapping[str, Any]]",
    ) -> str:
        sections = []
        titles = {"streams": "Streams", "views": "Views (registered queries; a continuous query "
                                                 "may read one, ADR-056)",
                  "sinks": "Sinks a registration may write to"}
        for kind in ("streams", "views", "sinks"):
            entries = sorted(kept[kind])
            body = "\n".join(text for _, text in entries) if entries else "(none)"
            sections.append(f"{titles[kind]}:\n{body}")
        left_out = [f"{kind}: {', '.join(names)}" for kind, names in omitted.items()
                    if names and kind != "examples"]
        if left_out:
            sections.append("Left out for size, least relevant to the description (they exist and "
                            "may be read; ask if you need one): " + "; ".join(left_out))
        register = (permissions or {}).get("register") if permissions else None
        if isinstance(register, Mapping) and register.get("allowed") is False:
            sections.append("This person may not register queries here"
                            + (f": {register.get('reason')}" if register.get("reason") else "")
                            + ". Draft anyway; they may hand it to someone who can.")
        return "\n\n".join(sections)


__all__ = [
    "ContextBuilder",
    "DEFAULT_BUDGET_CHARS",
    "DraftContext",
    "Example",
    "choose_examples",
    "load_examples",
    "render_dialect",
    "words",
]
