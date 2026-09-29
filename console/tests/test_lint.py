"""The console passes its own lint, as part of its own tests (CONSOLERUFF-1).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

``make lint`` runs ruff over the console with the rule set ``pyproject.toml`` pins, as the SDK pins
its own. Run with the tests, as ``test_typecheck.py`` runs mypy, so a person who runs only
``pytest`` sees ruff's verdict too; skips, saying so, where ruff is not installed (it is in the
``dev`` extra).
"""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

import pytest

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]


def test_ruff_is_clean_over_the_console():
    if importlib.util.find_spec("ruff") is None:
        pytest.skip("ruff is not installed here; `pip install -e '.[dev]'` brings it (make lint)")
    result = subprocess.run([sys.executable, "-m", "ruff", "check", "--no-cache", "--output-format",
                             "concise", "core", "routes", "tests", "run_pravaha_web.py"],
                            cwd=CONSOLE_ROOT, capture_output=True, text=True, timeout=300, check=False)
    assert result.returncode == 0, result.stdout + result.stderr
