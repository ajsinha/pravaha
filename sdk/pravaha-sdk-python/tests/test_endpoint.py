"""Endpoint parsing tests.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import pytest

from pravaha import Endpoint, MalformedEndpointError
from pravaha.endpoint import DEFAULT_PORT


def test_parses_scheme_host_and_port() -> None:
    e = Endpoint.parse("grpc+tls://db01.example.com:9090")
    assert e.tls is True
    assert len(e.nodes) == 1
    assert str(e.nodes[0]) == "db01.example.com:9090"


@pytest.mark.parametrize("text", ["grpc://localhost:9090", "http://localhost:9090"])
def test_plaintext_scheme_disables_tls(text: str) -> None:
    assert Endpoint.parse(text).tls is False


def test_tls_is_assumed_when_the_scheme_is_omitted() -> None:
    # The safe reading of an ambiguous input: defaulting to plaintext would mean a
    # typo silently downgrades the connection.
    assert Endpoint.parse("host:9090").tls is True


def test_port_defaults_when_omitted() -> None:
    assert Endpoint.parse("grpc://host").nodes[0].port == DEFAULT_PORT


def test_parses_a_list_of_nodes_for_failover() -> None:
    e = Endpoint.parse("grpc+tls://a:9090, b:9091 ,c:9092")
    assert len(e.nodes) == 3
    assert e.nodes[1].host == "b"
    assert e.nodes[2].port == 9092


def test_round_trips_through_str() -> None:
    text = "grpc+tls://a:9090,b:9091"
    assert Endpoint.parse(str(Endpoint.parse(text))) == Endpoint.parse(text)


@pytest.mark.parametrize(
    "text",
    ["", "   ", "grpc://", "ftp://host:1", "host:notaport", "host:0", "host:70000", "a:9090,,b:9090"],
)
def test_malformed_input_fails_at_construction_with_the_accepted_forms(text: str) -> None:
    # A typo should fail next to the code that supplied it, not fifteen minutes later
    # inside a request where the traceback points somewhere unhelpful.
    with pytest.raises(MalformedEndpointError) as excinfo:
        Endpoint.parse(text)
    assert "grpc+tls://host:port" in str(excinfo.value)
    assert excinfo.value.code == 1030
    assert excinfo.value.retryable is False


def test_non_string_input_is_rejected() -> None:
    with pytest.raises(MalformedEndpointError):
        Endpoint.parse(9090)  # type: ignore[arg-type]


def test_equality_is_by_value() -> None:
    assert Endpoint.parse("grpc://a:1") == Endpoint.parse("grpc://a:1")
    assert Endpoint.parse("grpc://a:1") != Endpoint.parse("grpc+tls://a:1")


def test_error_carries_a_help_url() -> None:
    with pytest.raises(MalformedEndpointError) as excinfo:
        Endpoint.parse("nope://x")
    assert excinfo.value.help_url.endswith("PRV-1030")
