"""Errors raised by the Pravaha client.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import os
from urllib.parse import urlparse


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
        """The help page for this code, or ``""`` when the deployment publishes none.

        DOCX-21: this was ``https://docs.pravaha.io/errors/PRV-nnnn``, and that host
        has never resolved. The base is configuration now -- the environment variable
        ``PRAVAHA_DOCS_BASE_URL``, the same setting the server spells
        ``pravaha.docs.base-url`` -- and unset means no URL rather than a dead one.
        Use :func:`help_hint` for the line to print when there is none.
        """
        return help_url_for(f"PRV-{self.code}")


DOCS_BASE_URL_VARIABLE = "PRAVAHA_DOCS_BASE_URL"
"""Where this client looks for the base of a help page. Unset means there is none."""

CONFIG_KEY = "pravaha.docs.base-url"
"""What the same setting is called on the server and in an embedded engine."""

_docs_base: str = ""


def configure_docs_base(raw: str | None) -> None:
    """Sets the help-page base, or clears it when ``raw`` is empty.

    Mirrors ``com.ash.messaging.pravaha.api.HelpUrls.configure``: a value that is not
    an absolute http or https URL is refused by name rather than concatenated with a
    code into something that only looks like a link.

    :raises InvalidDocsBaseUrlError: PRV-1029, if the value is not an http(s) URL.
    """
    global _docs_base
    written = (raw or "").strip()
    if not written:
        _docs_base = ""
        return
    parsed = urlparse(written)
    if not parsed.scheme:
        why = "it has no scheme, so it is a path and not a URL"
    elif parsed.scheme.lower() not in ("http", "https"):
        why = f"its scheme is '{parsed.scheme.lower()}' and a help page is fetched over http or https"
    elif not parsed.netloc:
        why = "it names no host"
    else:
        _docs_base = written if written.endswith("/") else written + "/"
        return
    raise InvalidDocsBaseUrlError(
        f"{DOCS_BASE_URL_VARIABLE} is '{written}', which is not an absolute http or https URL: "
        f"{why}. The client appends a code to it to build the help link a failure carries, so this "
        f"value would produce a link nobody can follow. Write the base of a page that resolves, for "
        f"example http://localhost:8088/help/errors/, or leave it unset: with no base the client "
        f"reports no URL at all and says to {help_hint('a code')}."
    )


def configure_docs_base_from_environment() -> None:
    """Reads ``PRAVAHA_DOCS_BASE_URL``. Called when a client is built, so a bad value is
    refused before it can appear inside the report of some other failure."""
    configure_docs_base(os.environ.get(DOCS_BASE_URL_VARIABLE))


def docs_base_url() -> str:
    """The configured base, ending in ``/``, or ``""`` when this deployment has none."""
    return _docs_base


def help_url_for(rendered_code: str) -> str:
    """``<base><code>``, or ``""`` when this deployment publishes no help pages."""
    return f"{_docs_base}{rendered_code}" if _docs_base else ""


def help_hint(rendered_code: str) -> str:
    """What to say instead of a link: where the code is written down offline."""
    return (
        f"look {rendered_code} up in the console's help under Errors, "
        "or in docs/TROUBLESHOOTING.md"
    )


def help_line(rendered_code: str) -> str:
    """The help URL when there is one, and otherwise how to resolve the code offline."""
    return help_url_for(rendered_code) or help_hint(rendered_code)


class InvalidDocsBaseUrlError(PravahaError):
    """``PRAVAHA_DOCS_BASE_URL`` is set to something that is not an http or https URL.

    The same ``PRV-1029`` the server raises for ``pravaha.docs.base-url`` (DOCX-21), so
    one code covers one mistake in both languages.
    """

    def __init__(self, message: str) -> None:
        super().__init__(1029, message)


class MalformedEndpointError(PravahaError):
    """A connection string could not be parsed."""

    def __init__(self, message: str) -> None:
        super().__init__(1030, message)


class InvalidOptionsError(PravahaError):
    """Client options are inconsistent or out of range."""

    def __init__(self, message: str) -> None:
        super().__init__(1031, message)


class InvalidTlsOptionsError(PravahaError):
    """TLS options are inconsistent -- a certificate without its key, both PEM and a keystore
    configured together, or disabled hostname verification combined with certificate material.

    A distinct code from ``InvalidOptionsError``, matching the Java SDK's
    ``TlsOptions``-specific ``PRV-1032`` rather than the general ``PRV-1031``: a team running
    both languages should find one page per code, not two meanings behind one.
    """

    def __init__(self, message: str) -> None:
        super().__init__(1032, message)
