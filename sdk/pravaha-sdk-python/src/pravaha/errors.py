"""Errors raised by the Pravaha client.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations


class PravahaError(Exception):
    """Base class for every Pravaha client failure.

    Carries the same stable ``PRV-nnnn`` codes the engine uses, so an operator
    searching for a code finds one page whether it surfaced in a server log or in
    a Python traceback. ``retryable`` exists so callers can write a sensible retry
    policy without parsing messages, which is what they will otherwise do.
    """

    def __init__(self, code: int, message: str, *, retryable: bool = False) -> None:
        self.code = code
        self.retryable = retryable
        super().__init__(f"PRV-{code}  {message}")

    @property
    def help_url(self) -> str:
        return f"https://docs.pravaha.io/errors/PRV-{self.code}"


class MalformedEndpointError(PravahaError):
    """A connection string could not be parsed."""

    def __init__(self, message: str) -> None:
        super().__init__(1030, message)


class InvalidOptionsError(PravahaError):
    """Client options are inconsistent or out of range."""

    def __init__(self, message: str) -> None:
        super().__init__(1031, message)
