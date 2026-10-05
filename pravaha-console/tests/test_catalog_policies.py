"""Row filters and masks in the console (ADR-059 section 4): the policies reaching an object on its
page, and the admin Policies editor -- create, bind to an object or a tag, unbind, drop.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Driven through the real console against the fake engine's catalogue (``fake_catalog``): the engine
checks every expression and binding, and the console renders its answers and its refusals -- with
their codes -- and decides nothing itself.
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


def _create(admin, **fields):
    data = {"name": "region_scope", "kind": "ROW_FILTER", "expression": "region = session_attribute('region')",
            "column": "", "except_roles": "finance_admin", "description": ""}
    data.update(fields)
    return admin.post("/admin/policies", data=data).text


def test_the_editor_creates_binds_unbinds_and_drops_and_every_form_carries_the_token(admin, engine):
    page = admin.get("/admin/policies").text
    assert 'aria-current="page">Policies' in page and 'id="policies-none"' in page
    for form in ("bind-form", "policy-form"):
        section = page.split(f'id="{form}"', 1)[1].split("</form>", 1)[0]
        assert 'name="csrf_token"' in section, form

    made = _create(admin)
    assert "region_scope created; bind it to take effect." in made
    assert engine.governed.policies["public.default.region_scope"]["exceptRoles"] == ["finance_admin"]
    _create(admin, name="card_last4", kind="MASK", column="card", expression="'XXXX'", except_roles="")
    assert engine.governed.policies["public.default.card_last4"]["column"] == "card"

    bound = admin.post("/admin/policies/bind", data={"policy": "region_scope", "object": "public.sales.revenue",
                                                     "tag": ""}).text
    assert "region_scope is bound to public.sales.revenue." in bound
    assert 'data-binding="public.sales.revenue"' in bound
    admin.post("/admin/policies/bind", data={"policy": "card_last4", "object": "", "tag": "pii"})
    listed = admin.get("/admin/policies").text
    assert 'data-binding="TAG pii"' in listed
    for form in ("unbind", "drop"):
        section = listed.split(f'action="/admin/policies/{form}"', 1)[1].split("</form>", 1)[0]
        assert 'name="csrf_token"' in section, form

    refused = admin.post("/admin/policies/drop", data={"policy": "region_scope"}).text
    assert "PRV-7040" in refused and "public.default.region_scope" in engine.governed.policies
    admin.post("/admin/policies/unbind", data={"policy": "region_scope", "object": "public.sales.revenue", "tag": ""})
    assert engine.governed.policies["public.default.region_scope"]["bindings"] == []
    dropped = admin.post("/admin/policies/drop", data={"policy": "region_scope"}).text
    assert "region_scope is dropped." in dropped
    assert "public.default.region_scope" not in engine.governed.policies


def test_an_object_page_lists_the_policies_reaching_it_and_whether_each_applies(admin, engine):
    _create(admin)
    _create(admin, name="card_last4", kind="MASK", column="card", expression="'XXXX'", except_roles="")
    admin.post("/admin/policies/bind", data={"policy": "region_scope", "object": "public.sales.revenue", "tag": ""})
    admin.post("/admin/policies/bind", data={"policy": "card_last4", "object": "", "tag": "domain"})
    page = admin.get("/catalog/objects/public.sales.revenue").text
    assert 'id="object-policies"' in page
    assert 'data-policy="public.default.region_scope"' in page and 'data-policy="public.default.card_last4"' in page
    assert "region = session_attribute" in page and "TAG domain" in page and ">applies<" in page
    assert 'href="/admin/policies?object=public.sales.revenue"' in page
    quiet = admin.get("/catalog/objects/node.streams.txn").text
    assert 'id="object-policies-none"' in quiet


def test_the_engines_refusal_and_a_half_filled_form_are_shown_with_their_codes(admin, engine):
    assert "PRV-7038" in _create(admin, expression="RAND() < 0.5")
    assert "PRV-7038" in _create(admin, name="m", kind="MASK", column="", expression="'x'")
    assert "PRV-7037" in admin.post("/admin/policies/bind", data={"policy": "x", "object": "", "tag": ""}).text
    ann = _person(engine)
    assert "PRV-7033" in _create(ann, name="mine")
    assert engine.governed.policies == {}


def test_a_form_without_the_token_is_refused(engine):
    client = _app(engine)
    sign_in(client, keep_token=False)
    assert csrf_of(client.get("/admin/policies").text)
    refused = client.post("/admin/policies", data={"name": "f", "kind": "ROW_FILTER", "expression": "a = 1"})
    assert refused.status_code == 403 and engine.governed.policies == {}


def test_a_node_with_the_catalogue_off_says_so(admin, engine):
    engine.governed.on = False
    page = admin.get("/admin/policies")
    assert page.status_code == 409 and "PRV-7030" in page.text
