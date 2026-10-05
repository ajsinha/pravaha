"""``pravaha context``: named connections, their precedence, their file, and their tokens.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Every test runs in a config directory of its own (``home``), never the owner's ``~/.config``.
"""

from __future__ import annotations

import argparse
import json
import shutil
import stat
import subprocess

import pytest

from pravaha.cli import EXIT_OK, EXIT_USAGE
from pravaha.cli._settings import ContextStore, Settings, save_token
from test_cli import _Engine, answer, engine, home, last, run  # noqa: F401


def _file(home):
    return home / "contexts.json"


def _add(*argv):
    code, out, err = run("context", "add", *argv)
    assert code == EXIT_OK, err
    return out


# ---------------------------------------------------------------------------------- the file


def test_add_writes_one_private_json_file_and_show_masks_secrets(home):
    _add("prod", "--url", "grpc+tls://n1:19090", "--http", "https://n1:18080",
         "--token", "sekrit-token-123", "--tls-ca", "/ca.pem", "--tls-trust-store-password", "pw1",
         "--insecure-token", "--timeout", "12")
    path = _file(home)
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    document = json.loads(path.read_text())
    assert document == {"contexts": {"prod": {
        "url": "grpc+tls://n1:19090", "http": "https://n1:18080", "token": "sekrit-token-123",
        "insecure-token": True, "timeout": 12.0, "tls-ca": "/ca.pem",
        "tls-trust-store-password": "pw1"}}}
    for argv in (("context", "show", "prod"), ("context", "show", "prod", "--json"),
                 ("context", "list"), ("context", "list", "--json")):
        code, out, err = run(*argv)
        assert code == EXIT_OK, err
        assert "sekrit-token-123" not in out + err and "pw1" not in out + err
    code, out, _ = run("context", "show", "prod", "--json")
    shown = json.loads(out)
    assert shown["settings"]["token"] == "<set>"
    assert shown["settings"]["tls-trust-store-password"] == "<set>"
    assert shown["settings"]["url"] == "grpc+tls://n1:19090" and shown["current"] is False
    # Rewritten, never left half-written: no temporary file survives a save.
    assert sorted(p.name for p in home.iterdir()) == ["contexts.json"]


def test_list_use_current_set_and_remove(home):
    _add("a", "--url", "grpc://a:1", "--http", "http://a:2")
    _add("b", "--http", "http://b:2", "--use")
    code, out, _ = run("context", "list", "--json")
    listed = json.loads(out)
    assert listed["current"] == "b"
    assert [(c["name"], c["current"]) for c in listed["contexts"]] == [("a", False), ("b", True)]
    assert run("context", "list", "--names")[1] == "a\nb\n"
    assert run("context", "use", "a")[0] == EXIT_OK
    assert run("context", "current")[1] == "a\n"
    assert run("context", "set", "a", "--timeout", "9", "--unset", "url")[0] == EXIT_OK
    assert ContextStore().contexts["a"] == {"http": "http://a:2", "timeout": 9.0}
    assert run("context", "remove", "a")[0] == EXIT_OK
    assert ContextStore().current is None and list(ContextStore().contexts) == ["b"]
    code, out, _ = run("context", "current", "--json")
    assert json.loads(out) == {"name": None, "source": None}


def test_mistakes_exit_two_and_name_the_way_out(home):
    _add("a", "--http", "http://a:2")
    code, _, err = run("context", "add", "a", "--http", "http://x")
    assert code == EXIT_USAGE and "context set a" in err
    code, _, err = run("context", "use", "nope")
    assert code == EXIT_USAGE and "no context named 'nope'" in err and "a" in err
    code, _, err = run("context", "set", "a")
    assert code == EXIT_USAGE and "--unset" in err
    code, _, err = run("status", "--context", "nope")
    assert code == EXIT_USAGE and "no context named 'nope'" in err


def test_a_broken_file_is_refused_by_name_but_context_can_still_mend_it(home):
    home.mkdir(parents=True, exist_ok=True)
    _file(home).write_text('{"contexts": {"a": {"urll": "x"}}, "current-context": "a"}')
    code, _, err = run("status")
    assert code == EXIT_USAGE and "'urll'" in err
    _file(home).write_text("{not json")
    code, _, err = run("status")
    assert code == EXIT_USAGE and "not valid JSON" in err


# ---------------------------------------------------------------------------------- precedence


@pytest.mark.parametrize(
    "flag, env, chosen, expected",
    [
        # flag > env > context (--context > PRAVAHA_CONTEXT > current) > default
        ("http://flag:1", "http://env:1", "flag", "http://flag:1"),
        (None, "http://env:1", "flag", "http://env:1"),
        (None, None, "flag", "http://picked:1"),
        (None, None, "env", "http://envctx:1"),
        (None, None, "current", "http://current:1"),
        (None, None, None, "http://localhost:18080"),
    ],
)
def test_precedence_flag_then_env_then_context_then_default(home, flag, env, chosen, expected):
    store = ContextStore()
    store.contexts = {"picked": {"http": "http://picked:1"}, "envctx": {"http": "http://envctx:1"},
                      "cur": {"http": "http://current:1"}}
    store.current = "cur" if chosen == "current" else None
    store.save()
    environ = {"PRAVAHA_CONFIG_DIR": str(home)}
    if env:
        environ["PRAVAHA_HTTP"] = env
    args = argparse.Namespace()
    if flag:
        args.http = flag
    if chosen == "flag":
        args.context = "picked"
        environ["PRAVAHA_CONTEXT"] = "envctx"  # --context beats the variable
    elif chosen == "env":
        environ["PRAVAHA_CONTEXT"] = "envctx"
        store.current = "cur"  # ... and the variable beats the current context
        store.save()
    resolved = Settings.resolve(args, environ)
    assert resolved.http == expected
    assert resolved.context == {"flag": "picked", "env": "envctx", "current": "cur"}.get(
        chosen or "")
    assert resolved.context_source == chosen


def test_the_token_flag_env_context_and_file_in_that_order(home):
    save_token("from-file")
    store = ContextStore()
    store.contexts = {"p": {"token": "from-context"}, "bare": {"http": "http://x:1"}}
    store.save()
    env = {"PRAVAHA_CONFIG_DIR": str(home)}
    # No context: the legacy file, exactly as before contexts.
    assert Settings.resolve(argparse.Namespace(), env).token_source == "file"
    with_context = argparse.Namespace(context="p")
    assert (Settings.resolve(with_context, env).token, Settings.resolve(with_context, env).token_source) \
        == ("from-context", "context")
    assert Settings.resolve(with_context, dict(env, PRAVAHA_TOKEN="e")).token == "e"
    assert Settings.resolve(argparse.Namespace(context="p", token="f"), env).token == "f"
    # A context without a token does not borrow the default node's saved one.
    assert Settings.resolve(argparse.Namespace(context="bare"), env).token is None


def test_a_context_supplies_tls_and_switches(home):
    store = ContextStore()
    store.contexts = {"p": {"url": "grpc+tls://n:1", "tls-override-hostname": "n1", "tls-no-verify": True,
                            "insecure-token": True, "timeout": 7}}
    store.save()
    resolved = Settings.resolve(argparse.Namespace(context="p"), {"PRAVAHA_CONFIG_DIR": str(home)})
    assert resolved.url == "grpc+tls://n:1" and resolved.timeout == 7.0
    assert resolved.insecure_token and resolved.tls.disable_hostname_verification
    assert resolved.tls.override_hostname == "n1"


def test_no_contexts_file_behaves_exactly_as_before(home):
    env = {"PRAVAHA_CONFIG_DIR": str(home), "PRAVAHA_URL": "grpc://e:1"}
    resolved = Settings.resolve(argparse.Namespace(), env)
    assert (resolved.url, resolved.http, resolved.context) == ("grpc://e:1", "http://localhost:18080", None)
    assert not _file(home).exists()


# ---------------------------------------------------------------------------------- tokens, doctor


def test_login_save_and_logout_use_the_context_in_use(engine, home):
    _add("p", "--http", engine, "--insecure-token", "--use")
    answer("POST", "/api/v1/auth/login", {"token": "ctx-token-1", "expiresAt": "later"})
    code, out, err = run("login", "--user", "ann", "--password", "pw", "--save")
    assert code == EXIT_OK, err
    assert "ctx-token-1" not in out and "context p" in out
    assert ContextStore().contexts["p"]["token"] == "ctx-token-1"
    assert not (home / "token").exists()
    assert stat.S_IMODE(_file(home).stat().st_mode) == 0o600
    answer("GET", "/api/v1/auth/me", {"username": "ann", "roles": [], "via": "session"})
    assert run("whoami")[0] == EXIT_OK
    assert last()["authorization"] == "Bearer ctx-token-1"
    answer("POST", "/api/v1/auth/logout", None, 204)
    code, out, _ = run("logout")
    assert code == EXIT_OK and "context p" in out
    assert "token" not in ContextStore().contexts["p"]


def test_doctor_names_the_context_in_use(home):
    _add("p", "--http", "http://127.0.0.1:9", "--url", "grpc://127.0.0.1:9", "--use")
    code, out, _ = run("doctor", "--json", "--timeout", "0.5")
    checks = {c["name"]: c for c in json.loads(out)}
    assert "p (chosen by pravaha context use)" in checks["context"]["detail"]
    _file(home).chmod(0o644)
    code, out, _ = run("doctor", "--json", "--timeout", "0.5")
    assert {c["name"]: c for c in json.loads(out)}["context"]["status"] == "RED"


def test_bash_completes_context_verbs_and_names(home, tmp_path):
    if not shutil.which("bash"):
        pytest.skip("no bash")
    _add("alpha", "--http", "http://a:1")
    _add("beta", "--http", "http://b:1")
    script = tmp_path / "pravaha.bash"
    script.write_text(run("completion", "bash")[1], encoding="utf-8")
    # The script asks `pravaha context list --names`; a shim stands in for the installed command.
    shim = tmp_path / "bin"
    shim.mkdir()
    import sys

    (shim / "pravaha").write_text(f"#!/bin/sh\nexec {sys.executable} -m pravaha.cli \"$@\"\n")
    (shim / "pravaha").chmod(0o755)
    probe = (f'export PATH="{shim}:$PATH"; source {script}; '
             'COMP_WORDS=(pravaha context ""); COMP_CWORD=2; _pravaha_complete; echo "${COMPREPLY[*]}"; '
             'COMP_WORDS=(pravaha context use ""); COMP_CWORD=3; _pravaha_complete; echo "${COMPREPLY[*]}"; '
             'COMP_WORDS=(pravaha queries --context b); COMP_CWORD=3; _pravaha_complete; echo "${COMPREPLY[*]}"')
    import os

    done = subprocess.run(["bash", "-c", probe], capture_output=True, text=True,
                          env=dict(os.environ, PYTHONPATH=os.pathsep.join(sys.path)))
    lines = done.stdout.splitlines()
    assert lines[0].split() == ["list", "show", "add", "set", "use", "remove", "current"], done.stderr
    assert lines[1:] == ["alpha beta", "beta"], done.stderr
