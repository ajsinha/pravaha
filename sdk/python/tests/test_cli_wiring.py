"""Every ``pravaha`` command, walked: its help, its examples, and its usage error.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The command list is the parser's own (``_completion.walk``), so a command added tomorrow is held
to the same rules without anybody remembering to list it here: ``--help`` exits 0 and ends with
``examples:``; every example is parsed by the real parser, so one naming a flag the command does
not have fails here; a flag the command does not know exits 2; so does a command that is not one.
"""

from __future__ import annotations

import shlex

import pytest

from pravaha.cli._app import build_parser
from pravaha.cli._completion import walk
from pravaha.cli._examples import EXAMPLES
from test_cli import home, run  # noqa: F401

PATHS = sorted(" ".join(path) for path in walk(build_parser()) if path)
_SHELL = {"|", "||", "&&", ">", ">>", "<(", ")", ";"}


def _invocation(line: str) -> "list[str]":
    """The ``pravaha ...`` words of an example line, without the shell around them."""
    lexer = shlex.shlex(line, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    lexer.commenters = "#"
    words = list(lexer)
    start = words.index("pravaha") if "pravaha" in words else len(words)
    out = []
    for word in words[start + 1:]:
        if word in _SHELL or word.startswith((">", "|", "<(")):
            break
        out.append(word)
    return out


def test_the_walk_finds_every_command_and_verb():
    assert len(PATHS) > 100
    assert {"doctor", "completion", "streams describe", "alert create", "debug fixture"} <= set(PATHS)


def test_every_command_has_examples_and_none_is_for_a_command_that_does_not_exist():
    assert set(EXAMPLES) == set(PATHS), (set(PATHS) ^ set(EXAMPLES))
    for path, lines in EXAMPLES.items():
        assert 1 <= len(lines) <= 3, path


@pytest.mark.parametrize("path", PATHS)
def test_help_exits_zero_and_ends_with_examples(home, path):
    code, out, err = run(*path.split(), "--help")
    assert code == 0, err
    assert "\nexamples:\n  " in out, path
    assert out.index("examples:") > out.index("options:" if "options:" in out else "usage:")


@pytest.mark.parametrize("path", PATHS)
def test_every_example_is_one_the_parser_takes(path):
    parser = build_parser()
    for line in EXAMPLES[path]:
        words = _invocation(line)
        if not words:  # `pravaha-engine run --help`: another program's
            assert "pravaha-engine" in line, line
            continue
        assert " ".join(words).startswith(path.split()[0]), (path, line)
        args, unknown = parser.parse_known_args(words)
        assert not unknown, (line, unknown)
        assert args.command == words[0]


@pytest.mark.parametrize("path", PATHS)
def test_a_flag_the_command_does_not_have_exits_two(home, path):
    code, _, err = run(*path.split(), "--no-such-flag")
    assert code == 2, (path, err)
    # Named, unless a missing positional is the first mistake argparse reports (or `run`, which
    # is refused whatever follows it, with directions to pravaha-engine).
    assert "--no-such-flag" in err or "are required" in err or "pravaha-engine" in err, err
    assert "--help" in err or "pravaha-engine" in err


def test_an_unknown_command_and_no_command_exit_two(home):
    code, _, err = run("no-such-command")
    assert code == 2 and "invalid choice" in err
    assert run()[0] == 2
    code, out, _ = run("--help")
    assert code == 0 and "examples:" in out
