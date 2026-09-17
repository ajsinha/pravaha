"""TLS option tests.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from pravaha import ClientOptions, InvalidOptionsError, InvalidTlsOptionsError, TlsOptions
from pravaha.endpoint import Endpoint

SOME_PATH = Path("/tmp/does-not-need-to-exist.pem")


def test_defaults_verify_hostname_and_carry_no_material() -> None:
    tls = TlsOptions()
    assert tls.disable_hostname_verification is False
    assert tls.has_pem_material is False
    assert tls.has_keystore_material is False
    assert tls.is_default is True
    assert tls.override_hostname is None


def test_a_client_certificate_without_its_key_is_refused_naming_what_is_missing() -> None:
    # CFG-6's shape, on the client's side: PravahaFlightServer.encryptedWith null-checks
    # both halves before either is dereferenced, and this is the same discipline.
    with pytest.raises(InvalidTlsOptionsError, match="only the certificate"):
        TlsOptions(client_certificate=SOME_PATH)


def test_a_client_key_without_its_certificate_is_refused_naming_what_is_missing() -> None:
    with pytest.raises(InvalidTlsOptionsError, match="only the private key"):
        TlsOptions(client_key=SOME_PATH)


def test_giving_both_pem_and_a_keystore_is_refused_rather_than_preferring_one() -> None:
    with pytest.raises(InvalidTlsOptionsError, match="both PEM material"):
        TlsOptions(
            ca_certificate=SOME_PATH,
            trust_store=SOME_PATH,
            trust_store_password="changeit",
            trust_store_type="PKCS12",
        )


def test_a_matched_client_certificate_and_key_are_accepted() -> None:
    tls = TlsOptions(client_certificate=SOME_PATH, client_key=SOME_PATH)
    assert tls.client_certificate == SOME_PATH
    assert tls.client_key == SOME_PATH
    assert tls.has_pem_material is True


def test_trust_store_type_defaults_to_jks_and_pkcs12_is_accepted() -> None:
    tls = TlsOptions(trust_store=SOME_PATH, trust_store_password="secret", trust_store_type="pkcs12")
    assert tls.trust_store == SOME_PATH
    assert tls.trust_store_password == "secret"
    assert tls.trust_store_type == "PKCS12"
    assert tls.has_keystore_material is True


def test_an_unknown_store_type_is_refused() -> None:
    with pytest.raises(InvalidTlsOptionsError, match="JKS or PKCS12"):
        TlsOptions(trust_store=SOME_PATH, trust_store_type="PFX")


def test_disabling_hostname_verification_needs_an_explicit_flag_and_says_so_in_str() -> None:
    tls = TlsOptions(disable_hostname_verification=True)
    assert tls.disable_hostname_verification is True
    assert "hostname verification disabled" in str(tls)
    assert tls.is_default is False


def test_override_hostname_still_verifies_just_against_a_different_name() -> None:
    tls = TlsOptions(override_hostname="localhost")
    assert tls.override_hostname == "localhost"
    assert tls.disable_hostname_verification is False


def test_a_blank_override_hostname_is_refused() -> None:
    with pytest.raises(InvalidTlsOptionsError):
        TlsOptions(override_hostname=" ")


def test_combining_disabled_hostname_verification_with_certificate_material_is_refused() -> None:
    # pyarrow's own transport refuses this combination, discovered only by actually
    # connecting a client to a server on the Java side (JavaSdkTlsTest) -- a builder-only
    # test would not have caught it, which is why the same refusal is pinned here too.
    with pytest.raises(InvalidTlsOptionsError, match="do not compose"):
        TlsOptions(ca_certificate=SOME_PATH, disable_hostname_verification=True)


def test_str_never_renders_a_password() -> None:
    tls = TlsOptions(
        trust_store=SOME_PATH, trust_store_password="super-secret-password", trust_store_type="JKS"
    )
    assert "super-secret-password" not in str(tls)
    assert "super-secret-password" not in repr(tls)


def test_default_tls_options_are_carried_through_client_options() -> None:
    o = ClientOptions.create("grpc+tls://host:9090")
    assert o.tls.is_default is True


def test_tls_options_on_a_plaintext_endpoint_are_refused() -> None:
    tls = TlsOptions(ca_certificate=SOME_PATH)
    with pytest.raises(InvalidOptionsError, match="plaintext"):
        ClientOptions(endpoint=Endpoint.parse("grpc://host:9090"), tls=tls)


def test_default_tls_options_on_a_plaintext_endpoint_are_fine() -> None:
    o = ClientOptions(endpoint=Endpoint.parse("grpc://host:9090"), tls=TlsOptions())
    assert o.tls.is_default is True


def test_disabling_hostname_verification_on_a_tls_connection_does_not_waive_the_token_refusal() -> None:
    # The two checks are independent -- Endpoint.tls decides the token refusal, hostname
    # verification is a property of the handshake itself -- so a token is fine on a real
    # TLS endpoint regardless of hostname verification.
    tls = TlsOptions(disable_hostname_verification=True)
    o = ClientOptions(endpoint=Endpoint.parse("grpc+tls://host:9090"), tls=tls, token="t")
    assert o.token == "t"


def test_disabling_hostname_verification_on_a_plaintext_endpoint_is_still_refused_for_being_plaintext() -> None:
    tls = TlsOptions(disable_hostname_verification=True)
    with pytest.raises(InvalidOptionsError, match="plaintext"):
        ClientOptions(endpoint=Endpoint.parse("grpc://host:9090"), tls=tls, token="t")


def test_tls_options_from_config_reads_pem_material_from_a_plain_map() -> None:
    tls = TlsOptions.from_config(
        {
            "tls.ca-certificate": str(SOME_PATH),
            "tls.client-certificate": str(SOME_PATH),
            "tls.client-key": str(SOME_PATH),
        }
    )
    assert tls.has_pem_material is True
    assert tls.ca_certificate == SOME_PATH


def test_tls_options_from_config_reads_keystore_material_including_type_and_password() -> None:
    tls = TlsOptions.from_config(
        {
            "tls.trust-store": str(SOME_PATH),
            "tls.trust-store-password": "secret",
            "tls.trust-store-type": "pkcs12",
        }
    )
    assert tls.has_keystore_material is True
    assert tls.trust_store == SOME_PATH
    assert tls.trust_store_password == "secret"
    assert tls.trust_store_type == "PKCS12"


def test_tls_options_from_config_given_both_pem_and_keystore_is_refused() -> None:
    with pytest.raises(InvalidTlsOptionsError, match="not both"):
        TlsOptions.from_config({"tls.ca-certificate": str(SOME_PATH), "tls.trust-store": str(SOME_PATH)})


def test_tls_options_from_config_reads_disable_hostname_verification_insecure_as_a_boolean_string() -> None:
    tls = TlsOptions.from_config({"tls.disable-hostname-verification-insecure": "true"})
    assert tls.disable_hostname_verification is True


def test_tls_options_from_config_ignores_keys_it_does_not_recognise() -> None:
    tls = TlsOptions.from_config({"token": "irrelevant", "hosts": "irrelevant"})
    assert tls.is_default is True


def test_endpoint_from_config_prefers_a_full_connection_string_over_hosts_and_tls_enabled() -> None:
    # The scheme in 'endpoint' already answers the TLS question explicitly; tls.enabled is not
    # even consulted, let alone allowed to override it.
    e = Endpoint.from_config({"endpoint": "grpc://a:1", "tls.enabled": "true"})
    assert e.tls is False


def test_endpoint_from_config_with_hosts_and_explicit_tls_enabled_false_stays_plaintext_with_cert_material() -> (
    None
):
    # The rule the owner corrected in the connector loader's PluginTls: an explicit tls.enabled
    # must win in both directions, never overridden by inferring "on" from a leftover setting.
    e = Endpoint.from_config(
        {"hosts": "a:1", "tls.enabled": "false", "tls.ca-certificate": "/tmp/ca.pem"}
    )
    assert e.tls is False


def test_endpoint_from_config_with_hosts_and_explicit_tls_enabled_true_turns_tls_on_with_no_material() -> None:
    e = Endpoint.from_config({"hosts": "a:1", "tls.enabled": "true"})
    assert e.tls is True


def test_endpoint_from_config_with_hosts_and_no_tls_enabled_infers_tls_from_cert_material() -> None:
    with_material = Endpoint.from_config({"hosts": "a:1", "tls.trust-store": "/tmp/ts.jks"})
    assert with_material.tls is True

    # Unlike parse("a:1"), which assumes TLS when a scheme is simply omitted, the "hosts" form
    # mirrors PluginTls's inference rule: nothing said at all means TLS was not asked for.
    without_material = Endpoint.from_config({"hosts": "a:1"})
    assert without_material.tls is False


def test_endpoint_from_config_requires_either_endpoint_or_hosts() -> None:
    from pravaha.errors import MalformedEndpointError

    with pytest.raises(MalformedEndpointError, match="neither 'endpoint' nor 'hosts'"):
        Endpoint.from_config({"token": "x"})


def test_endpoint_from_config_rejects_a_tls_enabled_value_that_is_neither_true_nor_false() -> None:
    from pravaha.errors import MalformedEndpointError

    with pytest.raises(MalformedEndpointError, match="neither true nor false"):
        Endpoint.from_config({"hosts": "a:1", "tls.enabled": "maybe"})


def test_client_options_from_config_builds_a_fully_tls_configured_client_from_a_plain_map() -> None:
    opts = ClientOptions.from_config(
        {
            "hosts": "db01:9090",
            "tls.enabled": "true",
            "tls.trust-store": "/tmp/truststore.p12",
            "tls.trust-store-password": "secret",
            "tls.trust-store-type": "PKCS12",
            "token": "t",
            "application-name": "config-driven-app",
        }
    )
    assert opts.endpoint.tls is True
    assert opts.tls.trust_store == Path("/tmp/truststore.p12")
    assert opts.token == "t"
    assert opts.application_name == "config-driven-app"


def test_client_options_from_config_with_tls_enabled_false_turns_tls_off_even_with_leftover_cert_material() -> (
    None
):
    with pytest.raises(InvalidOptionsError, match="plaintext"):
        ClientOptions.from_config(
            {"hosts": "db01:9090", "tls.enabled": "false", "tls.ca-certificate": "/tmp/ca.pem"}
        )


def test_config_layered_lets_an_environment_variable_override_the_base_map(monkeypatch) -> None:
    from pravaha.config import layered

    monkeypatch.setenv("PRAVAHA_TLS_ENABLED", "false")
    merged = layered({"tls.enabled": "true"}, "PRAVAHA_")
    assert merged["tls.enabled"] == "false"


def test_config_layered_keeps_a_base_key_no_environment_variable_overrides(monkeypatch) -> None:
    from pravaha.config import layered

    monkeypatch.delenv("PRAVAHA_HOSTS", raising=False)
    merged = layered({"hosts": "a:1"}, "PRAVAHA_")
    assert merged["hosts"] == "a:1"
