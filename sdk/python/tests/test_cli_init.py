"""``pravaha init``: a starter project that is refused over someone's work, written nowhere under
``--dry-run``, parses, and -- given a server jar -- runs on a real node as its README says.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The real-node test starts the ``pravaha-server`` jar the build made (``tools/cli_captures.py``'s
scratch node: free ports, never the defaults, a temporary home) with the generated
``conf/application.yaml`` and the sample CSV where the compose file mounts it, then registers the
generated query and reads closed windows back. It is skipped exactly when no jar is built.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import time

import pytest

from pravaha.cli import EXIT_OK, EXIT_USAGE
from pravaha.cli._init import QUERY_KEYS, QUERY_NAME
from test_cli import home, run  # noqa: F401

SDK = pathlib.Path(__file__).resolve().parents[1]
FILES = {"conf/application.yaml", "data/txn.csv", f"queries/{QUERY_NAME}.sql", "docker-compose.yml",
         "README.md", ".pravaha"}


def _written(root: pathlib.Path) -> "set[str]":
    return {str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()}


def test_init_writes_the_project_and_says_why(home, tmp_path):
    target = tmp_path / "spend-demo"
    code, out, err = run("init", str(target))
    assert code == EXIT_OK, err
    assert _written(target) == FILES
    for name in FILES:
        assert name in out
    assert "choices made for you:" in out and "next:" in out
    assert "pravaha context add spend-demo --url grpc://localhost:19090 " \
           "--http http://localhost:18080 --use" in out
    # The user's configuration is not written: the context is printed, not saved.
    assert not home.exists()


def test_dry_run_writes_nothing_and_json_lists_the_files(home, tmp_path):
    target = tmp_path / "p"
    code, out, _ = run("init", str(target), "--dry-run")
    assert code == EXIT_OK and "would create" in out and "nothing was written" in out
    assert not target.exists()
    code, out, _ = run("init", str(target), "--dry-run", "--json")
    plan = json.loads(out)
    assert plan["dryRun"] is True and {f["path"] for f in plan["files"]} == FILES
    assert all(f["why"] for f in plan["files"])
    assert plan["query"] == {"name": QUERY_NAME, "sqlFile": f"queries/{QUERY_NAME}.sql",
                             "keys": QUERY_KEYS}
    assert not target.exists()


def test_a_directory_with_something_in_it_is_refused_unless_forced(home, tmp_path):
    (tmp_path / "notes.txt").write_text("mine")
    code, _, err = run("init", str(tmp_path))
    assert code == EXIT_USAGE and "not empty" in err and "--force" in err
    assert _written(tmp_path) == {"notes.txt"}
    code, _, _ = run("init", str(tmp_path), "--force")
    assert code == EXIT_OK
    assert _written(tmp_path) == FILES | {"notes.txt"}
    assert (tmp_path / "notes.txt").read_text() == "mine"


def test_the_yaml_parses_and_compose_runs_the_engine_image_on_the_standard_ports(home, tmp_path):
    run("init", str(tmp_path / "p"))
    root = tmp_path / "p"
    compose_text = (root / "docker-compose.yml").read_text()
    app_text = (root / "conf/application.yaml").read_text()
    try:
        import yaml  # type: ignore[import-untyped]
    except ImportError:  # the SDK has no YAML dependency; the shape is checked by hand instead
        yaml = None
    if yaml is not None:
        compose = yaml.safe_load(compose_text)
        server = compose["services"]["pravaha-server"]
        assert server["image"].startswith("pravaha/pravaha-server:")
        assert set(server["ports"]) == {"127.0.0.1:18080:18080", "127.0.0.1:19090:19090"}
        assert compose["services"]["pravaha-console"]["profiles"] == ["console"]
        app = yaml.safe_load(app_text)["pravaha"]
        assert app["streams"]["txn"]["event-time"] == "event_time"
        assert app["sources"]["txn"]["plugin"] == "filesystem"
    for needle in ("image: pravaha/pravaha-server:", '"127.0.0.1:18080:18080"',
                   '"127.0.0.1:19090:19090"', '"127.0.0.1:17070:17070"', "profiles: [console]",
                   "./conf/application.yaml:/opt/pravaha/conf/application.yaml:ro"):
        assert needle in compose_text, needle
    assert "\t" not in compose_text + app_text
    for needle in ("  streams:\n    txn:\n", "event-time: event_time", "plugin: filesystem",
                   "path: data/incoming/txn.csv"):
        assert needle in app_text, needle
    # Every sample row has the schema's five columns.
    assert all(len(line.split(",")) == 5 for line in (root / "data/txn.csv").read_text().splitlines())


# ---------------------------------------------------------------------------------- a real node


def _captures():
    spec = importlib.util.spec_from_file_location("cli_captures", SDK / "tools" / "cli_captures.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_the_project_runs_on_a_real_node(home, tmp_path):
    pytest.importorskip("pyarrow", reason="register and query speak Flight")
    tool = _captures()
    project = tmp_path / "p"
    assert run("init", str(project))[0] == EXIT_OK
    try:
        node = tool.ScratchNode(args=(
            f"--spring.config.additional-location=file:{project / 'conf' / 'application.yaml'}",))
    except tool.NodeUnavailable as exc:
        pytest.skip(str(exc))
    # Where the compose file mounts ./data: the node's home is its working directory.
    (node.home / "data" / "incoming").mkdir(parents=True)
    shutil.copy(project / "data" / "txn.csv", node.home / "data" / "incoming" / "txn.csv")
    try:
        node.__enter__()
    except tool.NodeUnavailable as exc:
        pytest.skip(str(exc))
    try:
        env = tool._environment(node.home / "config")

        def pravaha(*argv: str) -> "tuple[int, str]":
            done = subprocess.run(
                [sys.executable, "-m", "pravaha.cli", *argv, "--http", node.http, "--url", node.url],
                env=env, cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
            return done.returncode, done.stdout.decode("utf-8", errors="replace")

        sql_file = f"queries/{QUERY_NAME}.sql"
        code, output = pravaha("validate", "--sql-file", sql_file)
        assert code == 0, output
        code, output = pravaha("register", "--name", QUERY_NAME, "--sql-file", sql_file,
                               "--keys", QUERY_KEYS)
        assert code == 0, output
        rows: list = []
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline and len(rows) < 7:
            code, output = pravaha("query", "--sql", f"SELECT * FROM {QUERY_NAME}", "--json")
            assert code == 0, output
            rows = json.loads(output)
            time.sleep(0.5)
        # Minutes 09:00 to 09:02 have closed (the last row is 09:03:50, five seconds of lateness):
        # alice, bob; dave, bob, carol; frank, bob -- seven (user, minute) rows, PENDING and FAILED out.
        assert len(rows) == 7, rows
        assert sum(int(r["spend"]) for r in rows) == 500 + 50 + 120 + 150 + 75 + 300 + 1200 + 60
    finally:
        node.__exit__(None, None, None)
