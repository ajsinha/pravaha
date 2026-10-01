"""The dialect card is current with docs/guides/CONTINUOUS_QUERIES.md; the prompts are versioned
package resources; the two tasks ground the model in the engine.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
from typing import Any

import pytest
from assist_support import EXPLANATION, REFUSAL, fake_config

from pravaha.assist import AssistConfig, Assistant, ModelRouter, UsageLedger, load_card, load_prompt
from pravaha.assist.prompts import prompt_versions
from pravaha.assist.providers import FakeProvider

SDK = pathlib.Path(__file__).resolve().parents[1]
GUIDE = SDK.parent.parent / "docs" / "guides" / "CONTINUOUS_QUERIES.md"
TOOL = SDK / "tools" / "build_dialect_card.py"


def _tool() -> Any:
    spec = importlib.util.spec_from_file_location("build_dialect_card", TOOL)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.skipif(not GUIDE.exists(), reason="needs the repository's docs/")
def test_the_packaged_card_is_current_with_the_guide():
    fresh = _tool().render(GUIDE.read_text(encoding="utf-8"))
    packaged = (SDK / "pravaha" / "assist" / "resources" / "dialect-card.json").read_text(
        encoding="utf-8")
    assert packaged == fresh, (
        "docs/guides/CONTINUOUS_QUERIES.md changed: run `.venv/bin/python tools/build_dialect_card.py` "
        "in sdk/python and commit the card"
    )


def test_the_generator_reads_headings_rows_ranges_and_mentions():
    guide = """# Title

## 13. Aggregation

Grouping needs a window.

### Why an unwindowed `GROUP BY` is refused

It is refused with `PRV-2050`.

```bash
# not a heading
```

## 17. What to do when something here is refused

Read the message.

## 19. Error codes

| Code | Means |
|---|---|
| `PRV-2050` | The query's state would grow without bound — §13 |
| `PRV-2060`–`PRV-2062` | Parameter binding |
"""
    card = _tool().build(guide)
    assert card["codes"]["PRV-2050"]["means"].startswith("The query's state")
    assert card["codes"]["PRV-2050"]["sections"] == [
        "13-aggregation", "why-an-unwindowed-group-by-is-refused"]
    assert card["codes"]["PRV-2061"]["means"] == "Parameter binding"
    assert card["general"] == "17-what-to-do-when-something-here-is-refused"
    assert "not a heading" in card["sections"]["why-an-unwindowed-group-by-is-refused"]["text"]


def test_the_card_explains_the_codes_that_matter():
    card = load_card()
    assert card.source == "docs/guides/CONTINUOUS_QUERIES.md" and len(card.version) == 12
    for code in ("PRV-2050", "PRV-2042", "PRV-2075", "PRV-2001"):
        assert card.knows(code) and card.means(code)
    titles = [e.title for e in card.excerpts("PRV-2050")]
    assert "13. Aggregation" in titles
    assert titles[-1].startswith("17."), "the general advice comes last"
    assert sum(len(e.text) for e in card.excerpts("PRV-2042")) <= 14000 + 10000
    assert not card.knows("PRV-9999") and card.excerpts("PRV-9999")[0].title.startswith("17.")


def test_prompts_are_versioned_resources_with_schemas():
    for name in ("explain_query", "explain_refusal"):
        assert prompt_versions(name) == [1]
        prompt = load_prompt(name)
        assert prompt.id == f"{name}@v1"
        assert prompt.schema is not None and "$comment" not in prompt.schema
        assert "Copyright" not in prompt.system + prompt.user, "header comments are not sent"
    system, user = load_prompt("explain_query").render(sql="SELECT 1", level="physical",
                                                       plan="Scan", facts="")
    assert "SELECT 1" in user and "Scan" in user and "plan wins" in system
    with pytest.raises(KeyError):
        load_prompt("explain_query").render(sql="x")
    with pytest.raises(LookupError):
        load_prompt("explain_query", 99)


# ---------------------------------------------------------------------------------- tasks


class _Engine:
    """Stands in for EngineApi: explain, validate and describe_query, recorded."""

    def __init__(self, *, valid: bool = False) -> None:
        self.calls: list[tuple[str, Any]] = []
        self.valid = valid

    def explain(self, sql: str, level: str = "physical") -> dict[str, Any]:
        self.calls.append(("explain", sql))
        return {"plan": "Aggregate[customer, window 1 min]\n  Scan[orders]"}

    def validate(self, sql: str) -> dict[str, Any]:
        self.calls.append(("validate", sql))
        if "TUMBLE" in sql or self.valid:
            return {"valid": True, "diagnostics": []}
        return {"valid": False, "diagnostics": [
            {"code": "PRV-2050", "message": "unbounded state: GROUP BY customer needs a window",
             "helpUrl": "x"}]}

    def describe_query(self, name: str) -> dict[str, Any]:
        self.calls.append(("describe", name))
        return {"name": name, "sql": "SELECT customer, COUNT(*) FROM orders GROUP BY customer",
                "keyColumns": [{"name": "customer"}], "retention": "PT24H"}


def _assistant(tmp_path, replies, engine=None):
    config = AssistConfig.from_dict(fake_config({"m": replies}))
    router = ModelRouter(config, ledger=UsageLedger(tmp_path / "u.json"), environ={}, user="ana")
    return Assistant(router, engine), router


def test_explain_query_grounds_the_model_in_the_engine_plan(tmp_path):
    engine = _Engine()
    assistant, router = _assistant(tmp_path, [EXPLANATION], engine)
    result = assistant.explain_query(query_name="per_customer")
    assert engine.calls[0] == ("describe", "per_customer")
    assert engine.calls[1][0] == "explain"
    sent = router.provider("m").requests[0]  # type: ignore[attr-defined]
    assert "Aggregate[customer, window 1 min]" in sent.messages[0].content
    assert '"retention": "PT24H"' in sent.messages[0].content
    assert result.summary.startswith("It keeps a count")
    assert result.plan.startswith("Aggregate") and result.prompt == "explain_query@v1"
    body = result.to_dict()
    assert body["answeredBy"]["modelId"] == "m" and body["enginePlan"]["level"] == "physical"
    json.dumps(body)


def test_explain_refusal_without_sql_needs_no_engine(tmp_path):
    no_rewrite = json.dumps({**json.loads(REFUSAL), "rewrite": None})
    assistant, router = _assistant(tmp_path, [no_rewrite])
    result = assistant.explain_refusal("prv-2050")
    assert result.code == "PRV-2050" and result.engine is None
    assert result.rewrite is None and not result.rewrite_verdict["checked"]
    sent = router.provider("m").requests[0].messages[0].content  # type: ignore[attr-defined]
    assert "PRV-2050: The query's state would grow without bound" in sent
    assert "13. Aggregation" in sent
    assert "13-aggregation" in result.card_sections


def test_explain_refusal_uses_the_engine_words_and_checks_the_rewrite(tmp_path):
    engine = _Engine()
    assistant, router = _assistant(tmp_path, [REFUSAL], engine)
    sql = "SELECT customer, COUNT(*) FROM orders GROUP BY customer"
    result = assistant.explain_refusal("PRV-2050", sql)
    assert engine.calls[0] == ("validate", sql)
    sent = router.provider("m").requests[0].messages[0].content  # type: ignore[attr-defined]
    assert "unbounded state: GROUP BY customer needs a window" in sent
    assert result.engine == {"valid": False, "codes": ["PRV-2050"], "diagnostics": [
        {"code": "PRV-2050", "message": "unbounded state: GROUP BY customer needs a window",
         "helpUrl": "x"}]}
    assert engine.calls[-1][0] == "validate" and "TUMBLE" in engine.calls[-1][1]
    assert result.rewrite_verdict == {"checked": True, "valid": True, "diagnostics": []}


def test_explain_refusal_says_when_the_engine_accepts_the_statement(tmp_path):
    engine = _Engine(valid=True)
    assistant, router = _assistant(tmp_path, [REFUSAL], engine)
    result = assistant.explain_refusal("PRV-2050", "SELECT 1", check_rewrite=False)
    assert result.engine is not None and result.engine["valid"]
    sent = router.provider("m").requests[0].messages[0].content  # type: ignore[attr-defined]
    assert "The engine accepts this statement" in sent
    assert not result.rewrite_verdict["checked"]


def test_a_bad_code_is_refused_before_any_model_is_asked(tmp_path):
    assistant, router = _assistant(tmp_path, [REFUSAL])
    with pytest.raises(ValueError, match="codes look like PRV-2050"):
        assistant.explain_refusal("2050")
    assert isinstance(router.provider("m"), FakeProvider)
    assert router.provider("m").requests == []  # type: ignore[attr-defined]
