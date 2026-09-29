"""The console type-checks, as part of its own tests (CATMYPY-1).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

``make typecheck`` runs mypy over the console, and the CI workflow runs that target -- but the gate
is Maven's, and a person running only ``pytest`` never saw mypy's verdict, which is how two errors
sat in ``routes/catalog_routes.py`` unnoticed. This runs the same command as ``make typecheck`` with
the tests, and skips, saying so, where mypy is not installed (it is in the ``dev`` extra).
"""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

import pytest

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]


def test_mypy_is_clean_over_the_console():
    if importlib.util.find_spec("mypy") is None:
        pytest.skip("mypy is not installed here; `pip install -e '.[dev]'` brings it (make typecheck)")
    result = subprocess.run([sys.executable, "-m", "mypy", "--no-color-output", "core", "routes",
                             "run_pravaha_web.py"],
                            cwd=CONSOLE_ROOT, capture_output=True, text=True, timeout=600, check=False)
    assert result.returncode == 0, result.stdout + result.stderr
