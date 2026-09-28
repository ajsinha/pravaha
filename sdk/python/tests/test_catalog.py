"""The catalogue (ADR-059) from Python: ``EngineApi``'s calls and the ``pravaha`` commands.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Against the recording HTTP server of ``test_cli``: what is pinned is the right endpoint, query and
body, what prints, and that a revocation or a change of owner does nothing without ``--yes``. What
each endpoint decides is pinned in ``pravaha-server``'s and ``pravaha-catalog``'s own tests.
"""

from __future__ import annotations

import json

import pytest

from pravaha.api import EngineApi
from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_USAGE
from pravaha.rest import ApiError
from test_cli import _Engine, answer, engine, home, last, run  # noqa: F401 -- the recording server

REVENUE = {
    "name": "acme.sales.revenue",
    "kind": "VIEW",
    "engineName": "revenue",
    "namespace": "acme.sales",
    "owner": {"type": "USER", "name": "ops"},
    "description": "Revenue per region",
    "tags": {"domain": "finance", "certified": ""},
    "version": 4,
}


# ---------------------------------------------------------------------------------- EngineApi


def test_objects_search_and_namespaces_are_listed_unwrapped(engine):
    api = EngineApi(engine)
    answer("GET", "/api/v1/catalog/objects", {"items": [REVENUE]})
    assert api.catalog_objects(namespace="acme.sales", kind="VIEW") == [REVENUE]
    assert last()["path"] == "/api/v1/catalog/objects?namespace=acme.sales&kind=VIEW"
    assert api.catalog_search("finance") == [REVENUE]
    assert last()["path"] == "/api/v1/catalog/objects?q=finance"
    answer("GET", "/api/v1/catalog/namespaces", {"items": [{"name": "acme.sales"}]})
    assert api.catalog_namespaces() == [{"name": "acme.sales"}]


def test_an_object_is_named_as_one_path_segment(engine):
    api = EngineApi(engine)
    answer("GET", "/api/v1/catalog/objects/acme.sales.revenue", {"object": REVENUE, "grants": []})
    assert api.catalog_object("acme.sales.revenue")["object"] == REVENUE
    answer("GET", "/api/v1/catalog/objects/%2A", {"object": {"name": "*"}})
    api.catalog_object("*")
    assert last()["path"] == "/api/v1/catalog/objects/%2A"


def test_changes_grants_revocations_and_access_reach_their_endpoints(engine):
    api = EngineApi(engine)
    answer("PATCH", "/api/v1/catalog/objects/revenue", REVENUE)
    api.change_catalog_object(
        "revenue",
        description="Revenue per region",
        set_tags={"domain": "finance"},
        unset_tags=["draft"],
        owner=("ROLE", "finance_data"),
        namespace="sales",
    )
    assert last()["body"] == {
        "description": "Revenue per region",
        "setTags": {"domain": "finance"},
        "unsetTags": ["draft"],
        "owner": {"type": "ROLE", "name": "finance_data"},
        "namespace": "sales",
    }
    answer("POST", "/api/v1/catalog/namespaces", {"name": "acme.sales"}, 201)
    api.create_namespace("sales", description="Order-to-cash")
    assert last()["body"] == {"name": "sales", "description": "Order-to-cash", "ifNotExists": False}
    answer("POST", "/api/v1/catalog/grants", {"items": [{"privilege": "SELECT"}]}, 201)
    assert api.grant("sales.revenue", ["SELECT"], "ROLE", "analyst") == [{"privilege": "SELECT"}]
    assert last()["body"] == {
        "object": "sales.revenue",
        "privileges": ["SELECT"],
        "granteeType": "ROLE",
        "grantee": "analyst",
    }
    answer("DELETE", "/api/v1/catalog/grants", None, 204)
    assert api.revoke("sales.revenue", ["SELECT", "SUBSCRIBE"], "ROLE", "analyst") is None
    assert last()["path"] == (
        "/api/v1/catalog/grants?object=sales.revenue&privileges=SELECT%2CSUBSCRIBE"
        "&granteeType=ROLE&grantee=analyst"
    )
    answer("GET", "/api/v1/catalog/grants", {"items": []})
    api.grants(grantee_type="ROLE", grantee="analyst")
    assert last()["path"] == "/api/v1/catalog/grants?granteeType=ROLE&grantee=analyst"
    answer("GET", "/api/v1/catalog/access", {"user": "ana", "privileges": []})
    api.access("ana", "sales.revenue")
    assert last()["path"] == "/api/v1/catalog/access?user=ana&object=sales.revenue"


def test_a_catalogue_that_is_off_is_refused_with_its_code(engine):
    answer("GET", "/api/v1/catalog/namespaces", {"code": "PRV-7030", "message": "off"}, 409)
    with pytest.raises(ApiError) as refused:
        EngineApi(engine).catalog_namespaces()
    assert refused.value.code == 7030 and "PRV-7030" in str(refused.value)


# ---------------------------------------------------------------------------------- the CLI


def test_catalog_ls_and_search_print_owners_and_tags(engine, home):
    answer("GET", "/api/v1/catalog/objects", {"items": [REVENUE]})
    code, out, _ = run("catalog", "ls", "--http", engine)
    assert code == EXIT_OK
    assert "acme.sales.revenue" in out and "USER ops" in out and "domain=finance, certified" in out
    code, out, _ = run("catalog", "search", "finance", "--http", engine)
    assert last()["path"] == "/api/v1/catalog/objects?q=finance" and "acme.sales.revenue" in out
    code, out, _ = run("catalog", "ls", "--kind", "VIEW", "--http", engine, "--json")
    assert json.loads(out) == [dict(REVENUE, ownerText="USER ops", tagText="domain=finance, certified")]


def test_catalog_show_prints_grants_and_what_you_may_do(engine, home):
    answer(
        "GET",
        "/api/v1/catalog/objects/sales.revenue",
        {
            "object": REVENUE,
            "grants": [{"privilege": "SELECT", "granteeType": "ROLE", "grantee": "analyst", "grantedBy": "ops"}],
            "access": {"privileges": [{"privilege": "SELECT", "allowed": True}, {"privilege": "MANAGE", "allowed": False}]},
        },
    )
    code, out, _ = run("catalog", "show", "sales.revenue", "--http", engine)
    assert code == EXIT_OK
    assert "Revenue per region" in out and "analyst" in out
    assert "you may: SELECT" in out and "MANAGE" not in out.split("you may:")[1]


def test_grant_and_access_why(engine, home):
    answer("POST", "/api/v1/catalog/grants", {"items": [{"privilege": "SELECT", "object": "acme.sales.revenue"},
                                                        {"privilege": "SUBSCRIBE", "object": "acme.sales.revenue"}]})
    code, out, _ = run("grant", "select,subscribe", "sales.revenue", "--role", "analyst", "--http", engine)
    assert code == EXIT_OK
    assert out == "granted SELECT, SUBSCRIBE on acme.sales.revenue to ROLE analyst\n"
    assert last()["body"]["privileges"] == ["SELECT", "SUBSCRIBE"]
    answer(
        "GET",
        "/api/v1/catalog/access",
        {
            "object": "acme.sales.revenue",
            "user": "ana",
            "privileges": [
                {"privilege": "SELECT", "allowed": True, "via": ["grant SELECT on acme.sales to ROLE analyst"]},
                {"privilege": "SUBSCRIBE", "allowed": False, "refusal": "ana holds no SUBSCRIBE"},
            ],
        },
    )
    code, out, _ = run("access", "why", "ana", "sales.revenue", "--http", engine)
    assert code == EXIT_OK
    assert "grant SELECT on acme.sales to ROLE analyst" in out and "ana holds no SUBSCRIBE" in out


def test_revoke_and_owner_change_nothing_without_yes(engine, home):
    code, out, _ = run("revoke", "SELECT", "sales.revenue", "--role", "analyst", "--http", engine)
    assert code == EXIT_OK and "would revoke SELECT on sales.revenue from ROLE analyst" in out
    code, out, _ = run("catalog", "owner", "sales.revenue", "--user", "ana", "--http", engine)
    assert code == EXIT_OK and "would give sales.revenue to USER ana" in out
    assert _Engine.calls == []
    answer("DELETE", "/api/v1/catalog/grants", None, 204)
    assert run("revoke", "SELECT", "sales.revenue", "--role", "analyst", "--yes", "--http", engine)[0] == EXIT_OK
    assert last()["method"] == "DELETE"
    answer("PATCH", "/api/v1/catalog/objects/sales.revenue", REVENUE)
    run("catalog", "owner", "sales.revenue", "--user", "ana", "--yes", "--http", engine)
    assert last()["body"] == {"owner": {"type": "USER", "name": "ana"}}


def test_comment_tag_move_and_create_namespace(engine, home):
    answer("PATCH", "/api/v1/catalog/objects/revenue", REVENUE)
    run("catalog", "comment", "revenue", "Revenue per region", "--http", engine)
    assert last()["body"] == {"description": "Revenue per region"}
    run("catalog", "tag", "revenue", "domain=finance", "certified", "--unset", "draft", "--http", engine)
    assert last()["body"] == {"setTags": {"domain": "finance", "certified": ""}, "unsetTags": ["draft"]}
    run("catalog", "move", "revenue", "--namespace", "sales", "--http", engine)
    assert last()["body"] == {"namespace": "sales"}
    answer("POST", "/api/v1/catalog/namespaces", {"name": "acme.sales", "owner": {"type": "USER", "name": "ops"}})
    code, out, _ = run("catalog", "create-namespace", "sales", "--comment", "Order-to-cash", "--http", engine)
    assert out == "created acme.sales, owned by USER ops\n"


def test_who_to_grant_to_is_one_of_role_or_user(engine, home):
    assert run("grant", "SELECT", "revenue", "--http", engine)[0] == EXIT_USAGE
    assert run("grant", "SELECT", "revenue", "--role", "a", "--user", "b", "--http", engine)[0] == EXIT_USAGE
    assert run("grant", "--http", engine)[0] == EXIT_USAGE
    assert run("grants", "--http", engine)[0] == EXIT_USAGE
    assert _Engine.calls == []


def test_a_refusal_exits_one_with_its_code(engine, home):
    answer("POST", "/api/v1/catalog/grants", {"code": "PRV-7033", "message": "ana may not change it"}, 403)
    code, _, err = run("grant", "SELECT", "revenue", "--user", "bob", "--http", engine)
    assert code == EXIT_REFUSED and "PRV-7033" in err
