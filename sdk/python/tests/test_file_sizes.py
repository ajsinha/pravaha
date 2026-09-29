"""The project's file-size rule, held for the SDK's Python (CONSOLESIZE-1).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

``SourceFileSizeTest`` holds every Java file under 1,500 lines; nothing held the Python, and
``pravaha/client.py`` had reached 1,782. The same ceiling here, with no allow-list: split,
don't exempt. The console's tests hold ``console/`` to the same rule, and
``SourceFileSizeTest`` holds both trees in the Maven gate.
"""

import pathlib

MAX_LINES = 1500
SDK_ROOT = pathlib.Path(__file__).resolve().parents[1]
#: Directories that are not ours to split: an interpreter environment, caches, build output.
SKIPPED = {".venv", "venv", "__pycache__", ".mypy_cache", ".ruff_cache", ".pytest_cache",
           "build", "dist", "node_modules"}


def _sources() -> "list[pathlib.Path]":
    return sorted(p for p in SDK_ROOT.rglob("*.py")
                  if not SKIPPED.intersection(p.relative_to(SDK_ROOT).parts)
                  and not any(part.endswith(".egg-info") for part in p.relative_to(SDK_ROOT).parts))


def _lines(path: pathlib.Path) -> int:
    with path.open(encoding="utf-8") as handle:
        return sum(1 for _ in handle)


def test_no_sdk_python_file_exceeds_the_line_limit():
    over = []
    for path in _sources():
        count = _lines(path)
        if count > MAX_LINES:
            over.append(f"{path.relative_to(SDK_ROOT)} ({count} lines)")
    assert not over, (f"Python files must stay under {MAX_LINES} lines (project rule). Split the "
                      f"responsibilities rather than raising the limit: {over}")


def test_the_rule_actually_scans_something():
    # A path bug would make the check pass on nothing, which reads as enforcement and is none.
    names = {p.relative_to(SDK_ROOT).as_posix() for p in _sources()}
    assert len(names) > 40
    assert {"pravaha/client.py", "pravaha/records.py", "tests/test_client.py"} <= names
