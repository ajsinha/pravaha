"""``Assistant``: the tasks, grounded in the engine. Phase 1 builds the two that need no
confirmation -- explain a query, and explain a refusal.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The engine is the judge (ADR-058): what the model is told comes from the engine, not from the
model's memory, and what the model proposes goes back to the engine before anyone is told it
works.

* :meth:`Assistant.explain_query` gives the model the SQL *and the engine's own plan for it*
  (``POST /api/v1/queries/explain``), and says the plan wins where the two disagree. For a
  registered query it also gives what the engine reports about it -- keys, retention, sink.
* :meth:`Assistant.explain_refusal` gives the model the engine's own words -- the diagnostics of
  ``POST /api/v1/queries/validate`` when a statement is given, else the guide's line for the code
  -- and the sections of docs/CONTINUOUS_QUERIES.md about that code, from the packaged dialect
  card. A rewrite the model proposes is validated by the engine and reported with its verdict;
  it is never presented as working on the model's say-so.

Nothing here registers, drops or reads data: the only engine calls are explain, validate and
describe, under the caller's own credentials.
"""

from __future__ import annotations

import dataclasses
import json
import re
from typing import TYPE_CHECKING, Any, Optional

from pravaha.assist.errors import AssistConfigError
from pravaha.assist.prompts import DialectCard, Prompt, load_card, load_prompt
from pravaha.assist.provider import ChatRequest, Message
from pravaha.assist.router import ModelRouter, RoutedResponse

if TYPE_CHECKING:
    from pravaha.api import EngineApi

#: The profile both explain tasks ask for (ADR-058: "cheaper is fine").
EXPLAIN_PROFILE = "explain"
CODE = re.compile(r"^PRV-\d{4}$")
_ANSWER_TOKENS = 1500


@dataclasses.dataclass(frozen=True)
class QueryExplanation:
    """A query in plain English, beside the engine's plan it was grounded in."""

    sql: str
    summary: str
    steps: "list[str]"
    notes: "list[str]"
    plan: str
    plan_level: str
    query_name: Optional[str]
    prompt: str
    answered_by: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            "task": "explain-query",
            "queryName": self.query_name,
            "sql": self.sql,
            "summary": self.summary,
            "steps": list(self.steps),
            "notes": list(self.notes),
            "enginePlan": {"level": self.plan_level, "plan": self.plan},
            "prompt": self.prompt,
            "answeredBy": self.answered_by,
        }


@dataclasses.dataclass(frozen=True)
class RefusalExplanation:
    """What a refusal means, what caused it, what to change -- and, when the model proposed a
    rewrite, what the engine said about it."""

    code: str
    sql: Optional[str]
    meaning: str
    cause: str
    fix: str
    rewrite: Optional[str]
    #: ``{"checked": bool, "valid": bool|None, "diagnostics": [...]}``: the engine's verdict.
    rewrite_verdict: dict[str, Any]
    #: The engine's verdict on the statement given, or ``None`` when none was.
    engine: Optional[dict[str, Any]]
    card_sections: "list[str]"
    card_version: str
    prompt: str
    answered_by: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            "task": "explain-refusal",
            "code": self.code,
            "sql": self.sql,
            "meaning": self.meaning,
            "cause": self.cause,
            "fix": self.fix,
            "rewrite": self.rewrite,
            "rewriteVerdict": self.rewrite_verdict,
            "engine": self.engine,
            "dialectCard": {"version": self.card_version, "sections": list(self.card_sections)},
            "prompt": self.prompt,
            "answeredBy": self.answered_by,
        }


def _strings(value: Any) -> "list[str]":
    return [str(item) for item in value] if isinstance(value, list) else []


def _diagnostic_lines(diagnostics: Any) -> str:
    lines = []
    for item in diagnostics or []:
        if isinstance(item, dict):
            lines.append(f"{item.get('code') or ''}  {item.get('message') or ''}".strip())
    return "\n".join(lines)


class Assistant:
    """The assistant's tasks over one router and, when a task needs the engine, one
    :class:`~pravaha.api.EngineApi` acting with the caller's credentials."""

    def __init__(
        self,
        router: ModelRouter,
        api: "Optional[EngineApi]" = None,
        *,
        card: Optional[DialectCard] = None,
    ) -> None:
        self.router = router
        self.api = api
        self._card = card

    @property
    def card(self) -> DialectCard:
        if self._card is None:
            self._card = load_card()
        return self._card

    def _engine(self, why: str) -> "EngineApi":
        if self.api is None:
            raise AssistConfigError(f"{why} needs the engine; construct the Assistant with an EngineApi")
        return self.api

    def _ask(
        self,
        prompt: Prompt,
        values: dict[str, str],
        *,
        profile: Optional[str],
        model: Optional[str],
    ) -> RoutedResponse:
        system, user = prompt.render(**values)
        request = ChatRequest(
            messages=[Message("user", user)],
            system=system,
            response_schema=prompt.schema,
            max_tokens=_ANSWER_TOKENS,
            metadata={"task": prompt.name},
        )
        return self.router.complete(request, profile=profile or EXPLAIN_PROFILE, model=model)

    # ------------------------------------------------------------------ explain a query

    def explain_query(
        self,
        sql: Optional[str] = None,
        *,
        query_name: Optional[str] = None,
        level: str = "physical",
        profile: Optional[str] = None,
        model: Optional[str] = None,
    ) -> QueryExplanation:
        """``sql`` -- or the registered query ``query_name`` -- in plain English, grounded in the
        engine's plan. An engine refusal of the SQL is raised as the engine's
        :class:`~pravaha.rest.ApiError`, before any model is asked."""
        api = self._engine("explaining a query")
        facts = ""
        if query_name:
            described = api.describe_query(query_name)
            sql = sql or str(described.get("sql") or "")
            if not sql:
                raise AssistConfigError(f"the engine did not give the SQL of {query_name!r}")
            facts = json.dumps(
                {k: described.get(k) for k in ("name", "keyColumns", "retention", "sink", "reads",
                                                "sharedWith", "lane") if described.get(k) is not None},
                indent=1, sort_keys=True, default=str,
            )
        if not sql or not sql.strip():
            raise ValueError("explain_query needs SQL or a registered query's name")
        plan = str(api.explain(sql, level).get("plan") or "")
        prompt = load_prompt("explain_query")
        answer = self._ask(prompt, {"sql": sql.strip(), "level": level, "plan": plan,
                                    "facts": facts}, profile=profile, model=model)
        parsed = answer.parsed or {}
        return QueryExplanation(
            sql=sql.strip(),
            summary=str(parsed.get("summary", "")),
            steps=_strings(parsed.get("steps")),
            notes=_strings(parsed.get("notes")),
            plan=plan,
            plan_level=level,
            query_name=query_name,
            prompt=prompt.id,
            answered_by=answer.answered_by(),
        )

    # ------------------------------------------------------------------ explain a refusal

    def explain_refusal(
        self,
        code: str,
        sql: Optional[str] = None,
        *,
        profile: Optional[str] = None,
        model: Optional[str] = None,
        check_rewrite: bool = True,
    ) -> RefusalExplanation:
        """What ``code`` means and what to change. With ``sql`` the engine validates it first,
        and the model is given the engine's own diagnostics; a rewrite the model proposes is
        validated too (``check_rewrite``). Without ``sql`` no engine call is made."""
        code = code.strip().upper()
        if not CODE.match(code):
            raise ValueError(f"{code!r} is not a code: codes look like PRV-2050")
        card = self.card
        engine: Optional[dict[str, Any]] = None
        if sql and sql.strip():
            verdict = self._engine("explaining a refusal of a statement").validate(sql)
            diagnostics = list(verdict.get("diagnostics") or [])
            engine = {
                "valid": bool(verdict.get("valid")),
                "diagnostics": diagnostics,
                "codes": sorted({str(d.get("code")) for d in diagnostics if isinstance(d, dict)
                                 and d.get("code")}),
            }
            if engine["valid"]:
                words = "The engine accepts this statement: it raised no refusal for it."
            else:
                words = _diagnostic_lines(diagnostics) or "The engine refused it without a message."
                if code not in engine["codes"]:
                    words += f"\n(The engine did not raise {code} for this statement.)"
        else:
            means = card.means(code)
            words = (f"{code}: {means}" if means else
                     f"{code} (no statement was given; the guide's error-code table has no line "
                     f"for this code)")
        excerpts = card.excerpts(code)
        prompt = load_prompt("explain_refusal")
        rendered = "\n\n".join(f"### {e.title}\n\n{e.text}" for e in excerpts) or "(none)"
        answer = self._ask(
            prompt,
            {"code": code, "engine": words, "sql": (sql or "").strip(), "card": card.source,
             "excerpts": rendered},
            profile=profile,
            model=model,
        )
        parsed = answer.parsed or {}
        rewrite = parsed.get("rewrite")
        rewrite = str(rewrite).strip() if isinstance(rewrite, str) and rewrite.strip() else None
        verdict_of_rewrite: dict[str, Any] = {"checked": False, "valid": None, "diagnostics": []}
        if rewrite and check_rewrite and self.api is not None:
            from pravaha.rest import ApiError

            try:
                checked = self.api.validate(rewrite)
            except ApiError as exc:
                verdict_of_rewrite["error"] = str(exc)
            else:
                verdict_of_rewrite = {
                    "checked": True,
                    "valid": bool(checked.get("valid")),
                    "diagnostics": list(checked.get("diagnostics") or []),
                }
        return RefusalExplanation(
            code=code,
            sql=sql.strip() if sql else None,
            meaning=str(parsed.get("meaning", "")),
            cause=str(parsed.get("cause", "")),
            fix=str(parsed.get("fix", "")),
            rewrite=rewrite,
            rewrite_verdict=verdict_of_rewrite,
            engine=engine,
            card_sections=[e.anchor for e in excerpts],
            card_version=card.version,
            prompt=prompt.id,
            answered_by=answer.answered_by(),
        )


__all__ = [
    "Assistant",
    "EXPLAIN_PROFILE",
    "QueryExplanation",
    "RefusalExplanation",
]
