"""Row filters and masks (ADR-059 section 4) from Python: ``EngineApi``'s calls and ``pravaha policy``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Against the recording HTTP server of ``test_cli``: what is pinned is the endpoint, query and body,
what prints, and that an unbinding or a drop does nothing without ``--yes``. What each endpoint
decides is pinned in ``pravaha-server``'s and ``pravaha-catalog``'s own tests.
"""

from __future__ import annotations

import json

import pytest

from pravaha.api import EngineApi
from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_USAGE
from pravaha.rest import ApiError
from test_cli import _Engine, answer, engine, home, last, run  # noqa: F401 -- the recording server

REGION = {
    "name": "acme.sales.region_scope",
    "kind": "ROW_FILTER",
    "column": None,
    "expression": "region = session_attribute('region')",
    "exceptRoles": ["finance_admin"],
    "owner": {"type": "USER", "name": "ops"},
    "description": "",
    "tags": {},
    "version": 1,
    "bindings": [{"object": "acme.sales.orders", "tag": None}, {"object": None, "tag": "pii"}],
}


# ---------------------------------------------------------------------------------- EngineApi


def test_policies_are_listed_shown_and_created(engine):
    api = EngineApi(engine)
    answer("GET", "/api/v1/catalog/policies", {"items": [REGION]})
    assert api.policies() == [REGION]
    assert last()["path"] == "/api/v1/catalog/policies"
    api.policies(on="sales.orders")
    assert last()["path"] == "/api/v1/catalog/policies?object=sales.orders"
    answer("GET", "/api/v1/catalog/policies/sales.region_scope", REGION)
    assert api.policy("sales.region_scope") == REGION
    answer("POST", "/api/v1/catalog/policies", REGION, 201)
    api.create_policy(
        "sales.card_last4", "MASK", "'XXXX' || RIGHT(card, 4)", column="card", except_roles=["ops"]
    )
    assert last()["body"] == {
        "name": "sales.card_last4",
        "kind": "MASK",
        "expression": "'XXXX' || RIGHT(card, 4)",
        "exceptRoles": ["ops"],
        "description": "",
        "column": "card",
    }


def test_bindings_reach_their_endpoints(engine):
    api = EngineApi(engine)
    answer("POST", "/api/v1/catalog/policies/region_scope/bindings", {"object": "acme.default.orders"}, 201)
    api.bind_policy("region_scope", on="orders")
    assert last()["body"] == {"object": "orders", "tag": None}
    api.bind_policy("region_scope", tag="domain=payments")
    assert last()["body"] == {"object": None, "tag": "domain=payments"}
    answer("DELETE", "/api/v1/catalog/policies/region_scope/bindings", {"unbound": True})
    assert api.unbind_policy("region_scope", tag="pii") == {"unbound": True}
    assert last()["path"] == "/api/v1/catalog/policies/region_scope/bindings?tag=pii"
    answer("DELETE", "/api/v1/catalog/policies/region_scope", None, 204)
    assert api.drop_policy("region_scope") is None
    assert last()["method"] == "DELETE"


def test_a_conflict_is_refused_with_its_code(engine):
    answer("DELETE", "/api/v1/catalog/policies/region_scope", {"code": "PRV-7040", "message": "still bound"}, 409)
    with pytest.raises(ApiError) as refused:
        EngineApi(engine).drop_policy("region_scope")
    assert refused.value.code == 7040


# ---------------------------------------------------------------------------------- the CLI


def test_policy_ls_and_show_print_where_each_is_bound(engine, home):
    answer("GET", "/api/v1/catalog/policies", {"items": [REGION]})
    code, out, _ = run("policy", "ls", "--http", engine)
    assert code == EXIT_OK
    assert "acme.sales.region_scope" in out and "finance_admin" in out
    assert "acme.sales.orders, TAG 'pii'" in out
    run("policy", "ls", "--on", "orders", "--http", engine)
    assert last()["path"] == "/api/v1/catalog/policies?object=orders"
    answer("GET", "/api/v1/catalog/policies/sales.region_scope", REGION)
    code, out, _ = run("policy", "show", "sales.region_scope", "--http", engine)
    assert "session_attribute" in out and "USER ops" in out
    code, out, _ = run("policy", "show", "sales.region_scope", "--http", engine, "--json")
    assert json.loads(out) == REGION


def test_create_filter_and_mask(engine, home):
    answer("POST", "/api/v1/catalog/policies", REGION, 201)
    code, out, _ = run(
        "policy", "create-filter", "sales.region_scope",
        "--as", "region = session_attribute('region')", "--except-role", "finance_admin",
        "--http", engine,
    )
    assert code == EXIT_OK and "nothing is narrowed until it is bound" in out
    assert last()["body"]["kind"] == "ROW_FILTER"
    assert last()["body"]["exceptRoles"] == ["finance_admin"]
    run("policy", "create-mask", "card_last4", "--column", "card", "--as", "'XXXX'", "--http", engine)
    assert last()["body"]["kind"] == "MASK" and last()["body"]["column"] == "card"
    calls = len(_Engine.calls)
    assert run("policy", "create-mask", "m", "--as", "'x'", "--http", engine)[0] == EXIT_USAGE
    assert run("policy", "create-filter", "f", "--http", engine)[0] == EXIT_USAGE
    assert len(_Engine.calls) == calls


def test_bind_needs_exactly_one_place(engine, home):
    assert run("policy", "bind", "f", "--http", engine)[0] == EXIT_USAGE
    assert run("policy", "bind", "f", "--on", "o", "--tag", "pii", "--http", engine)[0] == EXIT_USAGE
    assert _Engine.calls == []
    answer("POST", "/api/v1/catalog/policies/f/bindings", {"object": None, "tag": "pii"}, 201)
    code, out, _ = run("policy", "bind", "f", "--tag", "pii", "--http", engine)
    assert code == EXIT_OK and out == "bound f to TAG 'pii'\n"


def test_unbind_and_drop_change_nothing_without_yes(engine, home):
    code, out, _ = run("policy", "unbind", "f", "--on", "orders", "--http", engine)
    assert code == EXIT_OK and "would unbind f from orders" in out
    code, out, _ = run("policy", "drop", "f", "--http", engine)
    assert code == EXIT_OK and "would drop f" in out
    assert _Engine.calls == []
    answer(
        "DELETE",
        "/api/v1/catalog/policies/f/bindings",
        {"policy": "acme.default.f", "target": "acme.default.orders", "unbound": True},
    )
    code, out, _ = run("policy", "unbind", "f", "--on", "orders", "--yes", "--http", engine)
    assert out == "acme.default.f unbound acme.default.orders\n"
    answer("DELETE", "/api/v1/catalog/policies/f", None, 204)
    code, out, _ = run("policy", "drop", "f", "--yes", "--http", engine)
    assert code == EXIT_OK and out == "dropped f\n"


def test_a_refusal_exits_one_with_its_code(engine, home):
    answer("POST", "/api/v1/catalog/policies", {"code": "PRV-7038", "message": "RAND() is refused"}, 400)
    code, _, err = run("policy", "create-filter", "f", "--as", "RAND() < 1", "--http", engine)
    assert code == EXIT_REFUSED and "PRV-7038" in err
