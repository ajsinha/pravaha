"""The catalogue's screens (ADR-059): objects with owners, descriptions and tags; one object with
its grants and what a person may do, and why; the admin grants editor.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Driven through the real console against the fake engine's catalogue (``fake_catalog``), which keeps
the engine's contract: the engine decides who sees and changes what, and the console renders its
answers and its refusals -- with their codes -- and decides nothing itself.
"""
from __future__ import annotations

import pytest

pytest.importorskip("fastapi.testclient")

from fake_engine import FakeEngine
from fake_identity import csrf_of, sign_in
from test_identity import _app, _person


@pytest.fixture
def engine():
    return FakeEngine()


@pytest.fixture
def admin(engine):
    client = _app(engine)
    sign_in(client)
    return client


def test_the_objects_tab_lists_namespaces_owners_descriptions_and_tags(admin):
    page = admin.get("/catalog?tab=objects").text
    assert 'data-object="public.sales.revenue"' in page
    assert "Revenue per region" in page and "domain=finance" in page and "USER admin" in page
    assert 'aria-current="page">Objects and grants' in page
    found = admin.get("/catalog?tab=objects&q=finance").text
    assert 'data-object="public.sales.revenue"' in found and 'data-object="public.default.big_txn"' not in found


def test_a_person_sees_only_what_the_engine_lets_them_use(admin, engine):
    ann = _person(engine)
    page = ann.get("/catalog?tab=objects").text
    assert 'data-object="public.default.big_txn"' in page
    assert 'data-object="public.sales.revenue"' not in page
    engine.governed.grants.append({"object": "public.sales", "privilege": "USE", "granteeType": "ROLE",
                                   "grantee": "analyst", "grantedBy": "admin", "grantedAt": "t"})
    assert 'data-object="public.sales.revenue"' in ann.get("/catalog?tab=objects").text


def test_one_object_shows_its_grants_what_you_may_do_and_why_someone_may(admin, engine):
    _person(engine)
    admin.post("/admin/grants", data={"object": "public.sales.revenue", "privileges": ["SELECT", "SUBSCRIBE"],
                                      "grantee_type": "ROLE", "grantee": "analyst"})
    page = admin.get("/catalog/objects/public.sales.revenue").text
    assert 'id="object-grants"' in page and "ROLE analyst" in page
    assert "the admin role, which holds every right" in page
    why = admin.get("/catalog/objects/public.sales.revenue?user=ann").text
    assert 'id="why-table"' in why and "grant SELECT on public.sales.revenue to ROLE analyst" in why
    assert "ann holds no MANAGE on public.sales.revenue" in why


def test_an_object_the_engine_will_not_show_is_not_found(admin, engine):
    ann = _person(engine)
    answer = ann.get("/catalog/objects/public.sales.revenue")
    assert answer.status_code == 404 and "PRV-7031" in answer.text


def test_the_grants_editor_grants_revokes_and_every_form_carries_the_token(admin, engine):
    page = admin.get("/admin/grants?object=public.sales.revenue").text
    assert 'aria-current="page">Grants' in page
    for form in ("grant-form", "describe-form", "namespace-form"):
        section = page.split(f'id="{form}"', 1)[1].split("</form>", 1)[0]
        assert 'name="csrf_token"' in section, form
    done = admin.post("/admin/grants", data={"object": "public.sales.revenue", "privileges": ["SELECT"],
                                             "grantee_type": "USER", "grantee": "ann"}).text
    assert "Granted SELECT on public.sales.revenue to USER ann." in done
    assert [g["privilege"] for g in engine.governed.grants] == ["SELECT"]
    assert 'data-grant="SELECT:USER:ann"' in done
    gone = admin.post("/admin/grants/revoke", data={"object": "public.sales.revenue", "privilege": "SELECT",
                                                    "grantee_type": "USER", "grantee": "ann"}).text
    assert "Revoked SELECT on public.sales.revenue from USER ann" in gone
    assert engine.governed.grants == []


def test_the_engines_refusal_is_shown_with_its_code(admin, engine):
    ann = _person(engine)
    page = ann.post("/admin/grants", data={"object": "public.default.big_txn", "privileges": ["SELECT"],
                                           "grantee_type": "USER", "grantee": "ann"}).text
    assert "PRV-7033" in page and engine.governed.grants == []
    wrong = admin.post("/admin/grants", data={"object": "public.sales.revenue", "privileges": ["WRITE"],
                                              "grantee_type": "ROLE", "grantee": "a"}).text
    assert "PRV-7032" in wrong


def test_namespaces_descriptions_and_tags_are_changed_through_the_engine(admin, engine):
    made = admin.post("/admin/namespaces", data={"name": "risk", "description": "Exposure"}).text
    assert "Namespace risk created" in made and engine.governed.objects["public.risk"]["description"] == "Exposure"
    admin.post("/admin/grants/describe", data={"object": "public.sales.revenue", "description": "Revenue, net",
                                               "tags": "domain=finance, tier=gold", "unset": "certified"})
    revenue = engine.governed.objects["public.sales.revenue"]
    assert revenue["description"] == "Revenue, net"
    assert revenue["tags"] == {"domain": "finance", "tier": "gold"}


def test_a_form_without_the_token_is_refused(engine):
    client = _app(engine)
    sign_in(client, keep_token=False)
    page = client.get("/admin/grants?object=public.sales.revenue").text
    assert csrf_of(page)
    refused = client.post("/admin/grants", data={"object": "public.sales.revenue", "privileges": ["SELECT"],
                                                 "grantee_type": "USER", "grantee": "ann"})
    assert refused.status_code == 403 and engine.governed.grants == []


def test_a_node_with_the_catalogue_off_says_so_on_every_screen(admin, engine):
    engine.governed.on = False
    assert "The catalogue is off on this engine" in admin.get("/catalog?tab=objects").text
    grants = admin.get("/admin/grants")
    assert grants.status_code == 409 and "PRV-7030" in grants.text
    assert "pravaha.catalog.enabled" in admin.get("/catalog/objects/anything").text
