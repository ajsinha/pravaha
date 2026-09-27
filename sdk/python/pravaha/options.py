"""Client options.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Optional, Union

from pravaha.consistency import Consistency
from pravaha.endpoint import Endpoint
from pravaha.errors import InvalidOptionsError
from pravaha.tls import TlsOptions


@dataclass(frozen=True)
class ClientOptions:
    """How a client connects and behaves.

    Immutable and validated at construction. Defaults are chosen so the
    zero-configuration case is the safe one: TLS on, a bounded subscriber buffer,
    and ``CONSISTENT`` reads. A client that quietly defaults to the loosest
    behaviour is how an application ends up reporting numbers that do not
    reconcile, months before anyone notices.
    """

    endpoint: Endpoint
    token: Optional[str] = field(default=None, repr=False)
    connect_timeout_seconds: float = 10.0
    request_timeout_seconds: float = 30.0
    default_consistency: Consistency = Consistency.CONSISTENT
    subscriber_buffer_rows: int = 10_000
    conflate_on_overflow: bool = True
    application_name: str = "pravaha-python-sdk"
    #: Permits a token over a plaintext connection. For a test against a loopback
    #: server, and for a deployment where TLS is terminated by a sidecar on the same
    #: host. Both are real; neither is the common case, which is why it has to be
    #: asked for by a name that says what is being given up.
    allow_insecure_token: bool = False
    #: How this client verifies the server's certificate and, for mutual TLS, presents
    #: its own. Configuring any real material here on a plaintext (``grpc://``) endpoint
    #: is refused: the endpoint's own scheme decides whether TLS is used at all, and a
    #: setting that looks like it did something and silently did not is exactly what
    #: this class exists to refuse.
    tls: TlsOptions = field(default_factory=TlsOptions)
    #: The engine's HTTP surface (``http://host:18080``), for the calls that have no Flight form:
    #: the stream catalogue, validation, plans, sinks, per-query descriptions and node status.
    #: A separate port from Flight, which is why it is a separate setting. Optional: a client
    #: that only queries and subscribes never needs it.
    http_url: Optional[str] = None

    def __post_init__(self) -> None:
        if self.connect_timeout_seconds <= 0:
            raise InvalidOptionsError(
                f"connect_timeout_seconds must be positive, got {self.connect_timeout_seconds}"
            )
        if self.request_timeout_seconds <= 0:
            raise InvalidOptionsError(
                f"request_timeout_seconds must be positive, got {self.request_timeout_seconds}"
            )
        if self.subscriber_buffer_rows < 1:
            raise InvalidOptionsError(
                f"subscriber_buffer_rows must be at least 1, got {self.subscriber_buffer_rows}"
            )
        if not self.application_name or not self.application_name.strip():
            raise InvalidOptionsError("application_name must not be blank")
        if not self.endpoint.tls and not self.tls.is_default:
            # Certificate material configured for a connection that will not use TLS at all --
            # the endpoint's own scheme decides that, and this setting has no way to change it.
            raise InvalidOptionsError(
                f"TLS options were configured ({self.tls}) but the endpoint {self.endpoint} is "
                "plaintext; use grpc+tls:// or remove the TLS options"
            )
        if self.http_url is not None and not (
            self.http_url.startswith("http://") or self.http_url.startswith("https://")
        ):
            raise InvalidOptionsError(
                f"http_url must be an http:// or https:// URL, got {self.http_url!r}"
            )
        if (
            self.token is not None
            and self.http_url is not None
            and self.http_url.startswith("http://")
            and not self.allow_insecure_token
        ):
            raise InvalidOptionsError(
                f"refusing to send a token over plaintext HTTP to {self.http_url}; use https://, "
                "remove the token, or pass allow_insecure_token=True if the engine is loopback"
            )
        if self.token is not None and not self.endpoint.tls and not self.allow_insecure_token:
            # Sending a bearer token over plaintext hands it to anyone on the path.
            # Refusing is less convenient than warning, and considerably safer.
            raise InvalidOptionsError(
                f"refusing to send a token over a plaintext connection to {self.endpoint}; "
                "use grpc+tls://, remove the token, or pass allow_insecure_token=True if "
                "the connection is loopback or TLS ends at a local sidecar"
            )

    @staticmethod
    def create(endpoint: Union[str, Endpoint], **kwargs: object) -> "ClientOptions":
        """Builds options, accepting a connection string or a parsed endpoint."""
        parsed = Endpoint.parse(endpoint) if isinstance(endpoint, str) else endpoint
        return ClientOptions(endpoint=parsed, **kwargs)  # type: ignore[arg-type]

    @staticmethod
    def from_config(config: Mapping[str, str]) -> "ClientOptions":
        """Builds options entirely from a config map -- the endpoint (including whether it is
        TLS at all, via :meth:`Endpoint.from_config`), every TLS detail (via
        :meth:`TlsOptions.from_config`), and the few other settings an operator commonly needs
        to change without a recompile: ``token``, ``allow-insecure-token``
        (``"true"``/``"false"``), and ``application-name``.

        :func:`pravaha.config.layered` is how ``config`` itself typically gets built, so an
        environment variable can override one key from a file without editing the file.
        """
        endpoint = Endpoint.from_config(config)
        tls = TlsOptions.from_config(config)
        kwargs: dict[str, Any] = {"tls": tls}
        if config.get("token"):
            kwargs["token"] = config["token"]
        allow_insecure = config.get("allow-insecure-token")
        if allow_insecure:
            kwargs["allow_insecure_token"] = allow_insecure.strip().lower() in ("true", "1", "yes")
        if config.get("application-name"):
            kwargs["application_name"] = config["application-name"]
        if config.get("http-url"):
            kwargs["http_url"] = config["http-url"]
        return ClientOptions(endpoint=endpoint, **kwargs)

    def __str__(self) -> str:
        # Deliberately omits the token. Options reaching a log line must not leak it.
        auth = ", authenticated" if self.token else ""
        return (
            f"ClientOptions[{self.endpoint}, consistency={self.default_consistency.value}, "
            f"buffer={self.subscriber_buffer_rows}, app={self.application_name}{auth}]"
        )
