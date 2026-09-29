"""The project's file-size rule, held for the console's Python (CONSOLESIZE-1).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

``SourceFileSizeTest`` holds every Java file under 1,500 lines; nothing held the Python, and
``core/services.py`` passed the limit unnoticed. The same ceiling, for the same reason: a file
that long has almost always taken on more than one responsibility, and splitting it is a
decision best made at review, not years later. There is no allow-list; split, don't exempt.
The SDK's tests hold ``sdk/python`` to the same rule, and ``SourceFileSizeTest`` holds both
trees in the Maven gate.
"""
from __future__ import annotations

import pathlib

MAX_LINES = 1500
CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
#: Directories that are not ours to split: an interpreter environment, caches, vendored code.
SKIPPED = {".venv", "venv", "__pycache__", ".mypy_cache", ".ruff_cache", ".pytest_cache", "vendor",
           "build", "dist", "node_modules"}


def _sources() -> list[pathlib.Path]:
    return sorted(p for p in CONSOLE_ROOT.rglob("*.py")
                  if not SKIPPED.intersection(p.relative_to(CONSOLE_ROOT).parts))


def _lines(path: pathlib.Path) -> int:
    with path.open(encoding="utf-8") as handle:
        return sum(1 for _ in handle)


def test_no_console_python_file_exceeds_the_line_limit():
    over = [f"{p.relative_to(CONSOLE_ROOT)} ({n} lines)" for p in _sources() if (n := _lines(p)) > MAX_LINES]
    assert not over, (f"Python files must stay under {MAX_LINES} lines (project rule). Split the "
                      f"responsibilities rather than raising the limit: {over}")


def test_the_rule_actually_scans_something():
    # A path bug would make the check pass on nothing, which reads as enforcement and is none.
    names = {p.relative_to(CONSOLE_ROOT).as_posix() for p in _sources()}
    assert len(names) > 40
    assert {"core/services.py", "routes/base.py", "run_pravaha_web.py", "tests/test_product.py"} <= names
