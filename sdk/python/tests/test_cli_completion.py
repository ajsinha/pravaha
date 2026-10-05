"""``pravaha completion bash|zsh|fish``: made from the parser, so every command and flag is in it.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess

import pytest

from pravaha.cli._app import build_parser
from pravaha.cli._completion import walk
from test_cli import home, run  # noqa: F401


def _script(shell: str) -> str:
    code, out, err = run("completion", shell)
    assert code == 0, err
    return out


def _every_path_and_flag():
    tree = walk(build_parser())
    for path, node in tree.items():
        for flag in node["opts"]:
            yield path, flag


@pytest.mark.parametrize("shell", ["bash", "zsh", "fish"])
def test_every_command_verb_and_visible_flag_is_in_the_script(home, shell):
    script = _script(shell)
    tree = walk(build_parser())
    assert len(tree) > 100
    for path in tree:
        if path:
            assert path[-1] in script, path
    for _, flag in _every_path_and_flag():
        word = flag if shell != "fish" else (flag[2:] if flag.startswith("--") else flag[1:])
        assert word in script, flag
    for choice in ("history", "CONFLATE", "DROP_OLDEST", "allow", "deny", "fish"):
        assert choice in script


def test_a_hidden_flag_is_not_offered(home):
    tree = walk(build_parser())
    assert "--param" not in tree[("register",)]["opts"]  # help=SUPPRESS: kept for old scripts only
    assert "--name" in tree[("register",)]["opts"]


def test_the_tree_is_the_parsers_own(home):
    parser = build_parser()
    commands = next(a for a in parser._actions if isinstance(a, argparse._SubParsersAction))
    tree = walk(parser)
    assert set(tree[()]["subs"]) == set(commands.choices)
    assert {"list", "describe", "declare"} == set(tree[("streams",)]["subs"])
    assert tree[("subscribe",)]["values"]["--overflow"] == ["CONFLATE", "DROP_OLDEST", "FAIL"]


def test_bash_script_is_valid_bash_and_completes(home, tmp_path):
    if not shutil.which("bash"):
        pytest.skip("no bash")
    path = tmp_path / "pravaha.bash"
    path.write_text(_script("bash"), encoding="utf-8")
    assert subprocess.run(["bash", "-n", str(path)], capture_output=True).returncode == 0
    probe = (f"source {path}; COMP_WORDS=(pravaha streams de); COMP_CWORD=2; _pravaha_complete; "
             'echo "${COMPREPLY[*]}"; COMP_WORDS=(pravaha replace --backfill ""); COMP_CWORD=3; '
             '_pravaha_complete; echo "${COMPREPLY[*]}"')
    done = subprocess.run(["bash", "-c", probe], capture_output=True, text=True)
    assert done.stdout.splitlines() == ["describe declare", "history none"], done.stderr


@pytest.mark.parametrize("shell", ["zsh", "fish"])
def test_zsh_and_fish_scripts_parse(home, tmp_path, shell):
    script = _script(shell)
    if shell == "zsh":
        assert "bashcompinit" in script and "complete -o filenames -F _pravaha_complete" in script
    else:
        assert "function __pravaha_path" in script and script.count("\nend\n") >= 2
        assert all(line.startswith(("complete ", "#", "function", " ", "end")) or not line
                   for line in script.splitlines())
    if not shutil.which(shell):
        pytest.skip(f"{shell} is not installed: only the plausibility checks ran")
    path = tmp_path / f"pravaha.{shell}"
    path.write_text(script, encoding="utf-8")
    assert subprocess.run([shell, "-n", str(path)], capture_output=True).returncode == 0


def test_an_unknown_shell_is_a_usage_error(home):
    code, _, err = run("completion", "tcsh")
    assert code == 2 and "invalid choice" in err
