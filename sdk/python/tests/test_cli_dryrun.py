"""``--dry-run``: a plan made of reads, and never a change.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The fake node records every request. Under ``--dry-run`` each one must be a ``GET`` or one of the
two ``POST``s that plan and keep nothing (``/queries/validate``, ``/queries/explain``); and
``--url`` points at a port nothing listens on, so a Flight call -- every query mutation is one --
would exit 3 rather than pass unnoticed.
"""

from __future__ import annotations

import json

import pytest

from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_UNREACHABLE
from pravaha.cli._app import build_parser
from pravaha.cli._completion import walk
from pravaha.cli._dryrun import COVERED
from test_cli import _Engine, answer, engine, home, run  # noqa: F401

NOWHERE = "grpc://127.0.0.1:1"
_PLANNING = {"/api/v1/queries/validate", "/api/v1/queries/explain"}

QUERY = {
    "name": "spend", "state": "RUNNING", "sql": "SELECT u, SUM(x) FROM t GROUP BY u",
    "fingerprint": "fp-old", "sharedWith": [], "keyColumns": [{"name": "u", "ordinal": 0}],
    "sink": {"name": "kafka_out", "attached": True}, "reads": ["t"], "readsFrom": [],
    "dependants": [], "owner": "ann",
}


def dry(engine_url: str, *argv: str) -> "tuple[int, str, str]":
    code, out, err = run(*argv, "--dry-run", "--http", engine_url, "--url", NOWHERE)
    changed = [c for c in _Engine.calls
               if c["method"] != "GET" and c["path"].split("?")[0] not in _PLANNING]
    assert not changed, f"--dry-run sent a mutating call: {changed}"
    return code, out, err


def test_every_covered_command_takes_dry_run_and_nothing_else_does():
    tree = walk(build_parser())
    offered = {" ".join(path) for path, node in tree.items() if "--dry-run" in node["opts"]}
    # A scaffold's --dry-run lists files it would write; it is not a planner of reads.
    assert offered == set(COVERED) | {"init", "plugin new"}


# ---------------------------------------------------------------------------------- drop


def test_drop_shows_sharing_dependants_sink_and_subscribers(engine, home):
    answer("GET", "/api/v1/queries/spend", QUERY)
    answer("GET", "/api/v1/queries/spend/plan", {"query": {"subscribers": 3}})
    code, out, err = dry(engine, "drop", "--name", "spend", "--yes")  # --yes changes nothing
    assert code == EXIT_OK, err
    assert "nothing was changed" in out and "computation's last name" in out
    assert "kafka_out" in out and "3 live subscriptions" in out
    code, out, _ = dry(engine, "drop", "--name", "spend", "--json")
    plan = json.loads(out)
    assert plan["dryRun"] and plan["wouldSucceed"] and plan["releasesComputation"]
    assert plan["subscribers"] == 3 and plan["refusal"] is None


def test_drop_of_a_shared_computation_says_it_survives(engine, home):
    answer("GET", "/api/v1/queries/spend", dict(QUERY, sharedWith=["spend_copy"]))
    code, out, _ = dry(engine, "drop", "--name", "spend")
    assert code == EXIT_OK and "keeps running: spend_copy still names it" in out


def test_drop_with_dependants_would_be_refused_and_exits_one(engine, home):
    answer("GET", "/api/v1/queries/spend", dict(QUERY, dependants=["by_region", "ALERT big"]))
    code, out, _ = dry(engine, "drop", "--name", "spend", "--json")
    plan = json.loads(out)
    assert code == EXIT_REFUSED and plan["refusal"]["code"] == "PRV-8024"
    assert "by_region" in plan["refusal"]["message"]


def test_drop_of_a_missing_query_carries_the_engines_refusal(engine, home):
    answer("GET", "/api/v1/queries/nope",
           {"code": "PRV-8002", "message": "no continuous query 'nope'"}, 404)
    code, out, _ = dry(engine, "drop", "--name", "nope")
    assert code == EXIT_REFUSED and "PRV-8002" in out


def test_a_node_that_does_not_answer_still_exits_three(home):
    code, _, _ = run("drop", "--name", "spend", "--dry-run", "--http", "http://127.0.0.1:1",
                     "--url", NOWHERE)
    assert code == EXIT_UNREACHABLE


# ---------------------------------------------------------------------------------- replace


def test_replace_validates_explains_and_diffs_the_plans(engine, home):
    answer("GET", "/api/v1/queries/spend", QUERY)
    answer("GET", "/api/v1/queries/spend/replacement", {"code": "PRV-4016", "message": "no"}, 404)
    answer("POST", "/api/v1/queries/validate", {"valid": True, "diagnostics": []})
    answer("POST", "/api/v1/queries/explain", {"plan": "Aggregate\n  Scan t\n", "fingerprint": "fp-new"})
    code, out, err = dry(engine, "replace", "--name", "spend", "--sql", "SELECT u FROM t")
    assert code == EXIT_OK, err
    assert "a backfill starts" in out and "pravaha cutover --name spend" in out
    assert "fp-new" in out
    assert [c["body"]["sql"] for c in _Engine.calls if c["path"] == "/api/v1/queries/validate"] \
        == ["SELECT u FROM t"]


def test_replace_with_sql_the_engine_refuses_exits_one_with_its_code(engine, home):
    answer("GET", "/api/v1/queries/spend", QUERY)
    answer("POST", "/api/v1/queries/validate", {"valid": False, "diagnostics": [
        {"code": "PRV-2002", "message": "Object 'nope' not found"}]})
    code, out, _ = dry(engine, "replace", "--name", "spend", "--sql", "SELECT * FROM nope", "--json")
    assert code == EXIT_REFUSED
    assert json.loads(out)["refusal"]["code"] == "PRV-2002"


def test_replace_while_one_is_in_flight_would_be_refused(engine, home):
    answer("GET", "/api/v1/queries/spend", QUERY)
    answer("GET", "/api/v1/queries/spend/replacement", {"name": "spend", "state": "BACKFILLING"})
    answer("POST", "/api/v1/queries/validate", {"valid": True})
    code, out, _ = dry(engine, "replace", "--name", "spend", "--sql", "SELECT u FROM t")
    assert code == EXIT_REFUSED and "PRV-4017" in out


# ---------------------------------------------------------------------------------- the steps


@pytest.mark.parametrize("verb, state, code, words", [
    ("cutover", "CAUGHT_UP", EXIT_OK, "the name moves to the candidate"),
    ("cutover", "BACKFILLING", EXIT_REFUSED, "PRV-4014"),
    ("rollback", "CUT_OVER", EXIT_OK, "moves back"),
    ("rollback", "CAUGHT_UP", EXIT_REFUSED, "PRV-8003"),
    ("abandon", "BACKFILLING", EXIT_OK, "candidate version is released"),
    ("abandon", "CUT_OVER", EXIT_REFUSED, "already cut over"),
    ("finish", "CUT_OVER", EXIT_OK, "no rollback after this"),
    ("finish", "BACKFILLING", EXIT_REFUSED, "PRV-8003"),
])
def test_each_replacement_step_reads_the_state_it_needs(engine, home, verb, state, code, words):
    answer("GET", "/api/v1/queries/spend/replacement",
           {"name": "spend", "state": state, "rollbackAvailable": True, "candidate": "c1"})
    extra = ("--yes",) if verb in ("abandon", "finish") else ()  # --yes changes nothing either
    got, out, err = dry(engine, verb, "--name", "spend", *extra)
    assert got == code, err
    assert words in out


# ---------------------------------------------------------------------------------- grants


def test_grant_and_revoke_show_the_grants_before_and_after(engine, home):
    answer("GET", "/api/v1/catalog/objects/sales.revenue", {
        "object": {"name": "acme.sales.revenue"},
        "grants": [{"privilege": "SELECT", "granteeType": "ROLE", "grantee": "analyst"},
                   {"privilege": "SUBSCRIBE", "granteeType": "ROLE", "grantee": "analyst"},
                   {"privilege": "SELECT", "granteeType": "USER", "grantee": "bob"}],
        "access": {"privileges": [{"privilege": "MANAGE", "allowed": True}]},
    })
    code, out, _ = dry(engine, "grant", "MANAGE", "sales.revenue", "--role", "analyst", "--json")
    plan = json.loads(out)
    assert code == EXIT_OK
    assert plan["held"] == ["SELECT", "SUBSCRIBE"] and plan["after"] == ["MANAGE", "SELECT", "SUBSCRIBE"]
    code, out, _ = dry(engine, "revoke", "SELECT", "sales.revenue", "--role", "analyst", "--json")
    assert json.loads(out)["after"] == ["SUBSCRIBE"]


def test_grant_without_manage_would_be_refused(engine, home):
    answer("GET", "/api/v1/catalog/objects/x", {
        "object": {"name": "x"}, "grants": [],
        "access": {"privileges": [{"privilege": "MANAGE", "allowed": False}]}})
    code, out, _ = dry(engine, "grant", "SELECT", "x", "--user", "ann")
    assert code == EXIT_REFUSED and "MANAGE" in out


# ---------------------------------------------------------------------------------- policies


POLICY = {"name": "eu_only", "kind": "ROW_FILTER", "expression": "region = 'EU'",
          "bindings": [{"object": "payments"}]}


def test_policy_drop_while_bound_would_be_refused(engine, home):
    answer("GET", "/api/v1/catalog/policies/eu_only", POLICY)
    code, out, _ = dry(engine, "policy", "drop", "eu_only", "--yes")
    assert code == EXIT_REFUSED and "PRV-7040" in out and "payments" in out
    answer("GET", "/api/v1/catalog/policies/eu_only", dict(POLICY, bindings=[]))
    assert dry(engine, "policy", "drop", "eu_only")[0] == EXIT_OK


def test_policy_bind_and_unbind_read_the_bindings(engine, home):
    answer("GET", "/api/v1/catalog/policies/eu_only", POLICY)
    code, out, _ = dry(engine, "policy", "bind", "eu_only", "--on", "payments")
    assert code == EXIT_REFUSED and "already bound" in out
    code, out, _ = dry(engine, "policy", "unbind", "eu_only", "--on", "orders")
    assert code == EXIT_OK and "not bound to orders" in out
    code, out, _ = dry(engine, "policy", "unbind", "eu_only", "--on", "payments")
    assert code == EXIT_OK and "shown again" in out


# ---------------------------------------------------------------------------------- identity


def test_user_changes_read_the_user_and_their_sessions(engine, home):
    answer("GET", "/api/v1/users", {"users": [{"username": "ann", "roles": ["analyst"],
                                               "status": "active"}]})
    answer("GET", "/api/v1/sessions", {"sessions": [{"username": "ann"}, {"username": "bob"}]})
    code, out, _ = dry(engine, "user", "disable", "ann")
    assert code == EXIT_OK and "(1 now)" in out
    code, out, _ = dry(engine, "user", "roles", "ann", "--roles", "admin", "--json")
    plan = json.loads(out)
    assert plan["added"] == ["admin"] and plan["removed"] == ["analyst"]
    code, out, _ = dry(engine, "user", "enable", "ann")
    assert code == EXIT_OK and "already active" in out
    code, out, _ = dry(engine, "user", "disable", "zed")
    assert code == EXIT_REFUSED and "PRV-7021" in out


def test_key_revoke_finds_the_key(engine, home):
    answer("GET", "/api/v1/keys", {"keys": [{"keyId": "k1", "name": "ci", "status": "active"}]})
    code, out, _ = dry(engine, "key", "revoke", "k1")
    assert code == EXIT_OK and "stops working at once" in out
    code, out, _ = dry(engine, "key", "revoke", "k9")
    assert code == EXIT_REFUSED and "PRV-7021" in out


# ---------------------------------------------------------------------------------- alerts


def test_alert_drop_reads_the_alert_and_honours_if_exists(engine, home):
    answer("GET", "/api/v1/alerts/big", {"alert": {"name": "big", "view": "spend", "firing": 2},
                                         "keys": [{}, {}, {}]})
    code, out, _ = dry(engine, "alert", "drop", "big")
    assert code == EXIT_OK and "3 keys" in out
    answer("GET", "/api/v1/alerts/gone", {"code": "PRV-8030", "message": "no alert"}, 404)
    assert dry(engine, "alert", "drop", "gone")[0] == EXIT_REFUSED
    code, out, _ = dry(engine, "alert", "drop", "gone", "--if-exists")
    assert code == EXIT_OK and "IF EXISTS" in out
