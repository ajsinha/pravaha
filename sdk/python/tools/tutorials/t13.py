"""Tutorial 13 -- Writing a connector (docs/tutorials/13-writing-a-connector.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

`pravaha plugin new acme --kind source` and the project it writes; its TCK passing as generated; the
tutorial's source in its place -- Acme Exchange's price ticks, one partition per symbol, replayable
offsets -- a contract broken on purpose (pause ignored) and the TCK catching it; the jar in plugins/,
the node binding it, a windowed query over it. Then a sink: `plugin new acme-ledger --kind sink`, its
TCK, the jar dropped in beside the source, a restart, and a query writing to it.
"""

import pathlib

from tutorial_captures import REPO, Cli, Node, Open, Restart, Sh, Snap, Until

DOC = "13-writing-a-connector.md"

NODE = Node(plugins=True, files=(
    ("t13/streams", "conf/streams"),
    ("t13/AcmeSourcePlugin.java", "tutorial/AcmeSourcePlugin.java"),
    ("t13/AcmeReader.java", "tutorial/AcmeReader.java"),
    ("t13/AcmeSourceTckTest.java", "tutorial/AcmeSourceTckTest.java"),
    ("t13/sinks", "tutorial/sinks"),
    ("t13/tick_minutes.sql", "tick_minutes.sql"),
))

_M2 = REPO / ".m2-local" if (REPO / ".m2-local").is_dir() else pathlib.Path.home() / ".m2" / "repository"
_MVN = f"mvn -o -B -Dmaven.repo.local={_M2}"
T = "docs/tutorials/data/t13"
SRC = "src/main/java/com/example/acme"
TEST = "src/test/java/com/example/acme"
#: What `mvn verify` says that matters: the TCK's verdict, a failure's reason, the build's.
VERDICT = "grep -E 'Tests run:|FAILURE!|must|BUILD'"
JAR = "pravaha-plugin-acme/target/pravaha-plugin-acme-0.1.0-SNAPSHOT.jar"
LEDGER_JAR = "pravaha-plugin-acme-ledger/target/pravaha-plugin-acme-ledger-0.1.0-SNAPSHOT.jar"


def new(capture, name: str, kind: str) -> Sh:
    # In a checkout the CLI takes the version the checkout builds (PLUGINVER-1), as a reader's does.
    return Sh(capture, f"pravaha plugin new {name} --kind {kind}")


def verify(capture, project: str, prefix: str = "") -> Sh:
    """``mvn verify`` in a plugin project, the verdict lines shown."""
    return Sh(capture, f"cd {project} && {prefix}{_MVN} verify 2>&1 | {VERDICT}",
              show=f"{prefix}mvn verify | {VERDICT}")


BEFORE_START = (
    Sh(None, "mkdir -p data"),
    new("t13-new", "acme", "source"),
    Sh("t13-files", "cd pravaha-plugin-acme && find . -type f | sort", show="cd pravaha-plugin-acme && find . -type f | sort"),
    verify("t13-verify-generated", "pravaha-plugin-acme"),
    Sh("t13-copy", f"cp tutorial/AcmeSourcePlugin.java tutorial/AcmeReader.java pravaha-plugin-acme/{SRC}/ && "
                   f"cp tutorial/AcmeSourceTckTest.java pravaha-plugin-acme/{TEST}/",
       show=f"cp $T/AcmeSourcePlugin.java $T/AcmeReader.java {SRC}/ && cp $T/AcmeSourceTckTest.java {TEST}/"),
    # The contract broken on purpose: a reader that keeps producing while paused.
    Sh("t13-break", f"cd pravaha-plugin-acme && sed -i 's/if (paused) {{/if (false \\&\\& paused) {{/' {SRC}/AcmeReader.java"
                    f" && grep -n 'paused) {{' {SRC}/AcmeReader.java",
       show=f"sed -i 's/if (paused) {{/if (false \\&\\& paused) {{/' {SRC}/AcmeReader.java"
            f" && grep -n 'paused) {{' {SRC}/AcmeReader.java"),
    verify("t13-verify-broken", "pravaha-plugin-acme"),
    Sh(None, f"cp tutorial/AcmeReader.java pravaha-plugin-acme/{SRC}/"),
    verify("t13-verify", "pravaha-plugin-acme"),
    Sh("t13-install", f"mkdir -p plugins && cp {JAR} plugins/ && ls plugins",
       show='cp target/pravaha-plugin-acme-0.1.0-SNAPSHOT.jar "$PRAVAHA_HOME/plugins/" && ls "$PRAVAHA_HOME/plugins"'),
)

STEPS = (
    Cli("t13-plugins", ("plugins",), omit=(r"^(?!NAME|acme)",)),
    Cli("t13-streams", ("streams", "list")),
    Cli("t13-register", ("register", "--name", "tick_minutes", "--sql-file", "tick_minutes.sql", "--keys", "0,2")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM tick_minutes"), r"^2\s*$"),
    Cli("t13-query", ("query", "--sql", "SELECT symbol, window_end, low, high, ticks FROM tick_minutes")),
    Open("/plugins"),
    Snap("01-plugins", js="(() => { const h = [...document.querySelectorAll('#plugin-cards h2')]"
                          ".find(e => e.textContent.trim() === 'acme'); "
                          "h.closest('section').scrollIntoView({block: 'start'}); window.scrollBy(0, -110); return true; })()"),
    Open("/catalog/streams/ticks"),
    Snap("02-stream"),
    Open("/views/tick_minutes/live", "document.getElementById('live-state').dataset.state === 'fresh'"),
    Snap("03-view"),
    # The sink, briefly.
    new("t13-new-sink", "acme-ledger", "sink"),
    verify("t13-verify-sink", "pravaha-plugin-acme-ledger"),
    Sh("t13-install-sink", f"cp {LEDGER_JAR} plugins/ && cp -r tutorial/sinks conf/"
                           " && mkdir -p data/outgoing && ls plugins",
       show='cp target/pravaha-plugin-acme-ledger-0.1.0-SNAPSHOT.jar "$PRAVAHA_HOME/plugins/" && ls "$PRAVAHA_HOME/plugins"'),
    Restart(),
    Cli("t13-sinks", ("sinks",)),
    Cli("t13-register-sink", ("register", "--name", "big_ticks", "--sql",
                              "SELECT symbol, seq, price FROM ticks WHERE price >= 155", "--keys", "0,1",
                              "--sink", "ledger")),
    Until("wc -l < data/outgoing/ledger.txt", r"^4\s*$"),
    Sh("t13-ledger", "sort data/outgoing/ledger.txt", show='sort "$PRAVAHA_HOME/data/outgoing/ledger.txt"'),
    Open("/queries/big_ticks"),
    Snap("04-sink-query"),
)
