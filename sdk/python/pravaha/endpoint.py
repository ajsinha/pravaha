"""Connection endpoint parsing.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Sequence, Tuple

from pravaha.errors import MalformedEndpointError

DEFAULT_PORT = 19090
"""Default gRPC port, matching the engine's gateway configuration."""

_TLS_SCHEMES = frozenset({"grpc+tls", "grpcs", "https"})
_PLAINTEXT_SCHEMES = frozenset({"grpc", "http"})
_ACCEPTED = "grpc://host:port, grpc+tls://host:port, or a comma-separated list"


@dataclass(frozen=True)
class HostPort:
    """One host and port."""

    host: str
    port: int

    def __str__(self) -> str:
        return f"{self.host}:{self.port}"


@dataclass(frozen=True)
class Endpoint:
    """Where a client connects.

    Parsed and validated up front rather than on first use. A typo in a connection
    string should fail next to the code that supplied it, not fifteen minutes later
    inside a request where the traceback points somewhere unhelpful.

    Accepted forms::

        grpc://host:19090              plaintext
        grpc+tls://host:19090          TLS
        grpc+tls://h1:19090,h2:19090    several nodes; the client picks and fails over
        host:19090                     scheme omitted, TLS assumed
    """

    nodes: Tuple[HostPort, ...]
    tls: bool

    @staticmethod
    def parse(connection_string: str) -> "Endpoint":
        if not isinstance(connection_string, str):
            raise MalformedEndpointError(
                f"expected a string, got {type(connection_string).__name__}"
            )
        raw = connection_string.strip()
        if not raw:
            raise _malformed(connection_string, "it is empty")

        # TLS is assumed when the scheme is omitted. Defaulting to plaintext would let a
        # typo silently downgrade the connection, which is the wrong way to be wrong.
        tls = True
        remainder = raw
        if "://" in raw:
            scheme, remainder = raw.split("://", 1)
            scheme = scheme.lower()
            if scheme in _TLS_SCHEMES:
                tls = True
            elif scheme in _PLAINTEXT_SCHEMES:
                tls = False
            else:
                raise _malformed(connection_string, f"'{scheme}' is not a known scheme")

        if not remainder.strip():
            raise _malformed(connection_string, "it names no host")

        nodes = tuple(
            _parse_host_port(connection_string, part.strip())
            for part in remainder.split(",")
        )
        return Endpoint(nodes=nodes, tls=tls)

    def __str__(self) -> str:
        scheme = "grpc+tls" if self.tls else "grpc"
        return f"{scheme}://" + ",".join(str(n) for n in self.nodes)

    @staticmethod
    def from_config(config: Mapping[str, str]) -> "Endpoint":
        """Builds an endpoint from a config map, so an operator can turn TLS on or off by
        editing a file rather than a connection string embedded in code.

        Two forms, checked in this order:

        * ``endpoint`` -- a full connection string (``grpc://host:port`` or
          ``grpc+tls://host:port,...``). Its own scheme decides TLS, exactly as
          :meth:`parse` always has; ``tls.enabled`` is not consulted, because the operator
          already wrote the answer explicitly in the scheme.
        * ``hosts`` -- a bare comma-separated ``host:port`` list, no scheme. TLS is then
          decided by ``tls.enabled`` (``"true"``/``"false"``) if present. If it is absent,
          TLS is inferred from whether any certificate-material key (``tls.ca-certificate``,
          ``tls.client-certificate``, ``tls.client-key``, ``tls.trust-store``,
          ``tls.key-store``) is set, and defaults to ``False`` when none of that is present
          either -- deliberately not :meth:`parse`'s own "scheme omitted, TLS assumed"
          default for a bare connection string: ``hosts`` exists specifically to mirror the
          connector loader's ``PluginTls`` inference rule, where an operator who supplied
          nothing at all has not asked for TLS.

        **``tls.enabled``, when present, decides in both directions and is never overridden
        by inference.** Setting ``tls.ca-certificate`` does not switch TLS on behind an
        explicit ``tls.enabled=false`` -- the endpoint stays plaintext, and
        ``TlsOptions.from_config``'s own material is then refused by ``ClientOptions``'s
        constructor as certificate material configured for a plaintext endpoint, loudly,
        rather than the certificate being silently dropped or TLS being silently turned
        back on. This is the same rule the connector loader and the Java SDK's
        ``Endpoint.fromConfig`` enforce, so the three configuration surfaces cannot
        disagree about what an explicit ``tls.enabled`` means.
        """
        full = config.get("endpoint")
        if full:
            return Endpoint.parse(full)
        hosts = config.get("hosts")
        if not hosts:
            raise _malformed("<config>", "neither 'endpoint' nor 'hosts' is set")
        tls = _resolve_tls(config)
        return Endpoint.parse(("grpc+tls://" if tls else "grpc://") + hosts)


def _parse_host_port(original: str, text: str) -> HostPort:
    if not text:
        raise _malformed(original, "it contains an empty host entry")
    if ":" not in text:
        return HostPort(text, DEFAULT_PORT)
    host, _, port_text = text.rpartition(":")
    if not host.strip():
        raise _malformed(original, "the host is blank")
    try:
        port = int(port_text)
    except ValueError:
        raise _malformed(original, f"'{port_text}' is not a port number") from None
    if not 1 <= port <= 65535:
        raise _malformed(original, f"port {port} is outside 1-65535")
    return HostPort(host, port)


def _resolve_tls(config: Mapping[str, str]) -> bool:
    enabled = config.get("tls.enabled")
    if enabled:
        lowered = enabled.strip().lower()
        if lowered in ("true", "1", "yes"):
            return True
        if lowered in ("false", "0", "no"):
            return False
        raise _malformed("<config>", f"tls.enabled='{enabled}' is neither true nor false")
    # No explicit answer: infer from whatever certificate material was configured, the same
    # way PluginTls infers enablement on the connector side when it too has no explicit
    # tls.enabled to consult.
    return bool(
        config.get("tls.ca-certificate")
        or config.get("tls.client-certificate")
        or config.get("tls.client-key")
        or config.get("tls.trust-store")
        or config.get("tls.key-store")
    )


def _malformed(original: str, why: str) -> MalformedEndpointError:
    return MalformedEndpointError(
        f"cannot parse endpoint '{original}': {why}. Expected {_ACCEPTED}."
    )


def _unused(_: Sequence[object]) -> None:  # pragma: no cover
    """Placeholder kept out of the public surface."""
