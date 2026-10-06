"""Python client SDK for Project Pravaha.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The surface mirrors the Java SDK deliberately: the same concepts under the same
names, so a team running both does not have to hold two mental models. Wave 1 delivered the connection and result contracts; the Arrow Flight SQL transport
that implements them landed in Wave 7 (ADR-030). Import ``connect`` to use it -- it
needs the ``flight`` extra, because a client installed into somebody else's
environment should not drag pyarrow in unless it is going to talk to a server.
"""

from typing import Any, Optional

from pravaha.api import EngineApi
from pravaha.consistency import Consistency
from pravaha.debug import (
    DebugSession,
    DebugStep,
    Fixture,
    InputRow,
    OperatorFlow,
    StateEntry,
    StatePage,
    StateSlot,
    ViewDelta,
)
from pravaha.endpoint import Endpoint, HostPort
from pravaha.errors import (
    PravahaError,
    InvalidDocsBaseUrlError,
    MalformedEndpointError,
    MalformedTextError,
    InvalidOptionsError,
    InvalidTlsOptionsError,
    configure_docs_base,
    configure_docs_base_from_environment,
    docs_base_url,
    help_hint,
    help_line,
    help_url_for,
)
from pravaha.options import ClientOptions
from pravaha.rest import ApiError
from pravaha.tls import TlsOptions



def connect(*args: Any, **kwargs: Any) -> Any:
    """Connects to a Pravaha server. Needs the ``flight`` extra; see :mod:`pravaha.client`.

    Imported lazily so that ``import pravaha`` works without pyarrow -- the types and the
    connection string parser are useful on their own, and an unconditional import would
    make the optional dependency mandatory in practice.
    """
    from pravaha.client import connect as _connect

    return _connect(*args, **kwargs)


__all__ = [
    "ApiError",
    "ClientOptions",
    "connect",
    "Consistency",
    "DebugSession",
    "EngineApi",
    "DebugStep",
    "Endpoint",
    "Fixture",
    "InputRow",
    "OperatorFlow",
    "StateEntry",
    "StatePage",
    "StateSlot",
    "ViewDelta",
    "HostPort",
    "InvalidDocsBaseUrlError",
    "InvalidOptionsError",
    "InvalidTlsOptionsError",
    "MalformedEndpointError",
    "MalformedTextError",
    "PravahaError",
    "TlsOptions",
    "configure_docs_base",
    "configure_docs_base_from_environment",
    "docs_base_url",
    "help_hint",
    "help_line",
    "help_url_for",
]

def _source_version() -> Optional[str]:
    """The version in the pyproject.toml of the source tree this package was imported from, if any."""
    import re
    from pathlib import Path

    pyproject = Path(__file__).resolve().parent.parent / "pyproject.toml"
    try:
        text = pyproject.read_text(encoding="utf-8")
    except OSError:
        return None
    project = re.search(r"^\[project\]\s*$(.*?)(?=^\[|\Z)", text, re.M | re.S)
    if project is None or not re.search(r'^name\s*=\s*"pravaha"\s*$', project.group(1), re.M):
        return None
    found = re.search(r'^version\s*=\s*"([^"]+)"\s*$', project.group(1), re.M)
    return found.group(1) if found else None


def _installed_version() -> str:
    """The version of the wheel that is installed, read from its own metadata.

    It was a literal, "0.1.0", that deploy/release/set-version.sh never touched: the 0.1.1 wheel
    said 0.1.0. Read from the package metadata it cannot disagree with the wheel it came in.
    """
    # Run from a source checkout (an editable install, or the tree itself), the checkout's own
    # pyproject.toml is the truth: an editable install's metadata keeps the version it was installed
    # at, so after set-version.sh moved the tree on, `pravaha doctor` reported a mismatch with the
    # node built from the same tree. A wheel has no pyproject.toml beside the package.
    source = _source_version()
    if source is not None:
        return source
    try:
        from importlib.metadata import PackageNotFoundError, version
    except ImportError:  # pragma: no cover - Python < 3.8
        return "unknown"
    try:
        return version("pravaha")
    except PackageNotFoundError:
        # Imported from a source tree that was never installed: say so rather than guess.
        return "unknown"


__version__ = _installed_version()
