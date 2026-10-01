"""Endpoint parsing tests.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import pytest

from pravaha import Endpoint, MalformedEndpointError
from pravaha.endpoint import DEFAULT_PORT


def test_parses_scheme_host_and_port() -> None:
    e = Endpoint.parse("grpc+tls://db01.example.com:19090")
    assert e.tls is True
    assert len(e.nodes) == 1
    assert str(e.nodes[0]) == "db01.example.com:19090"


@pytest.mark.parametrize("text", ["grpc://localhost:19090", "http://localhost:19090"])
def test_plaintext_scheme_disables_tls(text: str) -> None:
    assert Endpoint.parse(text).tls is False


def test_tls_is_assumed_when_the_scheme_is_omitted() -> None:
    # The safe reading of an ambiguous input: defaulting to plaintext would mean a
    # typo silently downgrades the connection.
    assert Endpoint.parse("host:19090").tls is True


def test_port_defaults_when_omitted() -> None:
    assert Endpoint.parse("grpc://host").nodes[0].port == DEFAULT_PORT


def test_parses_a_list_of_nodes_for_failover() -> None:
    e = Endpoint.parse("grpc+tls://a:19090, b:9091 ,c:9092")
    assert len(e.nodes) == 3
    assert e.nodes[1].host == "b"
    assert e.nodes[2].port == 9092


def test_round_trips_through_str() -> None:
    text = "grpc+tls://a:19090,b:9091"
    assert Endpoint.parse(str(Endpoint.parse(text))) == Endpoint.parse(text)


@pytest.mark.parametrize(
    "text",
    ["", "   ", "grpc://", "ftp://host:1", "host:notaport", "host:0", "host:70000", "a:19090,,b:19090"],
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
        Endpoint.parse(19090)  # type: ignore[arg-type]


def test_equality_is_by_value() -> None:
    assert Endpoint.parse("grpc://a:1") == Endpoint.parse("grpc://a:1")
    assert Endpoint.parse("grpc://a:1") != Endpoint.parse("grpc+tls://a:1")


def test_error_carries_no_help_url_until_a_deployment_configures_one() -> None:
    """DOCX-21. The base used to be the constant ``https://docs.pravaha.io/errors/``,
    on a host that has never resolved. Unset now means no URL, and the line a caller
    prints instead names two references that exist offline."""
    from pravaha import errors as _errors

    _errors.configure_docs_base(None)
    with pytest.raises(MalformedEndpointError) as excinfo:
        Endpoint.parse("nope://x")
    assert excinfo.value.help_url == ""
    assert _errors.help_line("PRV-1030") == (
        "look PRV-1030 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md"
    )

    try:
        _errors.configure_docs_base("http://localhost:8088/help/errors")
        assert excinfo.value.help_url == "http://localhost:8088/help/errors/PRV-1030"
    finally:
        _errors.configure_docs_base(None)


def test_a_docs_base_that_is_not_a_url_is_refused_by_name() -> None:
    from pravaha import errors as _errors

    with pytest.raises(_errors.InvalidDocsBaseUrlError) as excinfo:
        _errors.configure_docs_base("docs.pravaha.io/errors/")
    assert "PRV-1029" in str(excinfo.value)
    assert _errors.docs_base_url() == ""
