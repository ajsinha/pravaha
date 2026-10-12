"""The tutorials' output is what the product prints: ``tools/tutorial_captures.py --check``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Each hands-on tutorial (``docs/tutorials/NN-*.md``) is played from a fresh scratch node by its scenario
(``tools/tutorials/tNN.py``) and every captured block compared with what the run printed. The check
starts real nodes from the ``pravaha-server`` jar, as the real-engine tests start theirs, and is
skipped exactly when that is not built (or pyarrow, which the CLI's Flight commands need, is missing).
The bookkeeping -- every marker is a captured step, every captured step has a marker, the scenarios'
scripts and data exist -- needs no node and always runs.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys

import pytest

SDK = pathlib.Path(__file__).resolve().parents[1]
TOOL = SDK / "tools" / "tutorial_captures.py"


def _tool():
    if "tutorial_captures" in sys.modules:
        return sys.modules["tutorial_captures"]
    spec = importlib.util.spec_from_file_location("tutorial_captures", TOOL)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # the scenarios import their step types by this name
    spec.loader.exec_module(module)
    return module


def _names() -> "list[str]":
    return _tool().scenarios()


def test_there_is_a_scenario_for_every_written_tutorial():
    tool = _tool()
    names = _names()
    assert len(names) >= 6, names
    written = sorted(p.name for p in tool.TUTORIALS.glob("[0-9][0-9]-*.md"))
    played = sorted(tool.load(n).DOC for n in names)
    assert set(played) <= set(written), set(played) - set(written)
    for name in names:
        assert tool.load(name).DOC.startswith(name[1:] + "-"), f"{name} plays {tool.load(name).DOC}"


@pytest.mark.parametrize("name", _names())
def test_every_marker_is_a_captured_step_and_every_captured_step_has_a_marker(name):
    tool = _tool()
    declared = tool.declared(name)
    assert len(declared) == len(set(declared)), f"{name} captures an id twice"
    assert all(i.startswith(name + "-") for i in declared), f"{name}: an id not named {name}-..."
    documented = tool.documented(name)
    assert set(documented) == set(declared), (
        f"{name}: markers without a step {sorted(set(documented) - set(declared))}; "
        f"steps without a marker {sorted(set(declared) - set(documented))}")
    assert len(documented) == len(set(documented)), f"{name}: a block is shown twice"


@pytest.mark.parametrize("name", _names())
def test_every_file_a_scenario_reads_is_in_the_repository(name):
    tool = _tool()
    scenario = tool.load(name)
    spec = scenario.NODE
    for source in [spec.config, *(s for s, _ in spec.files)]:
        if not source.startswith("@"):
            assert (tool.DATA / source).exists(), f"{name}: {source} is missing under docs/tutorials/data/"
    for step in [*getattr(scenario, "PRE", ()), *scenario.STEPS]:
        inner = getattr(step, "step", step)
        if isinstance(inner, tool.Py):
            assert (tool.DATA / name / inner.script).is_file(), f"{name}: no script {inner.script}"


def test_rewrite_replaces_only_between_the_markers():
    tool = _tool()
    text = "a\n<!-- capture: t01-x -->\nold\n<!-- /capture -->\nb\n"
    assert tool.rewrite(text, {"t01-x": "new\n"}) == "a\n<!-- capture: t01-x -->\nnew\n<!-- /capture -->\nb\n"
    with pytest.raises(KeyError):
        tool.rewrite("<!-- capture: t01-nope -->\n<!-- /capture -->", {})


@pytest.mark.parametrize("name", _names())
def test_the_tutorial_shows_what_a_fresh_run_prints(name):
    pytest.importorskip("pyarrow", reason="the CLI's Flight commands need the 'flight' extra")
    tool = _tool()
    try:
        blocks = tool.play(name)
    except tool.NodeUnavailable as exc:
        pytest.skip(str(exc))
    problems = tool.stale(name, blocks)
    assert not problems, (
        f"{name}'s captured output is stale; regenerate with `.venv/bin/python tools/tutorial_captures.py "
        f"--only {name}` in sdk/python:\n" + "\n".join(problems))
