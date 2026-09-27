"""Client option tests.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import pytest

from pravaha import ClientOptions, Consistency, InvalidOptionsError


def test_defaults_are_the_safe_choices_not_the_fastest_ones() -> None:
    # A client that quietly defaults to the loosest behaviour is how an application ends
    # up reporting numbers that do not reconcile, months before anyone notices.
    o = ClientOptions.create("host:19090")
    assert o.endpoint.tls is True
    assert o.default_consistency is Consistency.CONSISTENT
    assert o.subscriber_buffer_rows > 0
    assert o.conflate_on_overflow is True
    assert o.token is None


def test_overrides_apply() -> None:
    o = ClientOptions.create(
        "grpc+tls://host:19090",
        token="secret-token",
        connect_timeout_seconds=2.0,
        default_consistency=Consistency.LATEST,
        subscriber_buffer_rows=50,
        conflate_on_overflow=False,
        application_name="fraud-service",
    )
    assert o.token == "secret-token"
    assert o.connect_timeout_seconds == 2.0
    assert o.default_consistency is Consistency.LATEST
    assert o.subscriber_buffer_rows == 50
    assert o.conflate_on_overflow is False
    assert o.application_name == "fraud-service"


def test_refuses_to_send_a_token_over_plaintext() -> None:
    # Sending a bearer token over plaintext hands it to anyone on the path.
    with pytest.raises(InvalidOptionsError) as excinfo:
        ClientOptions.create("grpc://host:19090", token="t")
    assert "plaintext" in str(excinfo.value)
    assert "grpc+tls://" in str(excinfo.value)


def test_plaintext_without_a_token_is_allowed_for_local_development() -> None:
    assert ClientOptions.create("grpc://localhost:19090").endpoint.tls is False


def test_str_never_renders_the_token() -> None:
    o = ClientOptions.create("grpc+tls://h:19090", token="super-secret")
    assert "super-secret" not in str(o)
    assert "authenticated" in str(o)
    # repr is what a debugger and most log formatters actually call.
    assert "super-secret" not in repr(o)


@pytest.mark.parametrize(
    "kwargs",
    [
        {"subscriber_buffer_rows": 0},
        {"connect_timeout_seconds": 0},
        {"request_timeout_seconds": -1},
        {"application_name": "  "},
    ],
)
def test_rejects_nonsensical_settings(kwargs: dict) -> None:
    with pytest.raises(InvalidOptionsError):
        ClientOptions.create("grpc+tls://h:19090", **kwargs)


def test_consistency_matches_the_engine_modes() -> None:
    # Mirrors design section 17.3 and the Java SDK; a mode present in one and not the
    # other would be unreachable from that language.
    assert [c.name for c in Consistency] == ["LATEST", "CONSISTENT", "AS_OF", "AT_LEAST"]
