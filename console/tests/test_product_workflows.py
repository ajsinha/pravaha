"""The persona surfaces, continued: replacements, the debugger, backpressure, diffs, strings.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Split out of ``tests/test_product.py`` (CONSOLESIZE-1), whose docstring says what these tests
are for; the stand-in engine and the fixtures are that file's, imported rather than copied.
"""
from __future__ import annotations

import json
import re
from typing import ClassVar

import pytest

pytest.importorskip("fastapi.testclient")

from fake_engine import FakeEngine
from test_product import CONSOLE_ROOT, _anonymous_on, _engine_down_client, _signed_in_on

from core import authoring


@pytest.fixture
def engine():
    return FakeEngine()


@pytest.fixture
def signed_in(engine):
    return _signed_in_on(engine)


@pytest.fixture
def anonymous(engine):
    return _anonymous_on(engine)


@pytest.fixture
def engine_down():
    return _engine_down_client()


# ============================================================ backfill and cutover (23.10)

def _replacing(engine: FakeEngine, name: str = "big_txn", **options):
    return engine.start_replacement(
        name, "SELECT txn_id, user_id, amount FROM txn WHERE amount > 500", [0],
        backfill="history", rate_limit=5000, **options)


def test_a_query_nothing_is_replacing_says_what_a_replacement_is(signed_in):
    """Never had data (23.12): the screen explains the thing and offers the way to start one,
    rather than an empty progress panel that reads as a stalled job."""
    page = signed_in.get("/queries/big_txn/replacement")
    assert page.status_code == 200
    assert 'id="rep-none"' in page.text
    assert "beside the running one" in page.text
    assert 'href="/workbench?query=big_txn"' in page.text
    # And nothing that looks like progress: no zeros standing in for a job that does not exist.
    assert 'id="rep-numbers"' not in page.text


def test_the_replacement_screen_shows_what_is_measured_and_no_estimate(signed_in, engine):
    """Design 23.10 asks for an ETA. There is not one, and this is where that is enforced:
    a source does not say how much history it holds, so every denominator is invented."""
    _replacing(engine)
    engine.backfill_progress("big_txn", historyRows=412_000, liveRows=980, rowsPerSecond=4800.0,
                             partitionsLive=3, lagSeconds=12.5)
    page = signed_in.get("/queries/big_txn/replacement")
    assert page.status_code == 200
    body = page.text
    assert "412,000" in body and "980" in body and "4,800 rows/s" in body
    assert "3 of 4" in body, "partitions live against total is the closest thing to progress"
    assert "12.5 s" in body
    assert "There is no estimate and no percentage here" in body
    # Nothing that draws a share of unknown work.
    numbers = body.split('id="rep-numbers"', 1)[1].split("</dl>", 1)[0]
    assert "progress" not in numbers.lower() and 'role="meter"' not in numbers
    assert "%" not in numbers


def test_a_backfill_that_has_read_nothing_says_the_lag_is_not_known(signed_in, engine):
    """Zero never stands in for "not measured": a 0.0 s lag would say the candidate had
    caught up exactly, which is the opposite of what it means before the first row."""
    _replacing(engine)
    page = signed_in.get("/queries/big_txn/replacement").text
    lag = page.split("Candidate behind the running version", 1)[1].split("</dd>", 1)[0]
    assert "not known yet" in lag and "0" not in lag


def test_the_cutover_is_refused_until_every_partition_has_reached_the_seam(signed_in, engine):
    """Offered only when the engine would accept it: a control that fails on click is the
    thing 23.12's unauthorized state exists to prevent, and the same rule holds for a
    control the engine's own precondition would refuse."""
    _replacing(engine)
    early = signed_in.get("/queries/big_txn/replacement").text
    button = early.split('id="rep-cutover-plain"', 1)[1].split(">", 1)[0]
    assert "disabled" in button
    assert "has not read all of the history yet" in early

    engine.backfill_progress("big_txn", partitionsLive=4, historyComplete=True)
    ready = signed_in.get("/queries/big_txn/replacement").text
    assert "disabled" not in ready.split('id="rep-cutover-plain"', 1)[1].split(">", 1)[0]


def test_cutting_over_and_rolling_back_reach_the_engine_and_say_what_happened(signed_in, engine):
    _replacing(engine)
    engine.backfill_progress("big_txn", partitionsLive=4, historyComplete=True)
    done = signed_in.post("/queries/big_txn/replacement/cutover", follow_redirects=False)
    assert done.status_code == 303
    assert ("cutover", "big_txn", None) in engine.replacement_calls
    page = signed_in.get(done.headers["location"])
    assert "now answers the new version" in page.text

    back = signed_in.post("/queries/big_txn/replacement/rollback", follow_redirects=False)
    assert back.status_code == 303
    assert ("rollback", "big_txn", None) in engine.replacement_calls
    assert "answers the replaced version again" in signed_in.get(back.headers["location"]).text


def test_the_rollback_window_is_shown_honestly_including_when_it_has_passed(signed_in, engine):
    """A window that has closed is the fact an operator most needs. A screen that simply
    stopped offering the button would leave them guessing which of the two it was."""
    _replacing(engine)
    engine.backfill_progress("big_txn", partitionsLive=4, historyComplete=True)
    assert "Nothing has been cut over yet" in signed_in.get("/queries/big_txn/replacement").text

    engine.cut_over("big_txn")
    open_window = signed_in.get("/queries/big_txn/replacement").text
    assert "retained until 2026-09-19T15:30:00Z" in open_window

    engine.replacements_by_name["big_txn"]["rollbackAvailable"] = False
    closed = signed_in.get("/queries/big_txn/replacement").text
    assert "closed" in closed and "2026-09-19T15:30:00Z" in closed
    assert "no longer retained" in closed
    assert "disabled" in closed.split('id="rep-rollback-plain"', 1)[1].split(">", 1)[0]


def test_the_engine_refusing_a_cutover_is_shown_with_its_code(signed_in, engine):
    _replacing(engine)
    refused = signed_in.post("/queries/big_txn/replacement/cutover", follow_redirects=False)
    page = signed_in.get(refused.headers["location"]).text
    assert "PRV-4014" in page and "has not caught up" in page
    assert ("cutover", "big_txn", None) not in engine.replacement_calls


def test_throttling_sends_what_was_typed_rather_than_clamping_it(signed_in, engine):
    """The engine refuses a raise above the ceiling the replacement started with. The console
    sends the number typed and shows that refusal: a silently altered number is worse."""
    _replacing(engine)
    signed_in.post("/queries/big_txn/replacement/throttle", data={"rate": "2000"},
                   follow_redirects=False)
    assert ("throttle", "big_txn", 2000) in engine.replacement_calls

    refused = signed_in.post("/queries/big_txn/replacement/throttle", data={"rate": "99999"},
                             follow_redirects=False)
    assert ("throttle", "big_txn", 99999) in engine.replacement_calls, "the console clamped it"
    page = signed_in.get(refused.headers["location"]).text
    assert "PRV-4018" in page and "may be slowed, not sped up" in page
    assert engine.replacements_by_name["big_txn"]["backfill"]["rateLimit"] == 2000


def test_pausing_the_backfill_keeps_what_it_has_read(signed_in, engine):
    _replacing(engine)
    engine.backfill_progress("big_txn", historyRows=412_000)
    signed_in.post("/queries/big_txn/replacement/pause", follow_redirects=False)
    paused = signed_in.get("/queries/big_txn/replacement").text
    assert "paused" in paused and "412,000" in paused
    signed_in.post("/queries/big_txn/replacement/resume", follow_redirects=False)
    assert ("resume", "big_txn", None) in engine.replacement_calls


def test_a_reader_sees_the_whole_screen_with_every_control_disabled(signed_in, engine):
    """Everything here needs the administer permission. The engine's policy decides, and a
    refusal is a state of the screen with its reason on the page (23.16), not a hidden page."""
    _replacing(engine)
    engine.administer_refused["big_txn"] = "administering 'big_txn' needs one of the roles [ops]"
    page = signed_in.get("/queries/big_txn/replacement")
    assert page.status_code == 200
    assert "needs one of the roles [ops]" in page.text
    assert 'id="rep-refused"' in page.text
    for control in ("bf-pause", "bf-throttle", "rep-cutover", "rep-rollback-btn", "rep-finish"):
        button = page.text.split(f'id="{control}"', 1)[1].split(">", 1)[0]
        assert "disabled" in button, control
        assert 'aria-describedby="rep-refused"' in button, control
    # And the numbers are still there: a reader may read.
    assert 'id="rep-numbers"' in page.text


def test_the_replacement_screen_lists_who_has_served_this_name(signed_in, engine):
    """The versions that have served the name and the frontier each took over at, oldest
    first. It reaches the console over the engine's REST surface, because a control-wire row
    is a flat list of strings and a list of sentences does not fit in one."""
    _replacing(engine)
    page = signed_in.get("/queries/big_txn/replacement").text
    assert 'id="rep-history"' in page
    trail = page.split('id="rep-history"', 1)[1].split("</ol>", 1)[0]
    assert "From the beginning" in trail
    assert '<code class="rep-history-version" title="The fingerprint of this version: abc123def456">abc123def456</code>' in trail
    assert 'id="rep-history-partial"' not in page

    engine.backfill_progress("big_txn", historyRows=412_000, historyComplete=True)
    engine.cut_over("big_txn")
    page = signed_in.get("/queries/big_txn/replacement").text
    trail = page.split('id="rep-history"', 1)[1].split("</ol>", 1)[0]
    # RPL-1: the position is formatted and the fingerprint is its own element, and the one
    # serving now says so, rather than one sentence per version as the engine words it.
    assert "From input position 412,000" in trail
    assert ">newfp</code>" in trail
    assert trail.count("serving now") == 1 and trail.rindex("serving now") > trail.index(">newfp</code>")


def test_the_version_history_is_read_from_the_engines_entries_and_not_its_sentences():
    """RPL-1. ``historyEntries`` carries the position and the fingerprint apart; the sentences
    in ``history`` are the engine's wording, and the console does not split them to guess."""
    from core.engine import Engine

    class Answering(Engine):
        answer: ClassVar[dict[str, object]] = {}

        def _rest(self, call):
            return dict(self.answer)

    engine = Answering("grpc://localhost:1", http_url="http://localhost:1")
    engine.answer = {"history": ["from the beginning: abc", "from 4471: def"],
                     "historyEntries": [{"fromFrontier": None, "version": "abc"},
                                        {"fromFrontier": 4471, "version": "def"}]}
    assert engine._replacement_history("orders") == [{"fromFrontier": None, "version": "abc"},
                                                     {"fromFrontier": 4471, "version": "def"}]
    engine.answer = {"history": ["from the beginning: abc"]}
    assert engine._replacement_history("orders") is None


def test_the_replacement_screen_says_when_it_could_not_read_the_version_history(signed_in, engine):
    """Partial (23.12). The history comes from a second call to a second surface, and a node
    with no HTTP URL configured has the replacement and not the trail. An empty list there
    would read as "nobody has served this name", which is never true of a query that runs."""
    engine.history_carried = False
    _replacing(engine)
    page = signed_in.get("/queries/big_txn/replacement").text
    assert 'id="rep-history-partial"' in page
    assert 'id="rep-history"' not in page


def test_the_replacement_json_answers_null_rather_than_404_for_a_query_without_one(signed_in, engine):
    body = signed_in.get("/api/v1/queries/big_txn/replacement").json()
    assert body == {"query": "big_txn", "replacement": None}
    _replacing(engine)
    body = signed_in.get("/api/v1/queries/big_txn/replacement").json()
    assert body["replacement"]["state"] == "BACKFILLING"
    assert body["replacement"]["backfill"]["partitions"] == 4
    assert body["replacement"]["history"] == [{"fromFrontier": None, "version": "abc123def456"}]
    assert signed_in.get("/api/v1/replacements").json()["items"][0]["name"] == "big_txn"


def test_an_engine_that_does_not_answer_is_the_screens_error_state(signed_in, engine):
    engine.fail("replacement")
    page = signed_in.get("/queries/big_txn/replacement")
    assert page.status_code == 200 and 'id="rep-error"' in page.text
    assert "correlation" in page.text


# ============================================================ the time-travel debugger (23.9)


def _forked(signed_in, name: str = "big_txn", checkpoint: str = "4471") -> str:
    """Forks ``name`` the way the screen does, and returns the session id from the redirect."""
    answer = signed_in.post(f"/queries/{name}/debug/fork", data={"checkpoint": checkpoint},
                            follow_redirects=False)
    assert answer.status_code == 303, answer.text[:400]
    return answer.headers["location"].split("session=")[1].split("&")[0]


def test_a_query_nothing_is_debugging_says_what_a_fork_is_and_what_it_cannot_touch(signed_in):
    """Never had data (23.12). The three absences are the feature, so they are on the screen
    rather than in the documentation: no sink, no reader, no shared lane."""
    page = signed_in.get("/queries/big_txn/debug")
    assert page.status_code == 200
    assert 'id="dbg-none"' in page.text
    for absence in ("No sink is attached", "Nothing can read the fork",
                    "Its lanes are its own"):
        assert absence in page.text, absence
    # And the positions it could start from, newest first, as the engine listed them.
    assert 'id="dbg-fork-form"' in page.text
    assert page.text.index("4471") < page.text.index("4469")


def test_a_node_with_no_checkpoint_of_this_query_does_not_guess_which_reason(signed_in, engine):
    """Three different things read the same from the list -- this node does not checkpoint,
    this query has not taken one, every one has been pruned -- and the screen says so rather
    than picking one. The engine tells them apart when a fork is asked for (PRV-8011)."""
    engine.checkpoints_by_query.pop("big_txn")
    page = signed_in.get("/queries/big_txn/debug").text
    assert 'id="dbg-no-checkpoints"' in page
    assert 'id="dbg-fork-form"' not in page
    refused = signed_in.post("/queries/big_txn/debug/fork", data={"checkpoint": ""},
                             follow_redirects=False)
    assert "PRV-8011" in refused.headers["location"]


def test_forking_redirects_so_a_refresh_does_not_fork_twice(signed_in, engine):
    """A fork is a whole second copy of the query, so repeating one is not free -- unlike a
    step, which nothing outside the fork can feel."""
    session = _forked(signed_in)
    assert ("fork", "big_txn", 4471) in engine.debug_calls
    page = signed_in.get(f"/queries/big_txn/debug?session={session}").text
    assert 'id="dbg-app"' in page
    assert session in page
    # Said permanently, and from the engine's own answer rather than from the template.
    assert 'id="dbg-banner"' in page and "Sinks are disabled" in page


def test_a_checkpoint_id_that_is_not_a_number_is_refused_by_name(signed_in, engine):
    refused = signed_in.post("/queries/big_txn/debug/fork", data={"checkpoint": "newest"},
                             follow_redirects=False)
    assert "action_error" in refused.headers["location"]
    assert "not+a+checkpoint+id" in refused.headers["location"].replace("%20", "+")
    assert not engine.debug_calls


def test_a_step_answers_with_its_report_rather_than_redirecting(signed_in):
    """The one POST on this console that does not redirect: the report is the answer, and the
    engine has no call that hands back a step it has already taken. Repeating it is the
    cheapest mistake on the screen -- nothing outside the fork can be reached by it."""
    session = _forked(signed_in)
    page = signed_in.post("/queries/big_txn/debug/step",
                          data={"session": session, "step": "row"})
    assert page.status_code == 200
    assert 'id="dbg-report"' in page.text
    # Four answers at once: what came in, what each operator did, what the view did, the time.
    assert "8841" in page.text and "u2" in page.text
    assert "Filter(amount &gt; 100)" in page.text
    assert "rows consumed in all" in page.text


def test_the_operator_lines_tell_apart_a_rejected_row_and_a_row_that_passed(signed_in):
    """The part a view alone cannot give. The second replayed row is 40, which the filter
    rejects: the view does not move, and only the operator numbers say why."""
    session = _forked(signed_in)
    signed_in.post("/queries/big_txn/debug/step", data={"session": session, "step": "row"})
    page = signed_in.post("/queries/big_txn/debug/step",
                          data={"session": session, "step": "row"}).text
    operators = page.split('class="table table-sm small mb-3 dbg-operators"', 1)[1].split("</table>", 1)[0]
    assert ">n2<" in operators and ">n1<" in operators and ">n0<" in operators
    # Scan 1 in / 1 out, Filter 1 in / 0 out, Project 0 in / 0 out.
    assert operators.count(">0<") == 3, operators
    assert "The view did not change." in page


def test_a_step_the_engine_cannot_read_is_refused_by_name_with_its_code(signed_in):
    """No second parser in the console: the spec is sent as typed, and PRV-8015 comes back."""
    session = _forked(signed_in)
    page = signed_in.post("/queries/big_txn/debug/step",
                          data={"session": session, "step": "until:total"})
    assert 'id="dbg-action-error"' in page.text
    assert "PRV-8015" in page.text
    assert '/help/codes/PRV-8015' in page.text


def test_an_empty_step_is_refused_before_the_engine_is_asked(signed_in, engine):
    """The console's own refusal, because an empty box would otherwise mean "one row" by
    accident -- which is a step somebody did not ask for."""
    session = _forked(signed_in)
    before = list(engine.debug_calls)
    page = signed_in.post("/queries/big_txn/debug/step", data={"session": session, "step": ""})
    assert "a step has to say how far" in page.text
    assert engine.debug_calls == before


def test_the_operator_state_is_paged_and_a_key_narrows_it(signed_in):
    """Bounded on the way in as well as out. 'hot' is the GROUP BY, so it is the fork with
    state: a plan of scans, filters and projections keeps nothing between rows."""
    session = _forked(signed_in, "hot", "4471")
    page = signed_in.get(f"/queries/hot/debug?session={session}").text
    assert "aggregate#0" in page and 'id="dbg-slots"' in page
    page = signed_in.get(f"/queries/hot/debug?session={session}&operator=aggregate%230").text
    assert 'id="dbg-state-page"' in page and "u1" in page and "u2" in page
    page = signed_in.get(
        f"/queries/hot/debug?session={session}&operator=aggregate%230&key=u2").text
    assert "u1" not in page.split('id="dbg-state-page"', 1)[1].split("</table>", 1)[0]
    page = signed_in.get(
        f"/queries/hot/debug?session={session}&operator=aggregate%230&key=nobody").text
    assert 'id="dbg-page-filtered"' in page


def test_a_stateless_plan_says_it_holds_nothing_rather_than_drawing_an_empty_table(signed_in):
    session = _forked(signed_in)
    page = signed_in.get(f"/queries/big_txn/debug?session={session}").text
    assert 'id="dbg-no-slots"' in page
    assert "keeps nothing between rows" in page


def test_the_fixture_is_shown_rather_than_written_anywhere(signed_in, engine):
    """The generated file belongs in the repository this engine is built from, not on the
    machine the browser happens to be on, so the console writes nothing and shows the source
    with the path it belongs at."""
    session = _forked(signed_in)
    signed_in.post("/queries/big_txn/debug/step", data={"session": session, "step": "rows:3"})
    page = signed_in.post("/queries/big_txn/debug/fixture",
                          data={"session": session, "fixture": "user 42 goes negative"}).text
    assert "User42GoesNegativeFixtureTest" in page
    assert "pravaha-it/src/test/java/com/ash/messaging/pravaha/it/fixtures/" in page
    # And what it asserts is said on the screen, not left to be discovered in the file.
    assert "the answer over those rows from empty" in page
    assert ("fixture", session, "user 42 goes negative") in engine.debug_calls


def test_a_fixture_with_no_name_is_refused_before_the_engine_is_asked(signed_in):
    session = _forked(signed_in)
    page = signed_in.post("/queries/big_txn/debug/fixture",
                          data={"session": session, "fixture": "  "}).text
    assert "an exported fixture needs a name" in page


def test_ending_a_session_releases_it_and_the_screen_says_the_session_is_over(signed_in, engine):
    session = _forked(signed_in)
    ended = signed_in.post("/queries/big_txn/debug/end", data={"session": session},
                           follow_redirects=False)
    assert ended.status_code == 303
    assert ("end", session, None) in engine.debug_calls
    page = signed_in.get(f"/queries/big_txn/debug?session={session}").text
    assert 'id="dbg-gone"' in page
    assert 'id="dbg-app"' not in page


def test_a_node_holding_as_many_sessions_as_it_allows_refuses_the_next(signed_in, engine):
    engine.debug_sessions_max = 1
    _forked(signed_in)
    refused = signed_in.post("/queries/hot/debug/fork", data={"checkpoint": ""},
                             follow_redirects=False)
    assert "PRV-8014" in refused.headers["location"]


def test_a_reader_sees_the_debugger_with_its_control_disabled_and_the_reason(signed_in, engine):
    """Unauthorized (23.12, 23.16). A fork shows the SQL, the input rows and the operator
    state, so reading a session takes the administer permission too -- and a refused identity
    is not even shown the checkpoint list, because the engine would refuse that as well."""
    engine.administer_refused["big_txn"] = "administering 'big_txn' needs one of the roles [ops]"
    page = signed_in.get("/queries/big_txn/debug")
    assert page.status_code == 200
    assert 'id="dbg-refused"' in page.text
    assert "needs one of the roles [ops]" in page.text
    button = page.text.split('id="dbg-fork"', 1)[1].split(">", 1)[0]
    assert "disabled" in button and 'aria-describedby="dbg-refused"' in button
    assert "4471" not in page.text


def test_the_palette_does_not_offer_a_debugger_the_policy_would_refuse(signed_in, engine):
    engine.administer_refused["hot"] = "administering 'hot' needs one of the roles [ops]"
    items = signed_in.get("/api/v1/palette").json()["items"]
    titles = [i["title"] for i in items]
    assert "big_txn — debug" in titles
    assert "hot — debug" not in titles


def test_an_engine_that_does_not_answer_the_checkpoints_is_the_screens_error_state(signed_in, engine):
    engine.fail("debug_checkpoints")
    page = signed_in.get("/queries/big_txn/debug")
    assert page.status_code == 200 and 'id="dbg-checkpoints-error"' in page.text
    assert "correlation" in page.text


def test_the_debug_json_api_mirrors_the_engines_own_paths(signed_in, engine):
    """The console is a client of the published API (ADR-033), and a second spelling of the
    same call is a second thing to keep in step."""
    assert signed_in.get("/api/v1/queries/big_txn/debug/checkpoints").json() == {
        "query": "big_txn", "checkpoints": [4471, 4470, 4469]}
    session = signed_in.post("/api/v1/queries/big_txn/debug", json={"checkpointId": 4470}).json()
    assert session["checkpointId"] == 4470 and session["sinksDisabled"] is True
    assert session["streams"] == ["txn"]
    sid = session["id"]
    assert [s["id"] for s in signed_in.get("/api/v1/debug/sessions").json()["items"]] == [sid]
    assert signed_in.get(f"/api/v1/debug/sessions/{sid}").json()["session"]["id"] == sid
    step = signed_in.post(f"/api/v1/debug/sessions/{sid}/step", json={"step": "row"}).json()
    assert step["sequence"] == 1 and step["kind"] == "ROW"
    assert [op["id"] for op in step["operators"]] == ["n0", "n1", "n2"]
    assert signed_in.get(f"/api/v1/debug/sessions/{sid}/state").json() == {"slots": []}
    assert signed_in.get(f"/api/v1/debug/sessions/{sid}/view").json()["changes"][0]["weight"] == 1
    made = signed_in.post(f"/api/v1/debug/sessions/{sid}/fixture", json={"name": "a wrong row"}).json()
    assert made["className"] == "AWrongRowFixtureTest"
    assert signed_in.delete(f"/api/v1/debug/sessions/{sid}").json()["ended"] is True
    assert signed_in.get(f"/api/v1/debug/sessions/{sid}").json() == {"session": None}


def test_the_state_page_keeps_the_key_beside_the_columns_rather_than_merged_into_them(signed_in):
    """The engine's REST answer merges the entry's key into the columns under "key", so an
    operator holding a column of its own by that name loses one of the two. A state page is
    read literally, so the console keeps them apart."""
    session = signed_in.post("/api/v1/queries/hot/debug", json={}).json()["id"]
    page = signed_in.get(
        f"/api/v1/debug/sessions/{session}/state/aggregate%230").json()
    assert page["entries"][0] == {"key": "u1", "values": {"n": "1"}}
    assert page["hasMore"] is False and page["total"] == 2


def test_the_debug_api_is_gated_like_the_screens(anonymous):
    for path in ("/api/v1/queries/big_txn/debug/checkpoints", "/api/v1/debug/sessions"):
        assert anonymous.get(path).status_code == 401, path


# ============================================================ backpressure and operators (B6)

def test_the_dashboard_draws_the_lane_backpressure_the_engine_now_publishes(signed_in):
    ops = signed_in.get("/operations").text
    assert "Shared lanes" in ops and "lane 0" in ops and "lane 1" in ops
    assert 'id="ops-lanes"' in ops
    snapshot = signed_in.get("/api/v1/ops/snapshot").json()
    hot = next(q for q in snapshot["queries"] if q["name"] == "hot")
    assert hot["blocked_fraction"] == 0.92
    assert hot["inbox_depth"] == 2040 and hot["inbox_cells"] == 2048
    assert hot["backpressure_waits"] == 41 and hot["backpressure_wait_seconds"] == 312.5
    assert snapshot["operators_enabled"] == 1


def test_a_node_with_no_shared_lane_says_so_rather_than_drawing_an_empty_table(engine):
    engine.metrics_text = "\n".join(
        line for line in engine.metrics_text.splitlines() if not line.startswith("pravaha_lane_"))
    ops = _signed_in_on(engine).get("/operations").text
    assert 'id="ops-lanes-none"' in ops and "No lane is shared" in ops
    assert 'id="ops-lanes"' not in ops


def test_the_verdict_names_the_query_and_the_operator_the_time_goes_into(signed_in):
    """Design 23.20's "is everything healthy, and if not, where?" -- and on a backpressured
    query, "where" is the operator, not the query."""
    snapshot = signed_in.get("/api/v1/ops/snapshot").json()
    assert snapshot["verdict"]["where"] == "hot → Aggregate (n0)"
    backpressured = next(f for f in snapshot["findings"] if f["title"] == "Cannot be fed fast enough")
    assert backpressured["query"] == "hot" and backpressured["operator"] == "Aggregate (n0)"
    assert "92% of the time" in backpressured["detail"]
    assert "2,040 of 2,048 cells" in backpressured["detail"]
    assert "shared lane" in backpressured["detail"], "whose fault it is on a shared lane"
    assert "Most of the time goes into Aggregate (n0)" in signed_in.get("/operations").text


def test_a_query_that_is_not_backpressured_raises_no_finding_about_it(signed_in):
    snapshot = signed_in.get("/api/v1/ops/snapshot").json()
    assert not [f for f in snapshot["findings"]
                if f["query"] == "big_txn" and f["title"] == "Cannot be fed fast enough"]


def test_the_plan_of_a_registered_query_carries_its_operators_and_its_bottleneck(signed_in):
    """Explaining a registered query's own SQL gets its running plan's numbers attached --
    same SQL, same plan, same node ids -- and explaining anything else does not, because the
    numbers would then be about a different plan."""
    plan = signed_in.post("/api/v1/sql/explain",
                          json={"sql": "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id",
                                "query": "hot"}).json()
    assert plan["metrics_state"] == "measured"
    assert set(plan["operator_metrics"]) == {n["id"] for n in plan["graph"]["nodes"]}
    assert plan["bottleneck"] == "n0"
    assert plan["query_metrics"]["blockedFraction"] == 0.92

    edited = signed_in.post("/api/v1/sql/explain",
                            json={"sql": "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id "
                                         "HAVING COUNT(*) > 2", "query": "hot"}).json()
    assert edited["metrics_state"] == "not_running"
    assert edited["operator_metrics"] is None and edited["bottleneck"] is None
    assert "not running" in edited["metrics_note"]
    assert edited["query_metrics"] is None

    anonymous_sql = signed_in.post("/api/v1/sql/explain", json={"sql": "SELECT * FROM txn"}).json()
    assert anonymous_sql["metrics_state"] == "not_running"
    assert anonymous_sql["operator_metrics"] is None


def test_the_running_plan_is_keyed_by_the_graphs_own_node_ids(signed_in, engine):
    from core.services import AuthoringService, CatalogService

    authoring_service = AuthoringService(engine, CatalogService(engine))
    plan = authoring_service.plan("hot")
    ids = {n["id"] for n in plan["graph"]["nodes"]}
    assert set(plan["operator_metrics"]) == ids
    assert plan["bottleneck"] in ids
    assert plan["metrics_state"] == "measured"
    assert plan["query_metrics"]["blockedFraction"] == 0.92


def test_a_node_with_the_counters_off_says_so_and_names_the_setting(signed_in, engine):
    """Three answers, not two: not registered, counters off, measured. The middle one must
    name the setting rather than showing an empty graph."""
    from core.services import AuthoringService, CatalogService

    engine.operator_metrics = False
    plan = AuthoringService(engine, CatalogService(engine)).plan("hot")
    assert plan["metrics_state"] == "operators_off"
    assert plan["operator_metrics"] is None and plan["bottleneck"] is None
    assert "pravaha.metrics.operators is off" in plan["metrics_note"]
    # The query's own totals are still there -- they are measured either way.
    assert plan["query_metrics"]["inboxDepth"] == 2040


def test_the_bottleneck_is_absent_rather_than_guessed_when_the_time_is_even(engine):
    """Measured, not inferred from row counts. A plan whose samples put nothing ahead names
    nothing, and a console that always marked the maximum would always accuse somebody."""
    from core.services import AuthoringService, CatalogService

    engine.operator_shares.pop("hot", None)
    plan = AuthoringService(engine, CatalogService(engine)).plan("hot")
    assert plan["metrics_state"] == "measured"
    assert plan["bottleneck"] is None
    snapshot = _signed_in_on(engine).get("/api/v1/ops/snapshot").json()
    assert snapshot["verdict"]["where"] == "hot"


# ============================================================ comparing two versions (23.7)

def _graph(*spec):
    """A plan as the engine sends it, from (operator, label, stateful, fields, consumer index)."""
    nodes, edges = [], []
    for i, (op, label, stateful, fields, consumer) in enumerate(spec):
        nodes.append({"id": f"n{i}", "operator": op, "detail": label, "stateful": stateful, "fields": fields})
        if consumer is not None:
            edges.append({"from": f"n{i}", "to": f"n{consumer}"})
    return authoring.plan_graph({"nodes": nodes, "edges": edges})


_COLS = ["txn_id", "user_id", "merchant", "amount", "event_time"]
#: The real engine's plans for a per-user tumbling count, and the same per user and merchant over
#: transactions above 100 (``pravaha explain``, physical).
_V1 = _graph(
    ("WindowedAggregate", "WindowedAggregate(TUMBLING 60000ms, keys=[0, 1, 2], 1 aggregate(s))", True,
     ["window_start", "window_end", "user_id", "txns"], None),
    ("Project", "Project[window_start, window_end, user_id]", False, ["window_start", "window_end", "user_id"], 0),
    ("WindowAssign", "WindowAssign(TUMBLING size=60000ms slide=60000ms on event_time)", False,
     _COLS + ["window_start", "window_end"], 1),
    ("Scan", "Scan(txn)", False, _COLS, 2))
_V2 = _graph(
    ("WindowedAggregate", "WindowedAggregate(TUMBLING 60000ms, keys=[0, 1, 2, 3], 1 aggregate(s))", True,
     ["window_start", "window_end", "user_id", "merchant", "txns"], None),
    ("Project", "Project[window_start, window_end, user_id, merchant]", False,
     ["window_start", "window_end", "user_id", "merchant"], 0),
    ("Filter", "Filter(amount > 100)", False, _COLS + ["window_start", "window_end"], 1),
    ("WindowAssign", "WindowAssign(TUMBLING size=60000ms slide=60000ms on event_time)", False,
     _COLS + ["window_start", "window_end"], 2),
    ("Scan", "Scan(txn)", False, _COLS, 3))


def test_an_inserted_operator_is_one_addition_and_disturbs_nothing_beneath_it():
    diff = authoring.plan_diff(_V1, _V2)
    # Matched by place and kind, not by id: v1's WindowAssign is n2, v2's is n3.
    assert ["n2", "n3"] in diff["pairs"] and ["n3", "n4"] in diff["pairs"]
    assert diff["right"] == {"n0": "changed", "n1": "changed", "n2": "added", "n3": "same", "n4": "same"}
    assert diff["left"] == {"n0": "changed", "n1": "changed", "n2": "same", "n3": "same"}
    assert diff["counts"] == {"added": 1, "removed": 0, "changed": 2, "same": 2}
    assert diff["identical"] is False
    added = [c for c in diff["operators"] if c["change"] == "added"]
    assert [(c["op"], c["label"]) for c in added] == [("Filter", "Filter(amount > 100)")]


def test_a_changed_aggregate_names_its_keys_through_its_inputs_columns():
    diff = authoring.plan_diff(_V1, _V2)
    agg = diff["operators"][0]
    assert agg["change"] == "changed" and agg["op"] == "WindowedAggregate"
    assert agg["what"] == ["label", "fields"]
    assert agg["keys"] == {"before": ["window_start", "window_end", "user_id"],
                           "after": ["window_start", "window_end", "user_id", "merchant"]}
    assert agg["fields"]["added"] == ["merchant"] and agg["fields"]["removed"] == []
    # A label without key ordinals, or ordinals past the input's columns, gives no names at all.
    assert authoring._keys_by_name({"label": "Filter(x > 1)"}, {"fields": ["x"]}) is None
    assert authoring._keys_by_name({"label": "Aggregate(group=[3], [COUNT(c)])"}, {"fields": ["a"]}) is None


def test_an_edited_predicate_is_one_changed_operator():
    before = _graph(("Project", "Project(a)", False, ["a"], None), ("Filter", "Filter(a > 100)", False, ["a"], 0),
                    ("Scan", "Scan(t)", False, ["a"], 1))
    after = _graph(("Project", "Project(a)", False, ["a"], None), ("Filter", "Filter(a > 500)", False, ["a"], 0),
                   ("Scan", "Scan(t)", False, ["a"], 1))
    diff = authoring.plan_diff(before, after)
    assert diff["counts"] == {"added": 0, "removed": 0, "changed": 1, "same": 2}
    changed = next(c for c in diff["operators"] if c["change"] == "changed")
    assert (changed["before"], changed["after"], changed["what"]) == ("Filter(a > 100)", "Filter(a > 500)", ["label"])


def test_an_operator_never_changes_into_another_kind():
    before = _graph(("Project", "Project(a)", False, ["a"], None), ("Scan", "Scan(t)", False, ["a"], 0))
    after = _graph(("Aggregate", "Aggregate(group=[0], [COUNT(c)])", True, ["a", "c"], None),
                   ("Scan", "Scan(t)", False, ["a"], 0))
    diff = authoring.plan_diff(before, after)
    assert diff["left"] == {"n0": "removed", "n1": "same"}
    assert diff["right"] == {"n0": "added", "n1": "same"}
    assert diff["counts"]["changed"] == 0


def test_a_joins_inputs_are_paired_left_with_left_and_right_with_right():
    before = _graph(("Join", "Join[a.k = b.k]", True, ["k"], None), ("Scan", "Scan(a)", False, ["k"], 0),
                    ("Scan", "Scan(b)", False, ["k"], 0))
    after = _graph(("Join", "Join[a.k = b.k]", True, ["k"], None), ("Scan", "Scan(a)", False, ["k"], 0),
                   ("Filter", "Filter(k > 1)", False, ["k"], 0), ("Scan", "Scan(b)", False, ["k"], 2))
    diff = authoring.plan_diff(before, after)
    assert diff["pairs"] == [["n0", "n0"], ["n1", "n1"], ["n2", "n3"]]
    assert diff["right"]["n2"] == "added" and diff["counts"] == {"added": 1, "removed": 0, "changed": 0, "same": 3}
    # A whole input that exists on one side only goes with everything beneath it.
    lone = _graph(("Join", "Join[a.k = b.k]", True, ["k"], None), ("Scan", "Scan(a)", False, ["k"], 0))
    assert authoring.plan_diff(before, lone)["left"]["n2"] == "removed"


def test_identical_plans_are_identical_and_an_empty_side_is_all_added():
    assert authoring.plan_diff(_V1, _V1)["identical"] is True
    assert authoring.plan_diff(_V1, _V1)["counts"]["same"] == 4
    empty = authoring.plan_diff(None, _V1)
    assert empty["identical"] is False and set(empty["right"].values()) == {"added"}


def test_the_consequences_say_what_is_known_and_what_is_not():
    differ = authoring.plan_diff(_V1, _V2)
    left = {"query": "v1", "keys": ["user_id", "window_end"], "retention": "PT24H", "fingerprint": "aaa",
            "output_fields": [{"name": "user_id", "type": "VARCHAR"}, {"name": "window_end", "type": "TIMESTAMP"}]}
    right = {"output_fields": [{"name": "user_id", "type": "VARCHAR"}, {"name": "merchant", "type": "VARCHAR"}]}
    kinds = {f["kind"]: f for f in authoring.diff_consequences(differ, left, right)}
    assert "separate_computation" in kinds and "not_determinable" in kinds
    assert kinds["schema_changes"]["added"] == ["merchant"] and kinds["schema_changes"]["removed"] == ["window_end"]
    assert kinds["keys_missing"]["missing"] == ["window_end"]
    assert kinds["state_changes"]["changed"] == [_V2["nodes"][0]["label"]]
    # Identical plans may share -- never "will": keys, retention and row filters decide it.
    same = {f["kind"]: f for f in authoring.diff_consequences(authoring.plan_diff(_V1, _V1), left,
                                                                  dict(left, query=None, fingerprint=None))}
    assert same["may_share"]["keys"] == ["user_id", "window_end"] and same["may_share"]["retention"] == "PT24H"
    assert "state_same" in same and "schema_same" in same
    # Both registered: the fingerprints answer it outright.
    known = authoring.diff_consequences(differ, left, dict(right, fingerprint="aaa", registered_as="v2"))
    assert known[0] == {"kind": "fingerprint_same", "left": "aaa", "right": "aaa", "left_name": "v1", "right_name": "v2"}
    # No plan on a side: the computation is not guessed at.
    assert authoring.diff_consequences(None, left, {"output_fields": None})[0]["kind"] == "computation_unknown"


def test_a_draft_compared_with_a_registered_query(signed_in):
    body = signed_in.post("/api/v1/sql/diff", json={
        "left": {"query": "big_txn"},
        "right": {"sql": "SELECT txn_id, user_id, amount FROM txn WHERE amount > 500", "label": "draft"}}).json()
    assert body["left"]["query"] == "big_txn" and body["left"]["sql"].endswith("amount > 100")
    assert body["left"]["keys"] == ["txn_id"] and body["left"]["fingerprint"] == "abc123def456"
    # Measured totals are the registered query's, and only on its side.
    assert body["left"]["query_metrics"]["rowsIn"] == 1200
    assert body["right"]["query_metrics"] is None and body["right"]["registered_as"] is None
    changed = [c for c in body["plan"]["operators"] if c["change"] == "changed"]
    assert [(c["before"], c["after"]) for c in changed] == [("Filter(amount > 100)", "Filter(amount > 500)")]
    assert body["consequences"][0]["kind"] == "separate_computation"
    assert body["same_sql"] is False


def test_a_draft_that_is_a_registered_querys_text_carries_its_fingerprint(signed_in):
    body = signed_in.post("/api/v1/sql/diff", json={
        "left": {"query": "hot"}, "right": {"sql": "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"}}).json()
    assert body["same_sql"] is True and body["plan"]["identical"] is True
    assert body["right"]["registered_as"] == "hot, hot_alias" and body["right"]["fingerprint"] == "fff000"
    assert body["consequences"][0]["kind"] == "fingerprint_same"


def test_two_drafts_compare_without_registry_findings(signed_in):
    body = signed_in.post("/api/v1/sql/diff", json={
        "left": {"sql": "SELECT a FROM txn WHERE amount > 1", "label": "one"},
        "right": {"sql": "SELECT a FROM txn WHERE amount > 1", "label": "two"}}).json()
    assert body["left"]["query_metrics"] is None and body["left"]["keys"] == []
    assert body["consequences"][0]["kind"] == "may_share_drafts"
    assert not any(f["kind"].startswith("keys_") for f in body["consequences"])


def test_a_side_the_engine_will_not_plan_is_partial_and_the_rest_still_answers(signed_in):
    body = signed_in.post("/api/v1/sql/diff", json={
        "left": {"query": "big_txn"}, "right": {"sql": "SELECT PLANONLY FROM txn"}}).json()
    assert body["right"]["graph"] is None and body["right"]["plan_error"]["code"] == "PRV-2050"
    assert body["right"]["output_fields"] is None
    assert body["plan"] is None and body["left"]["graph"] is not None
    kinds = [f["kind"] for f in body["consequences"]]
    assert kinds[0] == "computation_unknown" and "schema_unknown" in kinds


def test_a_plan_the_policy_withholds_is_not_permitted_not_an_error(signed_in, engine):
    engine.plan_refused["big_txn"] = "reading plans needs one of the roles [ops]"
    answer = signed_in.post("/api/v1/sql/diff", json={
        "left": {"query": "big_txn"}, "right": {"sql": "SELECT * FROM txn"}})
    assert answer.status_code == 200
    left = answer.json()["left"]
    assert left["refused"]["status"] == 403 and "roles [ops]" in left["refused"]["message"]
    assert left["graph"] is None and left["query_metrics"] is None


def test_comparing_needs_something_on_each_side(signed_in, engine_down):
    assert signed_in.post("/api/v1/sql/diff", json={"left": {"query": "big_txn"}, "right": {}}).status_code == 400
    assert signed_in.post("/api/v1/sql/diff", json={"left": {"query": "nope"},
                                                    "right": {"sql": "SELECT 1"}}).status_code == 404
    assert engine_down.post("/api/v1/sql/diff", json={"left": {"query": "big_txn"},
                                                      "right": {"sql": "SELECT 1"}}).status_code == 503


def test_the_workbench_offers_the_compare_topic(signed_in):
    page = signed_in.get("/workbench?query=big_txn").text
    assert 'href="/help/topics/backfill-cutover#comparing-two-versions"' in page
    assert 'id="comparing-two-versions"' in signed_in.get("/help/topics/backfill-cutover").text
    # The old address still answers, and says where it went.
    moved = signed_in.get("/help/topics/compare-versions", follow_redirects=False)
    assert moved.status_code == 301
    assert moved.headers["location"] == "/help/topics/backfill-cutover#comparing-two-versions"


# ============================================================ the UI string catalog

def test_every_ui_string_key_a_template_or_island_uses_is_in_the_catalog():
    """A typo in a key renders the key; this is what stops `nav.catlog` shipping in a nav bar."""
    from core.i18n import Messages

    messages = Messages()
    used: set[str] = set()
    prefixes: set[str] = set()
    for template in (CONSOLE_ROOT / "web" / "templates").glob("*.html"):
        text = template.read_text(encoding="utf-8")
        used |= set(re.findall(r"""\bt\(\s*["']([a-z0-9_.]+)["']\s*[,)]""", text))
        prefixes |= set(re.findall(r"""\bt\(\s*["']([a-z0-9_.]+\.)["']\s*~""", text))
    # The islands, and the classic per-screen scripts, which reach the same js.* keys through
    # PravahaApi.t (api.js).
    scripts = [*(CONSOLE_ROOT / "web" / "static" / "app").glob("*.js"),
               *(CONSOLE_ROOT / "web" / "static" / "js").glob("*.js")]
    for island in scripts:
        text = island.read_text(encoding="utf-8")
        used |= {"js." + k for k in re.findall(r"""\bt\(\s*"([a-z0-9_.]+)"\s*[,)]""", text)}
        prefixes |= {"js." + k for k in re.findall(r"""\bt\(\s*"([a-z0-9_.]+\.)"\s*\+""", text)}
    assert len(used) > 40
    missing = sorted(k for k in used if k not in messages.catalog)
    assert not missing, f"keys used but not in web/i18n/en.json: {missing}"
    for prefix in prefixes:
        assert any(k.startswith(prefix) for k in messages.catalog), prefix


def test_every_key_a_route_hands_a_page_is_in_the_catalog():
    """Routes pass keys, not sentences: the palette's entries, a not-found page's "Back to …",
    the roles. A key a route names and the catalog lacks would reach the browser as the key."""
    from core.i18n import Messages
    from routes.base import ROLES

    messages = Messages()
    used: set[str] = set()
    for module in (CONSOLE_ROOT / "routes").glob("*.py"):
        text = module.read_text(encoding="utf-8")
        used |= set(re.findall(r"""\b(?:t|ui_text)\(\s*f?["']([a-z0-9_.]+)["']""", text))
        # page("landing", "/") in the palette: palette.page.<key> and palette.hint.<key>
        for key in re.findall(r"""\bpage\(\s*"([a-z_]+)",\s*"/""", text):
            used |= {f"palette.page.{key}", f"palette.hint.{key}"}
    for meta in ROLES.values():
        used |= {meta["label"], meta["blurb"]}
    for area in ("help", "tutorials"):
        used |= {f"help.area.{area}.{part}" for part in ("kicker", "heading", "title", "blurb")}
    assert len(used) > 50
    missing = sorted(k for k in used if "." in k and k not in messages.catalog)
    assert not missing, f"keys a route uses but web/i18n/en.json lacks: {missing}"


def test_no_user_visible_english_bypasses_the_catalog():
    """The islands, the classic scripts and the templates say nothing to a person that is not
    looked up by key. A new hard-coded sentence, label, placeholder or title fails here with its
    file and line; tests/i18n_scan.py says what it reads and ALLOWED what it lets through."""
    from i18n_scan import scan

    found = scan()
    assert not found, "user-visible English outside web/i18n/en.json:\n  " + "\n  ".join(found)


def test_the_english_guard_has_teeth():
    """What the guard must catch, and what it must leave alone, on snippets written for it."""
    from i18n_scan import _css_classes, scan_script_text, scan_template_text

    classes = _css_classes()
    caught = [
        'const v = html`<p class="small text-muted">Nothing here yet</p>`;',
        'const v = html`<button title="Close the panel">×</button>`;',
        'const v = html`<input placeholder="type a name" />`;',
        'const v = html`<div>${ok ? html`<span>Saved</span>` : null}</div>`;',
        'announce("Snippet saved");',
        'button.textContent = "Copied";',
        'const PANELS = [["run", "Run"]];',
        'const v = html`<span>${busy ? "Registering…" : "Register"}</span>`;',
        'throw new Error("the editor did not load");',
    ]
    for snippet in caught:
        assert scan_script_text("x.js", snippet, classes), snippet
    left_alone = [
        'const v = html`<p class="small text-muted">${t("wb.run.idle_body")}</p>`;',
        'const v = html`<div class="d-flex gap-2"><code>PT24H</code> ${t("wb.reg.or")}</div>`;',
        'el.className = "btn btn-sm btn-outline-secondary";',
        'if (event.key === "ArrowRight") next();',
        'setState({ status: "loading" });',
        '/* A comment in English is for the next person reading the code. */ go();',
        'const re = /\\bFROM\\s+([A-Za-z_]\\w*)/i;',
        'call("/sql/validate", { json: { sql } });',
    ]
    for snippet in left_alone:
        assert not scan_script_text("x.js", snippet, classes), snippet
    assert scan_template_text("x.html", "<h1>Hello there</h1>", classes)
    assert scan_template_text("x.html", '<input placeholder="a name">', classes)
    assert not scan_template_text("x.html", "<h1>{{ t('nav.catalog') }}</h1>", classes)
    assert not scan_template_text("x.html", '<p><a href="/help/codes/PRV-2041">PRV-2041</a></p>', classes)
    assert not scan_template_text("x.html", '<span translate="no">RUNNING</span>', classes)
    assert not scan_template_text("x.html", "<script>const a = 'Some words';</script>", classes)


def test_the_catalog_fills_named_parameters_and_says_when_one_is_missing():
    from core.i18n import Messages, MissingMessage

    messages = Messages()
    assert messages("plugins.columns", n=4) == "4 columns"
    assert messages("no.such.key") == "no.such.key"
    strict = Messages(strict=True)
    with pytest.raises(MissingMessage):
        strict("no.such.key")
    with pytest.raises(MissingMessage):
        strict("plugins.columns")  # needs n
    assert messages.for_script()["palette.label"] == "Command palette"
    # An unknown language falls back to English rather than rendering keys.
    assert Messages("xx")("nav.catalog") == "Catalog"


def test_the_shell_and_the_palette_speak_from_the_catalog(signed_in):
    page = signed_in.get("/catalog").text
    assert '<script type="application/json" id="i18n-messages">' in page
    assert '"palette.placeholder"' in page
    assert ">Skip to content<" in page and 'aria-label="Primary"' in page


#: Every kind of page the shell wraps: signed in and anonymous, a product screen, the
#: documentation, the sign-in page, the landing page and the two refusals.
EVERY_KIND_OF_PAGE = ["/", "/about", "/help", "/help/topics/getting-started", "/login",
                      "/catalog", "/queries/big_txn", "/operations", "/workbench",
                      "/views/no_such_view"]


@pytest.mark.parametrize("path", EVERY_KIND_OF_PAGE)
def test_the_slogan_is_on_every_page_in_italics(signed_in, path):
    """"Ask once. Answer always." is the sentence at the top of every source file in this
    repository, and it is on every page of the product for the same reason. In the shell, so
    no template can be the one that forgot it, and from the string catalog, as every
    user-visible string is."""
    from core.i18n import Messages

    page = signed_in.get(path).text
    assert Messages()("shell.slogan") == "Ask once. Answer always."
    assert "Ask once. Answer always." in page, path
    assert 'class="pv-foot-slogan slogan"' in page, path
    assert "/static/css/theme.css" in page and '<footer class="pv-foot">' in page, path
    theme = (CONSOLE_ROOT / "web" / "static" / "css" / "theme.css").read_text(encoding="utf-8")
    assert ".pv-foot-slogan { font-style: italic;" in theme


def test_the_slogan_is_on_a_page_nobody_has_signed_in_for(anonymous):
    """The landing page and the sign-in page are the first two a person ever sees."""
    for path in ("/", "/login", "/help"):
        assert "Ask once. Answer always." in anonymous.get(path).text, path


# ============================================================ the landing page


def test_the_landing_page_is_what_an_anonymous_visitor_gets(anonymous):
    """Not a redirect to the sign-in form. Somebody arriving at a bare host name is at least
    as likely to be asking what this server is as to be an operator checking on it, and the
    page has to answer the first without a session."""
    page = anonymous.get("/", follow_redirects=False)
    assert page.status_code == 200
    assert "Ask once." in page.text and "Answer always." in page.text
    # The rail: the version from the configuration, the page's sections, and the two public
    # documents. Every one of them reachable without signing in.
    assert 'class="rail"' in page.text
    assert 'href="/help"' in page.text and 'href="/about"' in page.text
    assert anonymous.get("/help").status_code == 200
    assert anonymous.get("/about").status_code == 200


def test_the_landing_pages_call_to_action_follows_the_session(anonymous, signed_in):
    """Sign in when there is no session, the console when there is -- in the rail, in the
    hero and in the closing, so none of the three sends somebody to a form they have already
    filled in."""
    out = anonymous.get("/").text
    assert out.count('href="/login"') >= 3 and 'href="/home"' not in out
    inn = signed_in.get("/").text
    assert inn.count('href="/home"') >= 3
    assert 'id="rail-cta"' in out and 'id="rail-cta"' in inn


def test_the_landing_page_tells_a_stranger_nothing_about_this_deployment(anonymous, signed_in):
    """A page anonymous readers can see is a disclosure decision, and this is the decision:
    the console's own version and whether the engine answers -- which is what reaching this
    port establishes anyway -- and nothing else. The engine's address, the reason it is not
    answering, and what is registered on it wait for a session."""
    out = anonymous.get("/").text
    assert "engine up" in out                      # reachability: safe
    assert "engine.test" not in out                # the address: not
    assert "big_txn" not in out and "/queries/" not in out
    # And the same fact in the shell, which is on every page a stranger can reach.
    for path in ("/", "/help", "/login", "/about"):
        assert "engine.test" not in anonymous.get(path).text, path
    # A signed-in reader is shown it, because it is the thing they need when a screen is empty.
    assert "engine.test" in signed_in.get("/").text


def test_the_figure_is_an_island_that_says_in_words_what_it_draws(anonymous):
    """Three figures drawn by the page's own script: decorative and aria-hidden, their labels from
    the catalog, and everything they draw said in text beside them -- the +1 and the −1 in the
    legend, the correction in "How it works", the windows and the lamps in their captions."""
    from html import unescape

    page = anonymous.get("/").text
    svg = re.search(r'<svg id="hero-flow" viewBox="[^"]*" aria-hidden="true" focusable="false"\s+data-flow="([^"]*)"', page)
    assert svg, "no aria-hidden hero figure"
    flow = json.loads(unescape(svg.group(1)))
    assert (flow["ask"], flow["answer"]) == ("Ask once.", "Answer always.")
    assert flow["question"].startswith("SELECT ") and flow["users"] == "u1,u2,u3,u4"
    assert (flow["plus"], flow["minus"]) == ("+1", "−1")
    for figure in ('id="windows-flow"', 'id="diyas-flow"'):
        assert figure in page, figure
    # Said in words: the legend under the hero, "How it works", and each figure's caption.
    assert 'class="w plus">+1<' in page and 'class="w minus">−1<' in page
    assert "a row taken back" in page and "steps back to 90 without" in page
    assert "A LATE EVENT REOPENS IT AS A CORRECTION" in page
    assert '<script src="/static/js/landing.js' in page


def test_the_figure_script_is_plain_themed_and_stops_when_it_should():
    """An island: plain script from this server, no library and no network. Every colour is a theme
    token used as var(--…), so the figures follow a theme change at once; reduced motion gets a still
    frame and no loop; a hidden page or figures scrolled out of view stop the loop."""
    script = (CONSOLE_ROOT / "web" / "static" / "js" / "landing.js").read_text(encoding="utf-8")
    assert not re.search(r"https?://(?!www\.w3\.org/2000/svg)|import\s|require\(", script)
    for token in ("var(--flow)", "var(--retract)", "var(--on-flow)", "var(--surface)", "var(--code)", "var(--serif)"):
        assert token in script, token
    # No colour of its own.
    assert not re.search(r"#[0-9a-fA-F]{3,6}\b", script)
    assert "prefers-reduced-motion: reduce" in script and "drawStill()" in script
    assert "visibilitychange" in script and "cancelAnimationFrame" in script
    assert "IntersectionObserver" in script
    # The same illustration on every load: a seeded generator, never Math.random.
    assert "Math.random" not in script


def test_the_figure_carries_no_number_that_was_typed_into_it(anonymous):
    """Everything on the page that is a fact about this deployment comes from where the rest
    of the console gets it. The figure's rows are an illustration and are labelled as one;
    the version beside the wordmark is the configured one."""
    from core.i18n import Messages

    page = anonymous.get("/").text
    # The version the page must show is the configured one, read here from the same file the
    # console reads, rather than typed into the test: it said "0.1.0" and failed the day the
    # release script moved the console to 0.1.1, in a test whose subject is not typing numbers in.
    configured = re.search(r'^  version: "([^"]+)"', (CONSOLE_ROOT / "config" / "application.yaml").read_text(), re.MULTILINE)
    assert configured, "console/config/application.yaml declares app.version"
    version = configured.group(1)
    assert Messages()("landing.instance.console", version=version) == f"console {version}"
    assert f"console {version}" in page
    assert "FIG. 01" in page
