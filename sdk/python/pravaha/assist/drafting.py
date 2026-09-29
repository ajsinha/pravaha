"""Drafting a continuous query from a description, with the engine as the judge (ADR-058 §2).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

::

    describe -> context -> draft -> engine validate/explain -> repair (<= 3) -> present
                                                                               -> confirm -> register

* The **context** is built from the engine under the caller's credentials
  (:mod:`pravaha.assist.context`).
* The **draft** is the model's answer to a fixed JSON Schema (``draft_query@v1``): ``name``,
  ``sql``, ``keys``, ``options`` (retention, index, sink, lane), ``explanation``,
  ``assumptions``, ``questions``, ``confidence``. When it has **questions**, they are returned
  and the engine is not asked anything.
* The **judge** is the engine: ``POST /api/v1/queries/validate``, then ``/explain``. Before a
  draft is called accepted the assistant also checks what the engine only checks at
  registration and the API cannot be asked -- that the key and the index name columns the
  ``SELECT`` produces, that the sink is one the caller may see, that the retention is a duration
  the engine reads -- and says, on the verdict, that it and not the engine made that check.
* A **refusal** goes back to the model with the ``PRV`` code, the engine's sentence and the
  dialect card's sections about the code, for up to three repair turns. A repair must keep the
  question: **it must read the same inputs as the first draft**; one that does not is refused
  by the assistant and never sent to the engine. A draft still refused after the last turn is
  presented as refused, in the engine's words.
* **Registration stays human**: :meth:`Assistant.register` needs ``confirmed=True`` and a draft the
  engine accepted, and goes through the ordinary :class:`pravaha.client.Client` call under the
  caller's own credentials. The model has no tool; nothing it says is executed.

**The same computation as a running query.** ``/explain`` answers, for an accepted draft's keys,
retention and sink, the **fingerprint** a registration would get for the caller (EXPLAINFP-1) --
the same short value ``GET /api/v1/queries`` lists for a registered query, computed by the
registration's own code: plan, the caller's row filters, keys, retention and tenant (ADR-025). A
running query with that fingerprint is the same computation, labelled ``match: "fingerprint"``. An
engine older than that answers no fingerprint, and the assistant falls back to what the API shows:
the engine's own physical plan for the draft against its plan for each running query that reads
the same inputs, whitespace-normalised, and the keys and retention -- labelled ``match: "plan"``,
evidence rather than identity; registration then confirms it. Guarantees are what ``GET
/api/v1/sinks`` says of the chosen sink.
"""

from __future__ import annotations

import dataclasses
import json
import re
import time
from typing import TYPE_CHECKING, Any, Mapping, Optional, Sequence

from pravaha.assist.context import ContextBuilder, DraftContext
from pravaha.assist.errors import AssistConfigError, RegistrationRefused
from pravaha.assist.prompts import DialectCard, load_prompt
from pravaha.assist.provider import ChatRequest, Message
from pravaha.assist.router import ModelRouter, RoutedResponse

if TYPE_CHECKING:
    from pravaha.api import EngineApi

#: The profile drafting asks for (ADR-058: "writing SQL: the strongest model available").
DRAFT_PROFILE = "draft"
#: Repair turns after the first draft, at most (ADR-058 §2).
MAX_REPAIRS = 3
#: The most a draft's answer may use: the JSON object is a few hundred tokens.
DRAFT_ANSWER_TOKENS = 1500
#: The guide's sections about a refusal, given on a repair turn, at most.
_EXCERPT_CHARS = 4000

_CREATE = re.compile(r"^\s*CREATE\s+(OR\s+REPLACE\s+)?CONTINUOUS\s+QUERY\b", re.IGNORECASE)
_BODY = re.compile(r"^\s*AS\s*$|\bAS\s+(?=SELECT\b)", re.IGNORECASE | re.MULTILINE)
_RELATION = re.compile(
    r"\b(?:FROM|JOIN|TABLE)\s+(\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_]*\b)(?!\s*\()",
    re.IGNORECASE,
)
_NOT_RELATIONS = frozenset({"table", "lateral", "unnest", "select", "stream"})
#: Functions whose arguments say FROM without naming a relation: EXTRACT(HOUR FROM ts).
_FROM_FUNCTIONS = frozenset({"extract", "substring", "trim", "overlay", "position"})
_DURATION = re.compile(r"^(\d+)\s*([smhdw])$", re.IGNORECASE)
_ISO = re.compile(r"^P(?!$)(\d+W|(\d+D)?(T(?=\d)(\d+H)?(\d+M)?(\d+(\.\d+)?S)?)?)$", re.IGNORECASE)


def select_of(sql: str) -> str:
    """The ``SELECT`` of a ``CREATE CONTINUOUS QUERY`` statement; other SQL unchanged."""
    if not _CREATE.match(sql or ""):
        return sql
    match = _BODY.search(sql)
    return sql[match.end():].strip() if match else sql


def relations(sql: str) -> "tuple[str, ...]":
    """The streams, views and tables ``sql`` names after ``FROM``, ``JOIN`` or ``TABLE``, lower
    case, sorted -- what the query reads. String literals and comments are ignored."""
    text = re.sub(r"--[^\n]*|/\*.*?\*/", " ", sql or "", flags=re.DOTALL)
    text = re.sub(r"'(?:[^']|'')*'", "''", text)
    # For each position, the word before the innermost open parenthesis around it.
    opener: list[str] = []
    inside: list[str] = []
    word = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*$")
    for i, char in enumerate(text):
        if char == "(":
            before = word.search(text[max(0, i - 40):i])
            opener.append(before.group(1).lower() if before else "")
        elif char == ")" and opener:
            opener.pop()
        inside.append(opener[-1] if opener else "")
    found = set()
    for match in _RELATION.finditer(text):
        if inside[match.start()] in _FROM_FUNCTIONS:
            continue
        name = match.group(1)
        name = name[1:-1].replace('""', '"') if name.startswith('"') else name.lower()
        if name.lower() not in _NOT_RELATIONS:
            found.add(name)
    return tuple(sorted(found))


def normalise_plan(plan: Optional[str]) -> str:
    """A plan's text with each line's whitespace collapsed; indentation (the tree) kept."""
    lines = []
    for line in (plan or "").splitlines():
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        lines.append(" " * indent + " ".join(line.split()))
    return "\n".join(lines)


def normalise_retention(value: Optional[str]) -> "tuple[Optional[str], Optional[str]]":
    """``(iso_or_forever, problem)``: ``24h`` is ``PT24H``, ``7d`` is ``P7D``."""
    if value is None or not str(value).strip():
        return None, None
    text = str(value).strip().strip("'\"")
    if text.lower() == "forever":
        return "forever", None
    short = _DURATION.match(text)
    if short:
        amount, unit = short.group(1), short.group(2).upper()
        if unit in "DW":
            return (f"P{amount}{unit}", None)
        return (f"PT{amount}{unit}", None)
    if _ISO.match(text):
        return text.upper(), None
    return None, (f"retention {value!r} is not a duration the engine reads: write an ISO-8601 "
                  f"duration such as PT24H or P7D, or 'forever'")


def _column(name: str, columns: Sequence[str]) -> Optional[str]:
    """``name`` as a column of ``columns``: exactly, else the one differing only in case."""
    if name in columns:
        return name
    folded = [c for c in columns if c.lower() == name.lower()]
    return folded[0] if len(folded) == 1 else None


def _quote(name: str) -> str:
    return name if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) else \
        '"' + name.replace('"', '""') + '"'


# ---------------------------------------------------------------------------------- results


@dataclasses.dataclass(frozen=True)
class Verdict:
    """Who judged a draft and what they said. ``by`` is ``engine`` (validate/explain),
    ``assistant`` (a check the API cannot be asked, made against what the engine answered), or
    ``none`` (not judged: the model asked questions instead)."""

    accepted: Optional[bool]
    by: str
    code: Optional[str] = None
    message: Optional[str] = None
    help_url: Optional[str] = None
    diagnostics: "tuple[Mapping[str, Any], ...]" = ()

    @classmethod
    def not_judged(cls) -> "Verdict":
        return cls(None, "none")

    def to_dict(self) -> dict[str, Any]:
        return {
            "accepted": self.accepted,
            "by": self.by,
            "code": self.code,
            "message": self.message,
            "helpUrl": self.help_url,
            "diagnostics": [dict(d) for d in self.diagnostics],
        }

    def words(self) -> str:
        if self.accepted:
            return "accepted by the engine"
        if self.accepted is None:
            return "not judged"
        who = "the engine" if self.by == "engine" else "the assistant"
        return f"refused by {who}: {self.code + '  ' if self.code else ''}{self.message or ''}"


@dataclasses.dataclass(frozen=True)
class Turn:
    """One answer from the model and what was made of it."""

    number: int
    #: ``draft`` for the first, ``repair`` for each after.
    kind: str
    name: str
    sql: str
    keys: "tuple[str, ...]"
    options: Mapping[str, Optional[str]]
    questions: "tuple[str, ...]"
    verdict: Verdict
    #: The relations the SQL reads.
    inputs: "tuple[str, ...]"
    #: ``False`` when a repair read different inputs from the first draft.
    intent_kept: bool
    answered_by: Mapping[str, Any]
    tokens: int
    latency_ms: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "number": self.number,
            "kind": self.kind,
            "name": self.name,
            "sql": self.sql,
            "keys": list(self.keys),
            "options": dict(self.options),
            "questions": list(self.questions),
            "verdict": self.verdict.to_dict(),
            "inputs": list(self.inputs),
            "intentKept": self.intent_kept,
            "answeredBy": dict(self.answered_by),
            "tokens": self.tokens,
            "latencyMs": self.latency_ms,
        }


@dataclasses.dataclass(frozen=True)
class Draft:
    """A continuous query drafted from a description, and the engine's verdict on it."""

    description: str
    #: ``accepted``, ``refused`` or ``questions``.
    status: str
    name: str
    sql: str
    keys: "tuple[str, ...]"
    options: Mapping[str, Optional[str]]
    explanation: str
    assumptions: "tuple[str, ...]"
    questions: "tuple[str, ...]"
    confidence: Optional[float]
    verdict: Verdict
    #: The engine's own physical plan, when it accepted the SQL.
    plan: Optional[str]
    #: The ``SELECT``'s output columns as the engine planned them (``name``, ``type``, ...).
    output_fields: "tuple[Mapping[str, Any], ...]"
    #: What the chosen sink promises, from ``GET /api/v1/sinks``.
    guarantees: Mapping[str, Any]
    #: Running queries that appear to be the same computation (see the module's note).
    same_as: "tuple[Mapping[str, Any], ...]"
    turns: "tuple[Turn, ...]"
    #: The model that gave the final answer.
    answered_by: Mapping[str, Any]
    #: Tokens over every turn.
    tokens: int
    latency_ms: float
    inputs: "tuple[str, ...]"
    context: Mapping[str, Any]
    prompt: str
    #: The fingerprint a registration would get, from the engine's ``/explain`` (EXPLAINFP-1);
    #: ``None`` when not accepted, or from an engine that does not answer one.
    fingerprint: Optional[str] = None

    @property
    def accepted(self) -> bool:
        return self.status == "accepted" and bool(self.verdict.accepted) and \
            self.verdict.by == "engine"

    @property
    def repairs(self) -> int:
        return sum(1 for t in self.turns if t.kind == "repair")

    def columns(self) -> "list[str]":
        return [str(f.get("name")) for f in self.output_fields if f.get("name") is not None]

    def key_ordinals(self) -> "list[int]":
        """The key as output-column ordinals, as ``Client.register`` takes it."""
        columns = self.columns()
        ordinals = []
        for key in self.keys:
            found = _column(key, columns)
            if found is None:
                raise RegistrationRefused(f"key column {key!r} is not an output column of the "
                                          f"draft ({', '.join(columns)})")
            ordinals.append(columns.index(found))
        return ordinals

    def statement(self, name: Optional[str] = None) -> str:
        """The draft as one ``CREATE CONTINUOUS QUERY`` statement (docs/CONTINUOUS_QUERIES.md
        §10.1): what a person would paste into ``pravaha query`` or the workbench."""
        lines = [f"CREATE CONTINUOUS QUERY {_quote(name or self.name)}",
                 f"  KEYED BY ({', '.join(_quote(k) for k in self.keys)})"]
        if self.options.get("index"):
            lines.append(f"  INDEX ({_quote(str(self.options['index']))})")
        if self.options.get("sink"):
            lines.append(f"  WRITING TO {_quote(str(self.options['sink']))}")
        retention = self.options.get("retention")
        if retention:
            lines.append("  RETAIN FOREVER" if str(retention).lower() == "forever"
                         else f"  RETAIN FOR {retention}")
        if self.options.get("lane"):
            lines.append(f"  WITH (lane = '{self.options['lane']}')")
        lines.append("AS")
        lines.append(self.sql.strip().rstrip(";"))
        return "\n".join(lines)

    def to_dict(self) -> dict[str, Any]:
        return {
            "task": "draft",
            "description": self.description,
            "status": self.status,
            "name": self.name,
            "sql": self.sql,
            "statement": self.statement() if self.sql and self.keys else None,
            "keys": list(self.keys),
            "options": dict(self.options),
            "explanation": self.explanation,
            "assumptions": list(self.assumptions),
            "questions": list(self.questions),
            "confidence": self.confidence,
            "verdict": self.verdict.to_dict(),
            "enginePlan": {"level": "physical", "plan": self.plan} if self.plan else None,
            "fingerprint": self.fingerprint,
            "outputFields": [dict(f) for f in self.output_fields],
            "guarantees": dict(self.guarantees),
            "sameAs": [dict(s) for s in self.same_as],
            "inputs": list(self.inputs),
            "turns": [t.to_dict() for t in self.turns],
            "repairs": self.repairs,
            "answeredBy": dict(self.answered_by),
            "tokens": self.tokens,
            "latencyMs": self.latency_ms,
            "context": dict(self.context),
            "prompt": self.prompt,
        }


@dataclasses.dataclass(frozen=True)
class _Proposal:
    name: str
    sql: str
    keys: "tuple[str, ...]"
    options: dict[str, Optional[str]]
    explanation: str
    assumptions: "tuple[str, ...]"
    questions: "tuple[str, ...]"
    confidence: Optional[float]

    @classmethod
    def of(cls, parsed: Any, name: Optional[str]) -> "_Proposal":
        parsed = parsed if isinstance(parsed, Mapping) else {}
        options = parsed.get("options") if isinstance(parsed.get("options"), Mapping) else {}
        cleaned: dict[str, Optional[str]] = {}
        for key in ("retention", "index", "sink", "lane"):
            value = options.get(key)
            cleaned[key] = str(value).strip() if isinstance(value, str) and value.strip() else None
        confidence = parsed.get("confidence")
        sql = str(parsed.get("sql") or "").strip().rstrip(";").strip()
        return cls(
            name=(name or str(parsed.get("name") or "").strip() or "drafted_query"),
            sql=select_of(sql),
            keys=tuple(str(k).strip() for k in parsed.get("keys") or [] if str(k).strip()),
            options=cleaned,
            explanation=str(parsed.get("explanation") or ""),
            assumptions=tuple(str(a) for a in parsed.get("assumptions") or []),
            questions=tuple(str(q) for q in parsed.get("questions") or [] if str(q).strip()),
            confidence=float(confidence) if isinstance(confidence, (int, float)) else None,
        )


def _usage_tokens(answer: RoutedResponse) -> int:
    return int(answer.usage.input_tokens) + int(answer.usage.output_tokens)


# ---------------------------------------------------------------------------------- the task


class Drafter:
    """One description through context, draft, judge and repair. :meth:`Assistant.draft` is
    the way in; this holds the steps so each can be read, and tested, on its own."""

    def __init__(
        self,
        router: ModelRouter,
        api: "EngineApi",
        card: DialectCard,
        *,
        context_builder: Optional[ContextBuilder] = None,
    ) -> None:
        self.router = router
        self.api = api
        self.card = card
        self.context_builder = context_builder or ContextBuilder(api, card=card)

    # ------------------------------------------------------------------ the judge

    def judge(
        self, proposal: _Proposal, context: DraftContext
    ) -> "tuple[Verdict, list[dict[str, Any]]]":
        """The engine's verdict on the ``SELECT``, then the registration checks the API cannot
        be asked, made against the engine's own output columns and sink listing."""
        verdict = self.api.validate(proposal.sql)
        fields = [dict(f) for f in verdict.get("outputFields") or [] if isinstance(f, Mapping)]
        if not verdict.get("valid"):
            diagnostics = tuple(dict(d) for d in verdict.get("diagnostics") or []
                                if isinstance(d, Mapping))
            first = diagnostics[0] if diagnostics else {}
            return Verdict(False, "engine", str(first.get("code") or "") or None,
                           str(first.get("message") or "the engine refused it without a message"),
                           first.get("helpUrl"), diagnostics), fields
        columns = [str(f.get("name")) for f in fields]
        listed = ", ".join(columns)

        def refused(code: Optional[str], message: str) -> "tuple[Verdict, list[dict[str, Any]]]":
            return Verdict(False, "assistant", code, message), fields

        if not proposal.keys:
            return refused("PRV-2071", "the draft names no key: a view needs one -- the output "
                                       f"columns that make a row distinct (the SELECT produces "
                                       f"{listed})")
        seen: set[str] = set()
        for key in proposal.keys:
            found = _column(key, columns)
            if found is None:
                return refused("PRV-2071", f"the key names {key!r}, which the SELECT does not "
                                           f"produce (it produces {listed}); the engine refuses "
                                           f"this at registration with PRV-2071")
            if found in seen:
                return refused("PRV-2071", f"the key names {found!r} twice; the engine refuses "
                                           f"this at registration with PRV-2071")
            seen.add(found)
        index = proposal.options.get("index")
        if index and _column(index, columns) is None:
            return refused("PRV-2074", f"the index names {index!r}, which the SELECT does not "
                                       f"produce (it produces {listed})")
        sink = proposal.options.get("sink")
        if sink and sink not in context.sink_info:
            known = ", ".join(sorted(context.sink_info)) or "none"
            return refused(None, f"there is no sink named {sink!r} that you may see (the sinks "
                                 f"are: {known})")
        _, problem = normalise_retention(proposal.options.get("retention"))
        if problem:
            return refused(None, problem)
        lane = proposal.options.get("lane")
        if lane and lane not in ("dedicated", "shared"):
            return refused("PRV-8017", f"lane {lane!r} is not a lane: 'dedicated' or 'shared'")
        return Verdict(True, "engine", diagnostics=()), fields

    def same_as(
        self, proposal: _Proposal, plan: str, context: DraftContext,
        fingerprint: Optional[str] = None,
    ) -> "list[dict[str, Any]]":
        """Running queries that are the draft's computation: by the engine's fingerprint when
        it answered one, else by plan, keys and retention (see the module's note)."""
        from pravaha.rest import ApiError

        wanted = normalise_plan(plan)
        inputs = relations(proposal.sql)
        retention, _ = normalise_retention(proposal.options.get("retention"))
        found = []
        for query in context.running:
            theirs_fingerprint = query.get("fingerprint")
            if fingerprint and theirs_fingerprint == fingerprint:
                found.append({
                    "name": query.get("name"), "fingerprint": theirs_fingerprint,
                    "match": "fingerprint", "keysEqual": True, "retentionEqual": True,
                    "sameComputation": True,
                    "reuse": (f"read {query.get('name')} instead of registering a copy, or "
                              f"register under your own name: the engine's fingerprints are "
                              f"equal, so it will share one computation (ADR-025)"),
                })
                continue
            sql = select_of(str(query.get("sql") or ""))
            if not sql or relations(sql) != inputs:
                continue
            try:
                theirs = self.api.explain(sql, "physical").get("plan")
            except ApiError as exc:
                if exc.status == 0:
                    raise
                continue
            if normalise_plan(str(theirs or "")) != wanted:
                continue
            keys = [str(k.get("name")) if isinstance(k, Mapping) else str(k)
                    for k in query.get("keyColumns") or []]
            keys_equal = [k.lower() for k in keys] == [k.lower() for k in proposal.keys]
            theirs_retention = str(query.get("retention") or "forever")
            retention_equal = (retention or "forever").lower() == theirs_retention.lower()
            # With the engine's fingerprint in hand, a plan match whose fingerprint differs is not
            # the same computation whatever the keys say: the row filters or the tenant differ.
            same = keys_equal and retention_equal and not fingerprint
            if same:
                reuse = (f"read {query.get('name')} instead of registering a copy, or register "
                         f"under your own name: the engine will share one computation (ADR-025)")
            elif keys_equal and retention_equal:
                reuse = (f"{query.get('name')} has the same plan, keys and retention but another "
                         f"fingerprint: registered by you it would be a separate computation "
                         f"(your row filters or tenant differ)")
            else:
                reuse = (f"{query.get('name')} computes the same rows under a different "
                         f"{'key' if not keys_equal else 'retention'}")
            found.append({
                "name": query.get("name"),
                "fingerprint": theirs_fingerprint,
                "match": "plan",
                "keysEqual": keys_equal,
                "retentionEqual": retention_equal,
                "sameComputation": same,
                "reuse": reuse,
            })
        return found

    def guarantees(self, proposal: _Proposal, context: DraftContext) -> dict[str, Any]:
        sink = proposal.options.get("sink")
        if not sink:
            return {"sink": None, "note": "no sink: the answer is the view, read at the "
                                          "consistency each reader asks for"}
        info = context.sink_info.get(sink) or {}
        return {
            "sink": sink,
            "guarantee": info.get("guarantee"),
            "acceptsRetractions": info.get("acceptsRetractions"),
            "emitModes": list(info.get("emitModes") or []),
            "keyColumns": list(info.get("keyColumns") or []),
        }

    # ------------------------------------------------------------------ the conversation

    def _repair_text(
        self, prompt_sections: Any, verdict: Verdict, sql: str, first_inputs: "tuple[str, ...]"
    ) -> str:
        if verdict.code and self.card.knows(verdict.code):
            excerpts = self.card.excerpts(verdict.code, limit_chars=_EXCERPT_CHARS)
            rendered = "\n\n".join(f"#### {e.title}\n\n{e.text}" for e in excerpts)
        else:
            rendered = "(the guide has no section for this)"
        intent = ("The question reads " + ", ".join(first_inputs) + "; the repair must read "
                  "exactly those.\n") if first_inputs else ""
        return str(prompt_sections.render_section(
            "repair", code=verdict.code or "(no code)", message=verdict.message or "",
            sql=sql, excerpts=rendered, intent=intent))

    def draft(
        self,
        description: str,
        *,
        name: Optional[str] = None,
        profile: Optional[str] = None,
        model: Optional[str] = None,
        max_repairs: int = MAX_REPAIRS,
        context: Optional[DraftContext] = None,
    ) -> Draft:
        if not description or not description.strip():
            raise ValueError("describe the query you want")
        if not 0 <= max_repairs <= MAX_REPAIRS:
            raise ValueError(f"max_repairs is 0 to {MAX_REPAIRS}")
        description = description.strip()
        started = time.monotonic()
        context = context or self.context_builder.build(description)
        prompt = load_prompt("draft_query")
        system, user = prompt.render(
            catalogue=context.catalogue_text, dialect=context.dialect_text,
            examples=context.examples_text, description=description)
        messages = [Message("user", user)]
        turns: list[Turn] = []
        first_inputs: Optional[tuple[str, ...]] = None
        judged: Optional[tuple[_Proposal, Verdict, list[dict[str, Any]]]] = None
        last: Optional[tuple[_Proposal, Verdict, list[dict[str, Any]]]] = None
        answer: Optional[RoutedResponse] = None
        for number in range(1, max_repairs + 2):
            answer = self.router.complete(
                ChatRequest(messages=list(messages), system=system,
                            response_schema=prompt.schema, max_tokens=DRAFT_ANSWER_TOKENS,
                            metadata={"task": prompt.name, "turn": str(number)}),
                profile=profile or DRAFT_PROFILE, model=model)
            proposal = _Proposal.of(answer.parsed, name)
            kind = "draft" if number == 1 else "repair"
            tokens = _usage_tokens(answer)
            latency = float(answer.response.latency_ms or 0.0)
            inputs = relations(proposal.sql)

            def record(verdict: Verdict, kept: bool = True) -> None:
                assert answer is not None
                turns.append(Turn(number, kind, proposal.name, proposal.sql, proposal.keys,
                                  dict(proposal.options), proposal.questions, verdict, inputs,
                                  kept, answer.answered_by(), tokens, latency))

            if proposal.questions or not proposal.sql:
                record(Verdict.not_judged())
                return self._present(description, "questions", proposal, Verdict.not_judged(),
                                     [], None, context, turns, answer, prompt.id, started)
            if first_inputs is None:
                first_inputs = inputs
            if inputs != first_inputs:
                verdict = Verdict(False, "assistant", None,
                                  f"this repair reads {', '.join(inputs) or 'nothing'} where the "
                                  f"question reads {', '.join(first_inputs) or 'nothing'}: that is "
                                  f"a different question, so it was not sent to the engine")
                record(verdict, kept=False)
                last = (proposal, verdict, [])
            else:
                verdict, fields = self.judge(proposal, context)
                record(verdict)
                judged = last = (proposal, verdict, fields)
                if verdict.accepted:
                    explained = self.explain(proposal, fields)
                    plan = str(explained.get("plan") or "")
                    return self._present(description, "accepted", proposal, verdict, fields, plan,
                                         context, turns, answer, prompt.id, started,
                                         fingerprint=explained.get("fingerprint"))
            if number > max_repairs:
                break
            # The description and catalogue, the last answer, and what was wrong with it: each
            # repair turn costs the same, rather than carrying every earlier refusal with it.
            messages = [messages[0],
                        Message("assistant", answer.text or json.dumps(answer.parsed)),
                        Message("user", self._repair_text(prompt, verdict, proposal.sql,
                                                          first_inputs or ()))]
        final = judged or last
        assert final is not None and answer is not None
        proposal, verdict, fields = final
        return self._present(description, "refused", proposal, verdict, fields, None, context,
                             turns, answer, prompt.id, started)

    def _present(
        self,
        description: str,
        status: str,
        proposal: _Proposal,
        verdict: Verdict,
        fields: "list[dict[str, Any]]",
        plan: Optional[str],
        context: DraftContext,
        turns: "list[Turn]",
        answer: RoutedResponse,
        prompt: str,
        started: float,
        fingerprint: Optional[str] = None,
    ) -> Draft:
        options = dict(proposal.options)
        if options.get("retention"):
            options["retention"] = normalise_retention(options["retention"])[0] or \
                options["retention"]
        accepted = status == "accepted"
        return Draft(
            description=description,
            status=status,
            name=proposal.name,
            sql=proposal.sql,
            keys=proposal.keys,
            options=options,
            explanation=proposal.explanation,
            assumptions=proposal.assumptions,
            questions=proposal.questions,
            confidence=proposal.confidence,
            verdict=verdict,
            plan=plan,
            output_fields=tuple(fields),
            guarantees=self.guarantees(proposal, context) if accepted else {},
            same_as=tuple(self.same_as(proposal, plan or "", context, fingerprint))
            if accepted else (),
            turns=tuple(turns),
            answered_by=answer.answered_by(),
            tokens=sum(t.tokens for t in turns),
            latency_ms=round((time.monotonic() - started) * 1000.0, 1),
            inputs=relations(proposal.sql),
            context=context.summary(),
            prompt=prompt,
            fingerprint=str(fingerprint) if accepted and fingerprint else None,
        )

    def explain(self, proposal: _Proposal, fields: "list[dict[str, Any]]") -> dict[str, Any]:
        """The engine's plan for an accepted draft, with the fingerprint a registration of it
        would get: its keys as output ordinals, its retention and its sink (EXPLAINFP-1)."""
        columns = [str(f.get("name")) for f in fields]
        ordinals = [columns.index(found) for found in
                    (_column(k, columns) for k in proposal.keys) if found is not None]
        retention, _ = normalise_retention(proposal.options.get("retention"))
        return self.api.explain(proposal.sql, "physical", keys=ordinals or None,
                                retention=retention, sink=proposal.options.get("sink"))


def register(draft: Draft, client: Any, *, confirmed: bool, name: Optional[str] = None
             ) -> dict[str, Any]:
    """Registers an accepted, confirmed draft through ``client`` (a
    :class:`pravaha.client.Client`), under the credentials it carries.

    ``Client.register`` when the draft needs only a key, a sink and a retention; the draft's
    ``CREATE CONTINUOUS QUERY`` statement through ``Client.query`` when it also declares an index
    or a lane, which ``register`` cannot carry. Refused, with nothing sent, unless ``confirmed``
    and the engine accepted the draft."""
    if not confirmed:
        raise RegistrationRefused("not registered: a person must confirm the draft first "
                                  "(confirmed=True; on the command line, --register --yes)")
    if not draft.accepted:
        raise RegistrationRefused(f"not registered: the draft is {draft.status}, not accepted by "
                                  f"the engine ({draft.verdict.words()})")
    if client is None:
        raise AssistConfigError("registering needs a pravaha.client.Client (Flight), under your "
                                "own credentials")
    chosen = name or draft.name
    ordinals = draft.key_ordinals()
    retention = draft.options.get("retention")
    if draft.options.get("index") or draft.options.get("lane"):
        rows = client.query(draft.statement(chosen)).to_list()
        row = dict(rows[0]) if rows else {}
        return {"name": row.get("name", chosen), "state": row.get("state"),
                "fingerprint": row.get("fingerprint"), "sink": row.get("sink"),
                "via": "statement", "statement": draft.statement(chosen)}
    registered = client.register(chosen, draft.sql, ordinals, sink=draft.options.get("sink"),
                                 retention=retention)
    return {"name": registered.name, "state": registered.state,
            "fingerprint": registered.fingerprint, "sink": registered.sink,
            "via": "register", "keys": ordinals}


__all__ = [
    "DRAFT_PROFILE",
    "Draft",
    "Drafter",
    "MAX_REPAIRS",
    "Turn",
    "Verdict",
    "normalise_plan",
    "normalise_retention",
    "register",
    "relations",
    "select_of",
]
