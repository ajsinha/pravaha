"""``pravaha.api.EngineApi``: the HTTP API with no Flight and no pyarrow.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The verbs the identity endpoints need (PUT, PATCH, DELETE), a ``204`` that is an answer rather
than a parse error, a health document read from a ``503``, and a rebalance that is a dry run
unless asked otherwise.
"""

from __future__ import annotations

import pytest

from pravaha.api import EngineApi
from pravaha.rest import ApiError
from test_cli import answer, engine, last  # noqa: F401 -- the recording HTTP server


def test_put_patch_and_delete_reach_their_endpoints_and_a_204_is_none(engine):
    api = EngineApi(engine)
    answer("PUT", "/api/v1/users/a%20b/roles", {"username": "a b"})
    assert api.set_roles("a b", ["reader"]) == {"username": "a b"}
    assert (last()["method"], last()["path"], last()["body"]) == (
        "PUT", "/api/v1/users/a%20b/roles", {"roles": ["reader"]})
    answer("PUT", "/api/v1/users/bob/attributes", {"attributes": {"region": "EU"}})
    assert api.set_attributes("bob", {"region": "EU"}) == {"attributes": {"region": "EU"}}
    assert last()["body"] == {"attributes": {"region": "EU"}}
    answer("PATCH", "/api/v1/users/bob", {})
    api.update_user("bob", status="disabled")
    assert last()["body"] == {"status": "disabled"}
    answer("DELETE", "/api/v1/keys/k1", None, 204)
    assert api.revoke_key("k1") is None
    assert last()["method"] == "DELETE"


def test_health_is_read_from_a_503_rather_than_raised(engine):
    api = EngineApi(engine)
    answer("GET", "/actuator/health", {"status": "OUT_OF_SERVICE"}, 503)
    assert api.health() == {"status": "OUT_OF_SERVICE"}
    answer("GET", "/actuator/health", {"code": "PRV-9000", "message": "broken"}, 500)
    with pytest.raises(ApiError) as refused:
        api.health()
    assert refused.value.status == 500
    assert refused.value.body == {"code": "PRV-9000", "message": "broken"}


def test_a_rebalance_is_a_dry_run_unless_asked_otherwise(engine):
    api = EngineApi(engine)
    answer("POST", "/api/v1/lanes/rebalance", {"moves": []})
    api.rebalance_lanes()
    assert last()["path"] == "/api/v1/lanes/rebalance?dryRun=true"
    api.rebalance_lanes(dry_run=False)
    assert last()["path"] == "/api/v1/lanes/rebalance"


def test_wrapped_lists_are_unwrapped_and_the_token_is_sent(engine):
    api = EngineApi(engine, token="t", allow_insecure_token=True)
    answer("GET", "/api/v1/sessions", {"sessions": [{"id": "s1"}]})
    assert api.sessions(all_sessions=True) == [{"id": "s1"}]
    assert last()["path"] == "/api/v1/sessions?all=true"
    assert last()["authorization"] == "Bearer t"


def test_login_sends_the_credentials_and_logout_the_token(engine):
    answer("POST", "/api/v1/auth/login", {"token": "tok"})
    assert EngineApi(engine).login("ann", "pw")["token"] == "tok"
    answer("POST", "/api/v1/auth/logout", None, 204)
    EngineApi(engine, token="tok", allow_insecure_token=True).logout()
    assert last()["authorization"] == "Bearer tok"
