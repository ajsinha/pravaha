"""``pravaha``, the command line for a running Pravaha engine.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Built on this SDK and nothing else: reads, registrations, subscriptions, dead letters and the
debugger go through :class:`pravaha.client.Client` over Arrow Flight, and everything the node
answers over HTTP -- status, the catalogue, lanes, identity, audit -- through
:class:`pravaha.api.EngineApi`. The command line is the SDK's first consumer, so an awkward
corner of the client shows up here before a customer finds it.

Run it as ``pravaha`` (the console script this package installs) or ``python -m pravaha.cli``.
The offline commands -- planning and running SQL with no server -- belong to the Java tool
``pravaha-engine``. See ``docs/guides/CLI.md``.
"""

from pravaha.cli._app import build_parser, main, run
from pravaha.cli._common import EXIT_OK, EXIT_REFUSED, EXIT_UNREACHABLE, EXIT_USAGE

__all__ = [
    "EXIT_OK",
    "EXIT_REFUSED",
    "EXIT_UNREACHABLE",
    "EXIT_USAGE",
    "build_parser",
    "main",
    "run",
]
