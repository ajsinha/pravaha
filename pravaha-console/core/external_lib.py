"""The console's half of ``external_lib`` (ADR-064): ``<external_lib>/python`` on ``sys.path``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

A deployment's own Python -- a console extension -- goes in ``external_lib/python`` and is
importable without installing anything. The root is ``paths.external_lib``, then
``PRAVAHA_EXTERNAL_LIB``, then ``$PRAVAHA_HOME/external_lib``, then the checkout's
``external_lib`` beside this console. Code there runs with the console's privileges.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Mapping, Optional

CHECKOUT_DEFAULT = Path(__file__).resolve().parents[2] / "external_lib"


def root(configured: Optional[str], environ: Optional[Mapping[str, str]] = None) -> Path:
    """The external_lib root in force."""
    env = os.environ if environ is None else environ
    for candidate in (configured, env.get("PRAVAHA_EXTERNAL_LIB")):
        if candidate and candidate.strip():
            return Path(candidate.strip()).expanduser()
    home = env.get("PRAVAHA_HOME", "").strip()
    return Path(home) / "external_lib" if home else CHECKOUT_DEFAULT


def add_to_sys_path(configured: Optional[str], environ: Optional[Mapping[str, str]] = None) -> Optional[Path]:
    """Puts ``<root>/python`` first on ``sys.path`` when it exists; the folder added, or None."""
    folder = (root(configured, environ) / "python").resolve()
    if not folder.is_dir():
        return None
    if str(folder) not in sys.path:
        sys.path.insert(0, str(folder))
    return folder
