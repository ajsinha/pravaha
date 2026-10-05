"""The CLI output the docs show is what the CLI prints: ``tools/cli_captures.py --check``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The check starts a real node from the ``pravaha-server`` jar, as the real-engine tests start theirs
from the build, and is skipped exactly when that is not built (or pyarrow, which ``queries`` needs,
is missing). The marker bookkeeping needs no node and always runs.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys

import pytest

SDK = pathlib.Path(__file__).resolve().parents[1]
TOOL = SDK / "tools" / "cli_captures.py"


def _tool():
    spec = importlib.util.spec_from_file_location("cli_captures", TOOL)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # a dataclass resolves its annotations through it
    spec.loader.exec_module(module)
    return module


def test_every_documented_block_is_declared_and_every_capture_is_documented():
    tool = _tool()
    declared = {c.id for c in tool.CAPTURES}
    found = [block for ids in tool.documented().values() for block in ids]
    assert set(found) <= declared, set(found) - declared
    assert declared <= set(found), declared - set(found)
    assert len(declared) >= 10


def test_rewrite_replaces_only_between_the_markers():
    tool = _tool()
    text = "before\n<!-- capture: status -->\nold\n<!-- /capture -->\nafter\n"
    assert tool.rewrite(text, {"status": "new\n"}) == (
        "before\n<!-- capture: status -->\nnew\n<!-- /capture -->\nafter\n")
    with pytest.raises(KeyError):
        tool.rewrite("<!-- capture: nope -->\n<!-- /capture -->", {})


def test_the_scratch_node_never_takes_a_reserved_port():
    tool = _tool()
    assert {18080, 19090, 5432, 8080, 55416} <= tool.RESERVED
    assert tool.free_port(set()) not in tool.RESERVED


def test_documented_output_matches_a_fresh_capture():
    pytest.importorskip("pyarrow", reason="`pravaha queries` needs the 'flight' extra")
    tool = _tool()
    try:
        blocks = tool.capture_all()
    except tool.NodeUnavailable as exc:
        pytest.skip(str(exc))
    problems = tool.stale(blocks)
    assert not problems, (
        "documented CLI output is stale; regenerate with `.venv/bin/python tools/cli_captures.py` "
        "in sdk/python:\n" + "\n".join(problems))
