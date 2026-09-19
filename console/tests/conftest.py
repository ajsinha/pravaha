"""Test setup shared by both console test files.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The console is tested against the Python SDK *in this checkout*, not whichever copy the
virtualenv happened to install. The console and the SDK change together -- a new engine
call lands in both in one commit -- and a test run that silently picked up an older SDK
from another checkout would be testing a pairing nobody ships.
"""
from __future__ import annotations

import pathlib
import sys

_SDK = pathlib.Path(__file__).resolve().parents[2] / "sdk" / "python"
if (_SDK / "pravaha" / "__init__.py").exists() and str(_SDK) not in sys.path:
    sys.path.insert(0, str(_SDK))
    for loaded in [m for m in sys.modules if m == "pravaha" or m.startswith("pravaha.")]:
        del sys.modules[loaded]
