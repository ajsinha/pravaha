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

_CONSOLE = pathlib.Path(__file__).resolve().parents[1]
if str(_CONSOLE) not in sys.path:
    # `core`, `routes` and `run_pravaha_web` import from here, in every test file and in the
    # shared fake engine, whichever of them pytest happens to import first.
    sys.path.insert(0, str(_CONSOLE))

_SDK = pathlib.Path(__file__).resolve().parents[2] / "sdk" / "python"
if (_SDK / "pravaha" / "__init__.py").exists() and str(_SDK) not in sys.path:
    sys.path.insert(0, str(_SDK))
    for loaded in [m for m in sys.modules if m == "pravaha" or m.startswith("pravaha.")]:
        del sys.modules[loaded]

# ADR-058 phase 3: the assistant's configuration, usage ledger and log default to the SDK's
# directory (~/.config/pravaha). No test reads or writes a person's own: every console a test
# builds without naming its own files gets this empty directory instead.
import os
import tempfile

os.environ["PRAVAHA_CONFIG_DIR"] = tempfile.mkdtemp(prefix="pravaha-console-tests-")
os.environ.pop("PRAVAHA_ASSIST_CONFIG", None)

# The browser tests' fixtures -- a Chrome (skipping when there is none), a console on a real
# port, a fresh tab -- available to every test module without each importing them.
from browser_harness import chrome, console, page  # noqa: F401
