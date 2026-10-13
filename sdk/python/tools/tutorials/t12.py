"""Tutorial 12 -- Embedding Pravaha (docs/tutorials/12-embedding-pravaha.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Two small Maven projects built against this checkout's installed engine: a plain Java program with the
embedded engine (declare, register, push, close a minute, follow, read, two refusals), the same engine
from configuration with a journal and checkpoints run four times (a restart keeps the total; a close
before the next checkpoint loses the push), and a Spring Boot service with @PravahaListener, run as a
jar and tested with @PravahaTest and PravahaTester. Then the same SQL on a node, read from a file.
"""

import pathlib
import re

from tutorial_captures import REPO, Cli, Node, Open, Sh, Snap, Until

DOC = "12-embedding-pravaha.md"

NODE = Node(files=(
    ("t12/streams", "conf/streams"),
    ("t12/txn.csv", "data/incoming/txn.csv"),
    ("t12/merchant_minutes.sql", "merchant_minutes.sql"),
    ("t12/payments-embedded", "payments-embedded"),
    ("t12/payments-spring", "payments-spring"),
))

#: The engine this checkout installed (a worktree's own repository, or ~/.m2), offline; the projects'
#: pravaha.version set to it. A reader's mvn finds the engine they installed the same way.
_M2 = REPO / ".m2-local" if (REPO / ".m2-local").is_dir() else pathlib.Path.home() / ".m2" / "repository"
_VERSION = re.search(r"<version>([^<]+)</version>", (REPO / "pom.xml").read_text(encoding="utf-8")).group(1)
_MVN = f"mvn -o -B -Dmaven.repo.local={_M2} -Dpravaha.version={_VERSION}"


def mvn(capture, project: str, args: str, shown: str = "", pipe: str = "", prefix: str = "") -> Sh:
    """``mvn <args>`` in one of the tutorial's projects, as a reader runs it there."""
    tail = f" | {pipe}" if pipe else ""
    return Sh(capture, f"cd {project} && umask 077 && {prefix}{_MVN} {args}{tail}",
              show=f"{prefix}mvn {shown or args}{tail}")


DURABLE = "-q exec:exec -Dmain=com.example.payments.Durable"
TESTS = r"grep -E 'Tests run:|BUILD'"  # the test JVM's warnings (Mockito, agents) filtered out too

STEPS = (
    mvn("t12-tree", "payments-embedded",
        "-q dependency:tree -Dincludes=com.ash.messaging -DoutputFile=tree.txt && cat tree.txt"),
    mvn("t12-run", "payments-embedded", "-q compile exec:exec"),
    mvn("t12-durable-1", "payments-embedded", DURABLE),
    mvn("t12-durable-2", "payments-embedded", DURABLE),
    mvn("t12-durable-closed", "payments-embedded", DURABLE, prefix="CLOSE_AT_ONCE=1 "),
    mvn("t12-durable-4", "payments-embedded", DURABLE),
    Sh("t12-state", "cd payments-embedded && find state -type f | sort", show="find state -type f | sort"),
    mvn("t12-package", "payments-spring", "-q package -DskipTests"),
    Sh("t12-jar", "cd payments-spring && java -jar target/revenue-board-0.1.0-SNAPSHOT.jar",
       show="java -jar target/revenue-board-0.1.0-SNAPSHOT.jar"),
    mvn("t12-test", "payments-spring", "package 2>&1", pipe=TESTS),
    Cli("t12-register", ("register", "--name", "merchant_minutes", "--sql-file", "merchant_minutes.sql",
                         "--keys", "0,2")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM merchant_minutes"), r"^2\s*$"),
    Cli("t12-query", ("query", "--sql",
                      "SELECT merchant, window_end, spend, payments FROM merchant_minutes")),
    Cli("t12-refused", ("validate", "--sql", "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id")),
    Open("/queries/merchant_minutes"),
    Snap("01-query"),
    Open("/views/merchant_minutes/live", "document.getElementById('live-state').dataset.state === 'fresh'"),
    Snap("02-view"),
    Open("/help/topics/embedded-engine"),
    Snap("03-help-embedded"),
    Open("/help/topics/spring-boot-starter"),
    Snap("04-help-starter"),
)
