"""TLS configuration.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping, Optional, Union

from pravaha.errors import InvalidTlsOptionsError

_STORE_TYPES = frozenset({"JKS", "PKCS12"})


@dataclass(frozen=True)
class TlsOptions:
    """How this client verifies a server's certificate and, for mutual TLS, presents its own.

    Without this, a client can only do TLS against a certificate the system's default
    trust store already trusts -- so it cannot reach a Pravaha server using a private CA
    or a self-signed certificate, which is most real deployments.

    Two independent forms, because both are what people actually have: PEM (a CA bundle,
    plus a client certificate and key for mutual TLS) and a JKS or PKCS12
    keystore/truststore with a password and a type, because that is what a JVM shop
    already has rather than something to export. Giving both is refused -- two settings
    both claiming to say how to trust the server, with no rule for which wins, is not a
    configuration to leave standing.

    A client certificate without its key, or a key without its certificate, is refused
    naming which is missing, matching ``PravahaFlightServer.encryptedWith``'s own fix on
    the server side of the same mistake (CFG-6): that server used to null-check only the
    certificate, so a key configured alone was read into a field and never used.

    Hostname verification is on by default. Disabling it entirely needs an explicit,
    named call -- ``disable_hostname_verification=True`` -- mirroring
    ``ClientOptions.allow_insecure_token``. ``override_hostname`` is the narrower
    alternative: the certificate still has to be properly signed and still has to name
    the given host, this only changes which name is checked against, for a test
    connecting to ``127.0.0.1`` against a certificate issued for ``localhost``.

    Protocol and cipher-suite selection is not offered. ``pyarrow.flight.FlightClient``
    exposes no hook for either -- neither does the Java SDK's transport, for the same
    reason -- and a setting this accepted and could not act on would be exactly the kind
    of knob that does nothing.
    """

    ca_certificate: Optional[Path] = None
    client_certificate: Optional[Path] = None
    client_key: Optional[Path] = None
    trust_store: Optional[Path] = None
    trust_store_password: Optional[str] = field(default=None, repr=False)
    trust_store_type: str = "JKS"
    key_store: Optional[Path] = None
    key_store_password: Optional[str] = field(default=None, repr=False)
    key_store_type: str = "JKS"
    disable_hostname_verification: bool = False
    override_hostname: Optional[str] = None

    def __post_init__(self) -> None:
        if (self.client_certificate is None) != (self.client_key is None):
            raise InvalidTlsOptionsError(
                "TLS needs both halves of a client certificate and got only "
                + ("the certificate" if self.client_certificate is not None else "the private key")
                + ". Pass client_certificate and client_key together, or neither -- a client "
                "given one of them cannot present the other, and connecting without a client "
                "certificate because half a setting was missing is how a deployment that asked "
                "for mutual TLS ends up without it."
            )
        pem = self.ca_certificate is not None or self.client_certificate is not None
        keystore = self.trust_store is not None or self.key_store is not None
        if self.disable_hostname_verification and (pem or keystore):
            # pyarrow's own transport refuses this combination -- disabling server verification
            # while also naming a root certificate or a client certificate -- for the same reason
            # Arrow's Java client does: disabling verification means nothing is checked against
            # anything, so the two settings do not compose.
            raise InvalidTlsOptionsError(
                "disable_hostname_verification=True was passed along with certificate material "
                "(ca_certificate/client_certificate/client_key/trust_store/key_store). Disabling "
                "verification means no certificate is checked against anything, so the two do not "
                "compose -- configure one or the other, not both."
            )
        if pem and keystore:
            raise InvalidTlsOptionsError(
                "both PEM material (ca_certificate/client_certificate/client_key) and a "
                "keystore (trust_store/key_store) are configured, with no rule for which one "
                "wins. Configure PEM files or a keystore, not both."
            )
        # object.__setattr__ because this dataclass is frozen: __post_init__ is the one place
        # that is allowed to fill in a derived value after construction.
        object.__setattr__(self, "trust_store_type", _require_store_type(self.trust_store_type))
        object.__setattr__(self, "key_store_type", _require_store_type(self.key_store_type))
        if self.override_hostname is not None and not self.override_hostname.strip():
            raise InvalidTlsOptionsError("override_hostname must not be blank")

    @property
    def has_pem_material(self) -> bool:
        return self.ca_certificate is not None or self.client_certificate is not None or self.client_key is not None

    @property
    def has_keystore_material(self) -> bool:
        return self.trust_store is not None or self.key_store is not None

    @property
    def is_default(self) -> bool:
        return (
            not self.has_pem_material
            and not self.has_keystore_material
            and not self.disable_hostname_verification
            and self.override_hostname is None
        )

    @staticmethod
    def create(
        *,
        ca_certificate: Union[str, Path, None] = None,
        client_certificate: Union[str, Path, None] = None,
        client_key: Union[str, Path, None] = None,
        trust_store: Union[str, Path, None] = None,
        trust_store_password: Optional[str] = None,
        trust_store_type: str = "JKS",
        key_store: Union[str, Path, None] = None,
        key_store_password: Optional[str] = None,
        key_store_type: str = "JKS",
        disable_hostname_verification: bool = False,
        override_hostname: Optional[str] = None,
    ) -> "TlsOptions":
        """Builds options, accepting strings or ``Path`` objects for every file."""
        return TlsOptions(
            ca_certificate=_as_path(ca_certificate),
            client_certificate=_as_path(client_certificate),
            client_key=_as_path(client_key),
            trust_store=_as_path(trust_store),
            trust_store_password=trust_store_password,
            trust_store_type=trust_store_type,
            key_store=_as_path(key_store),
            key_store_password=key_store_password,
            key_store_type=key_store_type,
            disable_hostname_verification=disable_hostname_verification,
            override_hostname=override_hostname,
        )

    def __str__(self) -> str:
        # Deliberately omits every password.
        shape = "pem" if self.has_pem_material else "keystore" if self.has_keystore_material else "default"
        insecure = ", hostname verification disabled" if self.disable_hostname_verification else ""
        return f"TlsOptions[{shape}{insecure}]"

    @staticmethod
    def from_config(config: Mapping[str, str]) -> "TlsOptions":
        """Builds options entirely from a config map -- a file an operator edits, not code.

        Recognised keys, all optional: ``tls.ca-certificate``, ``tls.client-certificate``,
        ``tls.client-key``, ``tls.trust-store``, ``tls.trust-store-password``,
        ``tls.trust-store-type``, ``tls.key-store``, ``tls.key-store-password``,
        ``tls.key-store-type``, ``tls.disable-hostname-verification-insecure``
        (``"true"``/``"false"``), and ``tls.override-hostname``. ``tls.enabled`` is read by
        :meth:`pravaha.endpoint.Endpoint.from_config`, not here -- it decides whether the
        endpoint itself is TLS at all, which this class has no way to change. Matches the
        Java SDK's ``TlsOptions.Builder.applyConfig`` key for key, so the same properties
        file structure (translated to whatever format each language's caller already uses)
        configures both.
        """
        kwargs: dict[str, Any] = {}
        if config.get("tls.ca-certificate"):
            kwargs["ca_certificate"] = config["tls.ca-certificate"]
        if config.get("tls.client-certificate"):
            kwargs["client_certificate"] = config["tls.client-certificate"]
        if config.get("tls.client-key"):
            kwargs["client_key"] = config["tls.client-key"]
        if config.get("tls.trust-store"):
            kwargs["trust_store"] = config["tls.trust-store"]
            if config.get("tls.trust-store-password"):
                kwargs["trust_store_password"] = config["tls.trust-store-password"]
            if config.get("tls.trust-store-type"):
                kwargs["trust_store_type"] = config["tls.trust-store-type"]
        if config.get("tls.key-store"):
            kwargs["key_store"] = config["tls.key-store"]
            if config.get("tls.key-store-password"):
                kwargs["key_store_password"] = config["tls.key-store-password"]
            if config.get("tls.key-store-type"):
                kwargs["key_store_type"] = config["tls.key-store-type"]
        disable = config.get("tls.disable-hostname-verification-insecure")
        if disable:
            kwargs["disable_hostname_verification"] = _truthy(disable)
        if config.get("tls.override-hostname"):
            kwargs["override_hostname"] = config["tls.override-hostname"]
        return TlsOptions.create(**kwargs)


def _truthy(value: str) -> bool:
    return value.strip().lower() in ("true", "1", "yes")


def _as_path(value: Union[str, Path, None]) -> Optional[Path]:
    return None if value is None else Path(value)


def _require_store_type(value: str) -> str:
    upper = (value or "").strip().upper()
    if upper not in _STORE_TYPES:
        raise InvalidTlsOptionsError(f"unknown store type '{value}'; use JKS or PKCS12")
    return upper
