"""Connection endpoint parsing.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence, Tuple

from pravaha.errors import MalformedEndpointError

DEFAULT_PORT = 9090
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

        grpc://host:9090              plaintext
        grpc+tls://host:9090          TLS
        grpc+tls://h1:9090,h2:9090    several nodes; the client picks and fails over
        host:9090                     scheme omitted, TLS assumed
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


def _malformed(original: str, why: str) -> MalformedEndpointError:
    return MalformedEndpointError(
        f"cannot parse endpoint '{original}': {why}. Expected {_ACCEPTED}."
    )


def _unused(_: Sequence[object]) -> None:  # pragma: no cover
    """Placeholder kept out of the public surface."""
