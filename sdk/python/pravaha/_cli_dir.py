"""The directory the command line keeps its token, contexts and assistant configuration in.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

``$PRAVAHA_CLI_DIR``, else ``$XDG_CONFIG_HOME/pravaha``, else ``~/.config/pravaha``.

ENVCONFIGDIR-1. This was ``$PRAVAHA_CONFIG_DIR`` until the server's ``pravaha.config.dir`` (ADR-064)
arrived: Spring binds an environment variable of that name to the server's setting, so a shell that
exported it for the command line and then started a server read its streams, lookups, sinks and
connections from the command line's directory. The old name is still read, after the new one, with
one warning on standard error per process.
"""

from __future__ import annotations

import os
import pathlib
import sys
from typing import Mapping, Optional

VARIABLE = "PRAVAHA_CLI_DIR"
DEPRECATED = "PRAVAHA_CONFIG_DIR"

_warned = False


def cli_dir(environ: Optional[Mapping[str, str]] = None) -> pathlib.Path:
    """``$PRAVAHA_CLI_DIR``, else (deprecated) ``$PRAVAHA_CONFIG_DIR``, else ``$XDG_CONFIG_HOME/pravaha``,
    else ``~/.config/pravaha``."""
    global _warned
    env = os.environ if environ is None else environ
    if env.get(VARIABLE):
        return pathlib.Path(env[VARIABLE])
    if env.get(DEPRECATED):
        if not _warned:
            _warned = True
            print(
                f"pravaha: {DEPRECATED} is deprecated as the command line's directory; use {VARIABLE}. "
                f"A server started from this shell reads {DEPRECATED} as its pravaha.config.dir.",
                file=sys.stderr,
            )
        return pathlib.Path(env[DEPRECATED])
    base = env.get("XDG_CONFIG_HOME")
    return (pathlib.Path(base) if base else pathlib.Path.home() / ".config") / "pravaha"
