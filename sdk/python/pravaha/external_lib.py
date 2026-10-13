"""The ``external_lib`` root's Python half (ADR-064): ``<external_lib>/python`` on ``sys.path``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A deployment's own Python -- a console extension, a CLI helper -- goes in ``external_lib/python``
and is importable without installing anything. The root is, first to last: what the caller names
(the console's ``paths.external_lib``), ``PRAVAHA_EXTERNAL_LIB``, ``$PRAVAHA_HOME/external_lib``,
then ``./external_lib``. A missing folder is fine. Code there runs with the privileges of the
process that imports it: put nothing there you would not run.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Mapping, Optional, Union


def root(named: Union[str, Path, None] = None, environ: Optional[Mapping[str, str]] = None) -> Path:
    """The external_lib root in force."""
    env = os.environ if environ is None else environ
    for candidate in (named, env.get("PRAVAHA_EXTERNAL_LIB")):
        if candidate is not None and str(candidate).strip():
            return Path(str(candidate).strip()).expanduser()
    home = env.get("PRAVAHA_HOME", "").strip()
    return Path(home) / "external_lib" if home else Path("external_lib")


def add_to_sys_path(
    named: Union[str, Path, None] = None, environ: Optional[Mapping[str, str]] = None
) -> Optional[Path]:
    """Puts ``<root>/python`` first on ``sys.path`` when it exists; the folder added, or None."""
    folder = (root(named, environ) / "python").resolve()
    if not folder.is_dir():
        return None
    if str(folder) not in sys.path:
        sys.path.insert(0, str(folder))
    return folder
