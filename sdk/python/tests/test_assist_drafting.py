"""Drafting a query from a description (ADR-058 phase 2): the context the engine gives, the
draft, the engine's judgement, the repair turns, and registration only by a person.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

No network beyond 127.0.0.1 and never a real model: the fake provider plays scripted answers and
tests/engine_support.py stands in for the engine's HTTP API.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
from typing import Any

import pytest
from assist_support import fake_config
from engine_support import FakeClient, FakeEngine, stream

from pravaha.api import EngineApi
from pravaha.assist import (
    AssistConfig,
    Assistant,
    ContextBuilder,
    ModelRouter,
    RegistrationRefused,
    UsageLedger,
    load_examples,
    load_golden_set,
    load_prompt,
)
from pravaha.assist.context import choose_examples, words
from pravaha.assist.drafting import (
    normalise_plan,
    normalise_retention,
    relations,
    select_of,
)

SDK = pathlib.Path(__file__).resolve().parents[1]
STUDIES = SDK.parent.parent / "examples" / "case-studies"
TOOL = SDK / "tools" / "build_examples.py"

WINDOWED = ("SELECT STREAM TUMBLE_END(ts, INTERVAL '1' MINUTE) AS window_end, customer, "
            "COUNT(*) AS orders FROM orders GROUP BY TUMBLE(ts, INTERVAL '1' MINUTE), customer")
UNBOUNDED = "SELECT customer, COUNT(*) AS n FROM orders GROUP BY customer"


def answer(sql: str = WINDOWED, *, keys: Any = ("window_end", "customer"), questions: Any = (),
           name: str = "orders_per_minute", **options: Any) -> str:
    return json.dumps({
        "name": name, "sql": sql, "keys": list(keys),
        "options": {"retention": options.get("retention"), "index": options.get("index"),
                    "sink": options.get("sink"), "lane": options.get("lane")},
        "explanation": "Counts each customer's orders in each minute.",
        "assumptions": ["A minute is a tumbling window of event time."],
        "questions": list(questions), "confidence": 0.8,
    })


@pytest.fixture
def engine():
    fake = FakeEngine()
    fake.streams = [
        stream("orders", "order_id:STRING", "customer:STRING", "amount:INT64", "ts:TIMESTAMP",
               event_time="ts"),
        stream("payroll", "employee:STRING", "salary:INT64", "paid_at:TIMESTAMP",
               event_time="paid_at"),
        stream("clicks", "click_id:STRING", "ts:TIMESTAMP", event_time="ts"),
    ]
    fake.readable = {"orders", "clicks"}          # payroll is listed, but not this person's
    fake.sinks = [{"name": "alerts", "plugin": "filesystem", "fields": [], "keyColumns": [],
                   "emitModes": ["APPEND", "RETRACT"], "acceptsRetractions": True,
                   "guarantee": "AT_LEAST_ONCE", "writers": []}]
    try:
        yield fake
    finally:
        fake.close()


def assistant_for(tmp_path, engine, replies, **kwargs: Any):
    config = AssistConfig.from_dict(fake_config({"m": replies}))
    router = ModelRouter(config, ledger=UsageLedger(tmp_path / "u.json"), environ={}, user="ana")
    api = EngineApi(engine.url)
    return Assistant(router, api, **kwargs), router


def sent_text(router) -> str:
    requests = router.provider("m").requests
    return "\n".join((r.system or "") + "\n" + "\n".join(m.content for m in r.messages)
                     for r in requests)


# ---------------------------------------------------------------------------------- resources


def _tool() -> Any:
    spec = importlib.util.spec_from_file_location("build_examples", TOOL)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.skipif(not STUDIES.exists(), reason="needs the repository's examples/")
def test_the_packaged_examples_and_golden_set_are_current_with_the_case_studies():
    examples, golden = _tool().render(STUDIES)
    resources = SDK / "pravaha" / "assist" / "resources"
    assert (resources / "examples.json").read_text(encoding="utf-8") == examples, (
        "a case study changed: run `.venv/bin/python tools/build_examples.py` in sdk/python")
    assert (resources / "golden-set.json").read_text(encoding="utf-8") == golden


def test_every_case_study_query_is_an_example_and_a_golden_case():
    examples = load_examples()
    assert len(examples) >= 20
    ids = [e.id for e in examples]
    assert ids == sorted(ids) and len(set(ids)) == len(ids)
    for example in examples:
        assert example.sql.upper().startswith("SELECT"), example.id
        assert example.keys and example.streams and example.description, example.id
    merchant = next(e for e in examples if e.view == "merchant_minute")
    assert merchant.options["index"] == "merchant"       # split out of its CREATE statement
    cases = load_golden_set()
    references = [c for c in cases if c.kind == "reference"]
    negatives = {c.id: c for c in cases if c.kind == "negative"}
    assert [c.id for c in references] == ids
    assert set(negatives) == {"negative/unbounded-group-by", "negative/join-without-time-bound",
                              "negative/stream-does-not-exist"}
    assert negatives["negative/stream-does-not-exist"].absent == ("refunds",)
    for case in negatives.values():
        assert set(case.expect) <= {"refused", "questions"} and case.requires


def test_the_generator_splits_statements_and_reads_descriptions():
    tool = _tool()
    body, options = tool.split_statement(
        "CREATE CONTINUOUS QUERY m\n  KEYED BY (a)\n  INDEX (b)\n  WRITING TO out\n"
        "  RETAIN FOR PT1H\nAS\nSELECT a, b FROM s")
    assert body == "SELECT a, b FROM s"
    assert options == {"index": "b", "lane": None, "retention": "PT1H", "sink": "out"}
    readme = ("# Study — x\n\n## The problem\n\nPeople need counts.\n\n## Step 3 — the queries\n\n"
              "### Counts per minute\n\n[`sql/01-continuous-c.sql`](sql/01-continuous-c.sql):\n\n"
              "```sql\nSELECT 1\n```\n\nIt counts rows, keyed by the minute. Like this:\n\n"
              "- a list item\n  continued\n")
    heading, description = tool.describe(readme, "01-continuous-c.sql", "Study")
    assert heading == "Counts per minute"
    assert description == "Counts per minute. It counts rows, keyed by the minute."


def test_the_draft_prompt_is_a_versioned_resource_with_a_repair_section():
    prompt = load_prompt("draft_query")
    assert prompt.id == "draft_query@v1" and set(prompt.extra) == {"repair"}
    assert prompt.schema is not None
    assert prompt.schema["required"] == ["name", "sql", "keys", "options", "explanation",
                                         "assumptions", "questions", "confidence"]
    assert "Never loosen the question" in prompt.system
    text = prompt.render_section("repair", code="PRV-2050", message="m", sql="S", excerpts="E",
                                 intent="")
    assert "PRV-2050" in text and "same inputs" in text
    with pytest.raises(LookupError):
        prompt.render_section("nope")


# ---------------------------------------------------------------------------------- helpers


def test_relations_are_what_the_query_reads():
    assert relations(WINDOWED) == ("orders",)
    assert relations("SELECT EXTRACT(HOUR FROM ts) FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(ts), "
                     "INTERVAL '1' HOUR)) t JOIN users FOR SYSTEM_TIME AS OF t.ts AS u ON "
                     "u.id = t.id WHERE note = 'FROM nowhere' -- FROM comment") == ("txn", "users")
    assert select_of("CREATE CONTINUOUS QUERY q KEYED BY (a) AS SELECT a FROM s") == \
        "SELECT a FROM s"
    assert normalise_plan("A\n   B   c  \n\n") == "A\n   B c"
    assert normalise_retention("24h") == ("PT24H", None)
    assert normalise_retention("7d") == ("P7D", None)
    assert normalise_retention("forever") == ("forever", None)
    assert normalise_retention("PT30M") == ("PT30M", None)
    assert normalise_retention("a month")[1] is not None


def test_examples_are_chosen_by_similarity_deterministically():
    examples = load_examples()
    chosen = choose_examples("attribute each click to the ad impression that preceded it",
                             examples)
    assert chosen[0][0].study == "adtech-click-attribution"
    assert len(chosen) == 4
    assert chosen == choose_examples("attribute each click to the ad impression that preceded it",
                                     examples)
    assert "orders" not in words("the orders") or words("the orders") == ["order"]


# ---------------------------------------------------------------------------------- the context


def test_the_context_names_only_what_the_caller_may_read(tmp_path, engine):
    engine.queries = [{"name": "secret_totals", "sql": "SELECT employee FROM payroll",
                       "keyColumns": [{"name": "employee", "ordinal": 0}], "retention": "forever",
                       "fingerprint": "abc"},
                      {"name": "order_feed", "sql": "SELECT order_id, customer FROM orders",
                       "keyColumns": [{"name": "order_id", "ordinal": 0}], "retention": "forever",
                       "fingerprint": "def"}]
    engine.views = {"order_feed": {"name": "order_feed", "schema": [
        {"name": "order_id", "type": "STRING"}, {"name": "customer", "type": "STRING"}],
        "keyColumns": [{"name": "order_id", "ordinal": 0}], "retention": "forever"}}
    engine.hidden_views = {"secret_totals"}   # listed, then refused when described
    assistant, router = assistant_for(tmp_path, engine, [answer()])
    draft = assistant.draft("orders per customer per minute")
    text = sent_text(router)
    assert "stream orders" in text and "stream clicks" in text and "view order_feed" in text
    assert "payroll" not in text and "salary" not in text and "secret_totals" not in text
    assert "sink alerts" in text and "AT_LEAST_ONCE" in text
    assert draft.context["streams"] == ["clicks", "orders"]
    assert draft.context["principal"] == "alice"
    # No row is ever read: only catalogue, validate and explain calls.
    paths = {p for _, p, _ in engine.requests}
    assert paths <= {"/api/v1/me/permissions", "/api/v1/streams", "/api/v1/queries",
                     "/api/v1/sinks", "/api/v1/views/order_feed", "/api/v1/views/secret_totals",
                     "/api/v1/queries/validate", "/api/v1/queries/explain"}


def test_without_a_permissions_endpoint_the_engine_listing_is_the_catalogue(tmp_path, engine):
    engine.permissions_status = 404
    engine.streams = [s for s in engine.streams if s["name"] != "payroll"]  # the engine filters
    builder = ContextBuilder(EngineApi(engine.url))
    context = builder.build("orders per minute")
    assert [s["name"] for s in context.streams] == ["clicks", "orders"]
    assert any("did not say what this principal may do" in n for n in context.notes)


def test_a_small_budget_leaves_out_the_least_relevant_and_says_so(engine):
    engine.streams += [stream(f"zz_unrelated_{i}", *[f"col{j}:STRING" for j in range(30)])
                       for i in range(20)]
    engine.readable = None
    builder = ContextBuilder(EngineApi(engine.url), budget_chars=9000)
    context = builder.build("orders per customer per minute")
    assert "orders" in [s["name"] for s in context.streams]
    assert context.omitted["streams"], "something had to be left out"
    assert "Left out for size" in context.catalogue_text
    assert context.chars <= 9000 + 4000   # the two reserved examples may exceed the budget
    again = builder.build("orders per customer per minute")
    assert again.catalogue_text == context.catalogue_text
    assert [e.id for e in again.examples] == [e.id for e in context.examples]


# ---------------------------------------------------------------------------------- drafting


def test_a_draft_the_engine_accepts(tmp_path, engine):
    assistant, router = assistant_for(tmp_path, engine, [answer(sink="alerts", retention="24h")])
    draft = assistant.draft("How many orders each customer places each minute, to alerts")
    assert draft.status == "accepted" and draft.accepted
    assert draft.verdict.by == "engine" and draft.verdict.accepted
    assert draft.plan and draft.plan.startswith("Plan")
    assert draft.keys == ("window_end", "customer") and draft.key_ordinals() == [0, 1]
    assert draft.options["retention"] == "PT24H"
    assert draft.guarantees["guarantee"] == "AT_LEAST_ONCE"
    assert draft.inputs == ("orders",) and len(draft.turns) == 1 and draft.repairs == 0
    assert draft.answered_by["modelId"] == "m" and draft.tokens > 0
    assert draft.prompt == "draft_query@v1"
    assert engine.calls("/api/v1/queries/validate") == [{"sql": WINDOWED}]
    statement = draft.statement()
    assert statement.startswith("CREATE CONTINUOUS QUERY orders_per_minute\n  KEYED BY "
                                "(window_end, customer)")
    assert "WRITING TO alerts" in statement and "RETAIN FOR PT24H" in statement
    body = draft.to_dict()
    json.dumps(body)
    assert body["verdict"]["accepted"] is True and body["enginePlan"]["level"] == "physical"
    assert len(router.provider("m").requests) == 1


def test_questions_are_returned_without_asking_the_engine(tmp_path, engine):
    assistant, _ = assistant_for(tmp_path, engine, [answer(
        "", keys=(), questions=["Which column is the amount: amount or a total?"])])
    draft = assistant.draft("big orders")
    assert draft.status == "questions" and not draft.accepted
    assert draft.questions == ("Which column is the amount: amount or a total?",)
    assert draft.verdict.accepted is None and draft.verdict.by == "none"
    assert engine.calls("/api/v1/queries/validate") == []
    assert engine.calls("/api/v1/queries/explain") == []


def test_a_refusal_is_repaired_on_the_second_turn(tmp_path, engine):
    assistant, router = assistant_for(tmp_path, engine, [answer(UNBOUNDED, keys=["customer"]),
                                                         answer()])
    draft = assistant.draft("orders per customer per minute")
    assert draft.status == "accepted" and draft.repairs == 1
    first, second = draft.turns
    assert first.kind == "draft" and first.verdict.code == "PRV-2050" and not first.verdict.accepted
    assert second.kind == "repair" and second.verdict.accepted and second.intent_kept
    repair = router.provider("m").requests[1]
    assert [m.role for m in repair.messages] == ["user", "assistant", "user"]
    told = repair.messages[-1].content
    assert "PRV-2050" in told and "grow without bound" in told
    assert "Aggregation" in told           # the dialect card's section about the code
    assert "must read exactly those" in told and "orders" in told


def test_still_refused_after_three_repairs_is_presented_in_the_engines_words(tmp_path, engine):
    assistant, router = assistant_for(tmp_path, engine, [answer(UNBOUNDED, keys=["customer"])])
    draft = assistant.draft("each customer's order count, forever")
    assert draft.status == "refused" and not draft.accepted
    assert len(draft.turns) == 4 and draft.repairs == 3
    assert draft.verdict.by == "engine" and draft.verdict.code == "PRV-2050"
    assert "grow without bound" in (draft.verdict.message or "")
    assert draft.plan is None and draft.guarantees == {} and draft.same_as == ()
    assert len(router.provider("m").requests) == 4


def test_every_turn_fits_the_documented_per_request_budget(tmp_path, engine):
    config = AssistConfig.from_dict(fake_config({"m": [answer(UNBOUNDED, keys=["customer"])]},
                                                budgets={"per_request_max_tokens": 8000}))
    router = ModelRouter(config, ledger=UsageLedger(tmp_path / "u.json"), environ={}, user="ana")
    assistant = Assistant(router, EngineApi(engine.url))
    assert assistant.context_budget() == 12000
    draft = assistant.draft("each customer's order count, forever")   # no BudgetExceeded
    assert len(draft.turns) == 4
    # Each repair carries the description, the last answer and the refusal -- not every turn.
    assert all(len(r.messages) == 3 for r in router.provider("m").requests[1:])


def test_a_repair_that_changes_the_inputs_is_not_sent_to_the_engine(tmp_path, engine):
    loosened = answer("SELECT click_id, ts FROM clicks", keys=["click_id"])
    assistant, _ = assistant_for(tmp_path, engine, [answer(UNBOUNDED, keys=["customer"]),
                                                    loosened, loosened, loosened])
    draft = assistant.draft("each customer's order count, forever")
    assert draft.status == "refused"
    assert [t.intent_kept for t in draft.turns] == [True, False, False, False]
    assert "different question" in (draft.turns[1].verdict.message or "")
    assert engine.calls("/api/v1/queries/validate") == [{"sql": UNBOUNDED}]
    # The final draft is the one the engine judged, in the engine's words.
    assert draft.sql == UNBOUNDED and draft.verdict.by == "engine"
    assert draft.verdict.code == "PRV-2050"


def test_a_key_the_select_does_not_produce_is_refused_before_it_is_called_accepted(
        tmp_path, engine):
    assistant, router = assistant_for(tmp_path, engine, [answer(keys=["minute", "customer"]),
                                                         answer()])
    draft = assistant.draft("orders per customer per minute")
    assert draft.status == "accepted"
    assert draft.turns[0].verdict.by == "assistant" and draft.turns[0].verdict.code == "PRV-2071"
    assert "'minute'" in (draft.turns[0].verdict.message or "")
    assert "PRV-2071" in router.provider("m").requests[1].messages[-1].content


def test_a_sink_the_caller_cannot_see_is_refused(tmp_path, engine):
    assistant, _ = assistant_for(tmp_path, engine, [answer(sink="payroll_out")], )
    draft = assistant.draft("orders per customer per minute", max_repairs=0)
    assert draft.status == "refused" and draft.verdict.by == "assistant"
    assert "no sink named 'payroll_out'" in (draft.verdict.message or "")


def test_a_running_query_with_the_same_plan_is_offered_for_reuse(tmp_path, engine):
    engine.queries = [{"name": "per_minute", "sql": WINDOWED, "fingerprint": "abc123",
                       "keyColumns": [{"name": "window_end"}, {"name": "customer"}],
                       "retention": "forever"}]
    engine.views = {"per_minute": {"name": "per_minute", "schema": [], "keyColumns": [],
                                   "retention": "forever"}}
    assistant, _ = assistant_for(tmp_path, engine, [answer()])
    draft = assistant.draft("orders per customer per minute")
    assert len(draft.same_as) == 1
    same = draft.same_as[0]
    assert same["name"] == "per_minute" and same["fingerprint"] == "abc123"
    assert same["match"] == "plan" and same["sameComputation"] is True
    assert "share one computation" in same["reuse"]


def test_the_engines_fingerprint_decides_the_same_computation(tmp_path, engine):
    # EXPLAINFP-1: an engine that answers the fingerprint a registration would get.
    engine.fingerprints = True
    ours = FakeEngine.fingerprint_of(WINDOWED, [0, 1])
    engine.queries = [
        {"name": "per_minute", "sql": WINDOWED, "fingerprint": ours,
         "keyColumns": [{"name": "window_end"}, {"name": "customer"}], "retention": "forever"},
        # The same plan, keys and retention, and another fingerprint: another principal's row
        # filters or tenant. Plan text would have called it the same computation.
        {"name": "theirs", "sql": WINDOWED, "fingerprint": "0123456789ab",
         "keyColumns": [{"name": "window_end"}, {"name": "customer"}], "retention": "forever"},
    ]
    engine.views = {name: {"name": name, "schema": [], "keyColumns": [], "retention": "forever"}
                    for name in ("per_minute", "theirs")}
    assistant, _ = assistant_for(tmp_path, engine, [answer()])
    draft = assistant.draft("orders per customer per minute")
    assert draft.fingerprint == ours and draft.to_dict()["fingerprint"] == ours
    asked = [c for c in engine.calls("/api/v1/queries/explain") if c.get("keys")]
    assert [c["keys"] for c in asked] == [[0, 1]]
    by_name = {s["name"]: s for s in draft.same_as}
    assert by_name["per_minute"]["match"] == "fingerprint"
    assert by_name["per_minute"]["sameComputation"] is True
    assert by_name["theirs"]["match"] == "plan" and by_name["theirs"]["sameComputation"] is False
    assert "another fingerprint" in by_name["theirs"]["reuse"]


def test_an_engine_without_fingerprints_leaves_the_draft_without_one(tmp_path, engine):
    assistant, _ = assistant_for(tmp_path, engine, [answer(retention="24h")])
    draft = assistant.draft("orders per customer per minute")
    assert draft.status == "accepted" and draft.fingerprint is None
    explained = engine.calls("/api/v1/queries/explain")[-1]
    assert explained["keys"] == [0, 1] and explained["retention"] == "PT24H"


# ---------------------------------------------------------------------------------- register


def test_register_needs_a_confirmation_and_an_accepted_draft(tmp_path, engine):
    client = FakeClient()
    assistant, _ = assistant_for(tmp_path, engine, [answer(retention="7d")], client=client)
    draft = assistant.draft("orders per customer per minute")
    with pytest.raises(RegistrationRefused, match="confirm"):
        assistant.register(draft)
    assert client.registered == []
    registered = assistant.register(draft, confirmed=True)
    assert client.registered == [("orders_per_minute", WINDOWED, [0, 1], None, "P7D")]
    assert registered["via"] == "register" and registered["fingerprint"]


def test_register_refuses_a_refused_draft_and_one_with_questions(tmp_path, engine):
    client = FakeClient()
    assistant, _ = assistant_for(tmp_path, engine, [answer(UNBOUNDED, keys=["customer"])],
                                 client=client)
    refused = assistant.draft("count forever", max_repairs=0)
    with pytest.raises(RegistrationRefused, match="not accepted by the engine"):
        assistant.register(refused, confirmed=True)
    asking, _ = assistant_for(tmp_path, engine, [answer("", keys=(), questions=["Which?"])],
                              client=client)
    with pytest.raises(RegistrationRefused):
        asking.register(asking.draft("something"), confirmed=True)
    assert client.registered == [] and client.queries == []


def test_an_index_or_a_lane_registers_through_the_statement(tmp_path, engine):
    client = FakeClient()
    assistant, _ = assistant_for(tmp_path, engine, [answer(index="customer", lane="dedicated")],
                                 client=client)
    draft = assistant.draft("orders per customer per minute")
    registered = assistant.register(draft, confirmed=True, name="mine")
    assert client.registered == []
    assert client.queries[0].startswith("CREATE CONTINUOUS QUERY mine\n")
    assert "INDEX (customer)" in client.queries[0]
    assert "WITH (lane = 'dedicated')" in client.queries[0]
    assert registered["via"] == "statement" and registered["name"] == "mine"
