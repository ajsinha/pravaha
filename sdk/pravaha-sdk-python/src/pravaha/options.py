"""Client options.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional, Union

from pravaha.consistency import Consistency
from pravaha.endpoint import Endpoint
from pravaha.errors import InvalidOptionsError


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
        if self.token is not None and not self.endpoint.tls:
            # Sending a bearer token over plaintext hands it to anyone on the path.
            # Refusing is less convenient than warning, and considerably safer.
            raise InvalidOptionsError(
                f"refusing to send a token over a plaintext connection to {self.endpoint}; "
                "use grpc+tls:// or remove the token"
            )

    @staticmethod
    def create(endpoint: Union[str, Endpoint], **kwargs: object) -> "ClientOptions":
        """Builds options, accepting a connection string or a parsed endpoint."""
        parsed = Endpoint.parse(endpoint) if isinstance(endpoint, str) else endpoint
        return ClientOptions(endpoint=parsed, **kwargs)  # type: ignore[arg-type]

    def __str__(self) -> str:
        # Deliberately omits the token. Options reaching a log line must not leak it.
        auth = ", authenticated" if self.token else ""
        return (
            f"ClientOptions[{self.endpoint}, consistency={self.default_consistency.value}, "
            f"buffer={self.subscriber_buffer_rows}, app={self.application_name}{auth}]"
        )
