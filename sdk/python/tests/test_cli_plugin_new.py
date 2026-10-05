"""``pravaha plugin new``: a connector project that is laid out as the connector guide says, and that
builds and passes its TCK as generated.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The build test runs Maven offline against this checkout's installed snapshot -- in a linked worktree
``<repo>/.m2-local``, which ``tools/worktree-build.sh -o -DskipTests install`` fills; otherwise
``~/.m2/repository``; ``PRAVAHA_M2_REPO`` overrides both -- and is skipped, saying which, when there is
no Maven, no JDK 21 or later, or no installed ``pravaha-testkit`` of the reactor's version.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import shutil
import subprocess

import pytest

from pravaha.cli import EXIT_OK, EXIT_USAGE
from test_cli import home, run  # noqa: F401

REPO = pathlib.Path(__file__).resolve().parents[3]
SPI = "src/main/resources/META-INF/services/com.ash.messaging.pravaha.api.plugin."


def _files(root: pathlib.Path) -> "set[str]":
    return {str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()}


def test_a_source_project_is_the_guides_layout(home, tmp_path):
    target = tmp_path / "src-plugin"
    code, out, err = run("plugin", "new", "my-store", "--kind", "source", "--dir", str(target),
                         "--pravaha-version", "9.9.9")
    assert code == EXIT_OK, err
    assert _files(target) == {
        "pom.xml", "README.md", SPI + "StreamSourcePlugin",
        "src/main/java/com/example/mystore/MyStoreSourcePlugin.java",
        "src/main/java/com/example/mystore/MyStoreReader.java",
        "src/test/java/com/example/mystore/MyStoreSourceTckTest.java",
    }
    assert (target / (SPI + "StreamSourcePlugin")).read_text() == \
        "com.example.mystore.MyStoreSourcePlugin\n"
    pom = (target / "pom.xml").read_text()
    assert "<maven.compiler.release>21</maven.compiler.release>" in pom
    assert "<pravaha.version>9.9.9</pravaha.version>" in pom
    assert re.search(r"<artifactId>pravaha-api</artifactId>\s*<version>\$\{pravaha.version}</version>"
                     r"\s*<scope>provided</scope>", pom)
    tck = (target / "src/test/java/com/example/mystore/MyStoreSourceTckTest.java").read_text()
    assert "extends SourcePluginTck" in tck and "com.ash.messaging.pravaha.testkit.tck" in tck
    plugin = (target / "src/main/java/com/example/mystore/MyStoreSourcePlugin.java").read_text()
    assert 'return "my-store";' in plugin and "MY_STORE_BAD_CONFIGURATION" in plugin
    assert "choices made for you:" in out and "mvn verify" in out


def test_a_sink_project_in_a_package_of_its_own(home, tmp_path):
    target = tmp_path / "s"
    code, out, _ = run("plugin", "new", "audit-file", "--kind", "sink", "--package", "com.acme.audit",
                       "--dir", str(target), "--pravaha-version", "9.9.9", "--json")
    assert code == EXIT_OK
    shown = json.loads(out)
    assert shown["plugin"] == {"name": "audit-file", "kind": "sink", "package": "com.acme.audit",
                               "class": "com.acme.audit.AuditFileSinkPlugin", "pravahaVersion": "9.9.9"}
    assert SPI + "StreamSinkPlugin" in _files(target)
    assert "extends SinkPluginTck" in (target / "src/test/java/com/acme/audit/AuditFileSinkTckTest.java"
                                       ).read_text()


def test_dry_run_writes_nothing_and_a_bad_name_kind_or_package_is_usage(home, tmp_path):
    target = tmp_path / "p"
    code, out, _ = run("plugin", "new", "x", "--kind", "source", "--dir", str(target), "--dry-run",
                       "--pravaha-version", "1.0.0")
    assert code == EXIT_OK and "nothing was written" in out and not target.exists()
    for argv in (("My Store", "--kind", "source"), ("ok", "--kind", "lookup"), ("ok",),
                 ("ok", "--kind", "sink", "--package", "Com.Example")):
        code, _, err = run("plugin", "new", *argv, "--dir", str(target), "--pravaha-version", "1.0.0")
        assert code == EXIT_USAGE, (argv, err)
    assert not target.exists()


# ---------------------------------------------------------------------------------- the build


def _reactor_version() -> str:
    pom = (REPO / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<artifactId>pravaha</artifactId>\s*<version>([^<]+)</version>", pom)
    assert match, "the root pom names no version"
    return match.group(1)


def _maven_or_skip() -> "tuple[str, dict[str, str], pathlib.Path, str]":
    maven = shutil.which("mvn") or (str(REPO / "mvnw") if (REPO / "mvnw").exists() else None)
    if maven is None:
        pytest.skip("no Maven")
    java_home = os.environ.get("JAVA_HOME") or "/usr/lib/jvm/java-21-openjdk-amd64"
    if not (pathlib.Path(java_home) / "bin" / "javac").exists():
        pytest.skip(f"no JDK at JAVA_HOME ({java_home})")
    version = _reactor_version()
    candidates = [os.environ.get("PRAVAHA_M2_REPO"), str(REPO / ".m2-local"),
                  str(pathlib.Path.home() / ".m2" / "repository")]
    for candidate in filter(None, candidates):
        repo = pathlib.Path(candidate)
        if (repo / "com/ash/messaging/pravaha-testkit" / version).is_dir():
            break
    else:
        pytest.skip(f"pravaha-testkit {version} is not installed: run tools/worktree-build.sh -o "
                    "-DskipTests install")
    env = dict(os.environ, JAVA_HOME=java_home)
    return maven, env, repo, version


@pytest.mark.parametrize("kind, passed, skipped", [("source", 10, 0), ("sink", 4, 6)])
def test_the_project_builds_and_its_tck_passes_as_generated(home, tmp_path, kind, passed, skipped):
    maven, env, repo, version = _maven_or_skip()
    target = tmp_path / kind
    code, _, err = run("plugin", "new", "my-store", "--kind", kind, "--dir", str(target),
                       "--pravaha-version", version)
    assert code == EXIT_OK, err
    done = subprocess.run([maven, "-o", "-B", f"-Dmaven.repo.local={repo}", "verify"], cwd=target,
                          env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
    log = done.stdout.decode("utf-8", errors="replace")
    assert done.returncode == 0 and "BUILD SUCCESS" in log, log[-4000:]
    assert f"Tests run: {passed + skipped}, Failures: 0, Errors: 0, Skipped: {skipped}" in log, log[-4000:]
    assert (target / "target" / "pravaha-plugin-my-store-0.1.0-SNAPSHOT.jar").is_file()
