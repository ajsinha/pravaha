"""Captures the documented ``pravaha`` output from a scratch node, so the docs show what it prints.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Each documented invocation is declared below (:data:`CAPTURES`). The tool starts a real node -- the
``pravaha-server`` app jar the build makes, on free ports that are not the defaults, in a temporary
directory, with the ``dev`` profile -- gives it a stream and a query (:data:`SETUP`), runs every
invocation as a person would (``python -m pravaha.cli``, in a config directory of its own), and
writes each output into the docs between markers::

    <!-- capture: queries -->
    ```text
    $ pravaha queries
    ...
    ```
    <!-- /capture -->

What differs from run to run is normalised (:func:`normalise`): the scratch node's ports become the
defaults (18080, 19090), versions ``<version>``, instants ``<time>``, durations ``N``, fingerprints
``<fp>`` (padded to their width, so tables stay aligned), and this machine's paths their short
forms. Lines describing this machine rather than the node -- doctor's port probes -- are left out.

A test fails when a documented block differs from a fresh capture (``tests/test_cli_captures.py``,
skipped exactly when the server jar is not built)::

    cd sdk/python
    .venv/bin/python tools/cli_captures.py           # rewrite every block
    .venv/bin/python tools/cli_captures.py --check   # exit 1 if a block is stale

Standard library only.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import pathlib
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Optional

HERE = pathlib.Path(__file__).resolve().parent
SDK = HERE.parent
REPO = SDK.parent.parent
DOCS = (
    REPO / "pravaha-console" / "content" / "topics" / "cli-reference.md",
    REPO / "docs" / "guides" / "CLI.md",
)
SERVER_TARGET = REPO / "pravaha-server" / "target"

#: Never bound by the scratch node: the defaults it would collide with, and ports the owner's own
#: services use on a development machine.
RESERVED = frozenset({5432, 9092, 2181, 8080, 5050, 5672, 15672, 8161, 61616, 3000, 3001, 3002,
                      55416, 55417, 18080, 19090, 17070})


@dataclass(frozen=True)
class Capture:
    """One documented invocation: the block's id, the words after ``pravaha``, and lines (regexes)
    to leave out because they describe the machine that ran it rather than the node."""

    id: str
    argv: "tuple[str, ...]"
    omit: "tuple[str, ...]" = field(default=())


QUERY_SQL = "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id"
WINDOW_SQL = ("SELECT user_id, window_start, window_end, SUM(amount) AS spend "
              "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(ts), INTERVAL '1' MINUTE)) "
              "GROUP BY user_id, window_start, window_end")

#: The scratch node's AI layer (docs/guides/AI.md): on, with the deterministic ``mock`` provider, whose
#: regular expressions play the model -- no network, no key -- and a ``log`` notifier for alert drafts.
AI_ARGS = ("--pravaha.ai.enabled=true", "--pravaha.ai.dir=ai", "--pravaha.ai.default-model=mock/m",
           "--pravaha.ai.reload-interval=1h", "--pravaha.notifiers.ops_log.plugin=log")


def _yaml(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def _question(words: str) -> str:
    """A rule matching ``words`` in the question the person typed, and nowhere else in the prompt."""
    return r"[\s\S]*UNTRUSTED (question|description) id=[0-9a-f]+>>>\n[^\n]*" + words


AI_RULES = (
    ("TASK: ask" + _question("per user"),
     '{"sql": "SELECT user_id, SUM(spend) AS total FROM spend_by_minute GROUP BY user_id", '
     '"explanation": "Adds up each user\'s spend over every minute the view holds."}'),
    ("TASK: draft",
     '{"name": "spend_per_minute", "sql": "' + WINDOW_SQL + '", "keys": ["user_id", "window_start"], '
     '"options": {"retention": null, "sink": null, "lane": null}, '
     '"explanation": "Each user\'s spend in every one-minute window of event time."}'),
    ("TASK: alert",
     '{"statement": "CREATE ALERT big_spender ON spend_by_minute WHERE spend > 500 NOTIFY ops_log '
     'WITH (severity = \'critical\')", "explanation": "Notifies when a user spends over 500 in a minute."}'),
    ("TASK: explain-sql",
     "It reads txn, puts each payment in the one-minute window of its event time and adds up the amount "
     "per user per window. A window's total is published when the watermark passes its end, so it can "
     "change for late rows within the allowed lateness."),
    ("TASK: explain-refusal",
     "It means the query keeps a running count for every user forever: the stream never ends, so "
     "its state never stops growing. Count per window instead.\nTry: SELECT user_id, window_end, COUNT(*) "
     "AS n FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(ts), INTERVAL '1' MINUTE)) GROUP BY user_id, "
     "window_start, window_end"),
)
AI_MOCK = "type: mock\ndefault-model: m\nmodels:\n  - name: m\nmock:\n  rules:\n" + "".join(
    f"    - match: {_yaml(match)}\n      reply: {_yaml(reply)}\n" for match, reply in AI_RULES)

#: Run once, in order, before any capture; their output is not documented.
SETUP: "tuple[tuple[str, ...], ...]" = (
    ("streams", "declare", "txn", "--schema", "user_id:STRING,amount:INT64,ts:TIMESTAMP",
     "--event-time", "ts", "--out-of-orderness", "PT5S"),
    ("register", "--name", "spend_by_minute", "--sql", WINDOW_SQL, "--keys", "0,1"),
)

CAPTURES: "tuple[Capture, ...]" = (
    Capture("status", ("status",)),
    Capture("health", ("health",)),
    Capture("queries", ("queries",)),
    Capture("describe", ("describe", "spend_by_minute")),
    Capture("views", ("views",)),
    Capture("top-once", ("top", "--once", "--interval", "1")),
    Capture("validate", ("validate", "--sql", WINDOW_SQL)),
    Capture("validate-refused", ("validate", "--sql", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id")),
    Capture("explain", ("explain", "--sql", WINDOW_SQL)),
    Capture("whoami", ("whoami",)),
    Capture("drop-dry-run", ("drop", "--name", "spend_by_minute", "--dry-run")),
    Capture("describe-refused", ("describe", "no_such_query")),
    Capture("doctor", ("doctor",), omit=(r"^\S+\s+port \d+\s",)),
    Capture("ai-query-ask", ("query", "--ask", "total spend per user", "--chart")),
    Capture("ai-ask-engine", ("ask", "spend per user per minute", "--engine")),
    Capture("ai-alert-ask", ("alert", "--ask", "tell me when a user spends over 500 in a minute")),
    Capture("ai-why-changed", ("explain", "--why-changed", "spend_by_minute")),
    Capture("ai-explain-sql", ("explain-sql", "--query", "spend_by_minute", "--engine")),
    Capture("ai-why", ("why", "PRV-2050", "--sql", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id",
                       "--engine")),
)

_BLOCK = re.compile(r"(<!-- capture: (?P<id>[a-z0-9-]+) -->\n)(?P<body>.*?)(<!-- /capture -->)",
                    re.DOTALL)


class NodeUnavailable(RuntimeError):
    """No server jar, no Java, or a node that did not start: nothing can be captured."""


# ---------------------------------------------------------------------------------- the node


def server_jar() -> pathlib.Path:
    jars = sorted(SERVER_TARGET.glob("pravaha-server-*-app.jar"))
    if not jars:
        raise NodeUnavailable("pravaha-server is not built; run ./mvnw -pl pravaha-server -am "
                              "install -DskipTests")
    return jars[-1]


def free_port(taken: "set[int]") -> int:
    while True:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(("127.0.0.1", 0))
            port = int(probe.getsockname()[1])
        if port not in RESERVED and port not in taken:
            return port


class ScratchNode:
    """A node of its own, in a temporary directory, on two free ports; stopped on exit.

    ``args`` are more of the server's own (``--spring.config.additional-location=...``), ``jvm`` more
    of the JVM's (``-Dloader.path=plugins``, as ``bin/pravaha-server`` passes); files a test wants
    under the node's working directory go into :attr:`home` before it is entered. :meth:`stop` and
    :meth:`start` restart it on the same ports and home, as a tutorial's "restart the node" does."""

    def __init__(self, args: "tuple[str, ...]" = (), jvm: "tuple[str, ...]" = (),
                 env: "Optional[dict[str, str]]" = None) -> None:
        self.args = tuple(args)
        self.jvm = tuple(jvm)
        self.env = dict(env or {})
        self.jar = server_jar()
        java_home = os.environ.get("JAVA_HOME")
        self.java = str(pathlib.Path(java_home) / "bin" / "java") if java_home else shutil.which("java")
        if not self.java or not pathlib.Path(self.java).exists():
            raise NodeUnavailable("no java: set JAVA_HOME to a JDK 21 or later")
        self.http_port = free_port(set())
        self.flight_port = free_port({self.http_port})
        self.home = pathlib.Path(tempfile.mkdtemp(prefix="pravaha-captures-"))
        self.process: Optional[subprocess.Popen[bytes]] = None

    @property
    def http(self) -> str:
        return f"http://127.0.0.1:{self.http_port}"

    @property
    def url(self) -> str:
        return f"grpc://127.0.0.1:{self.flight_port}"

    def __enter__(self) -> "ScratchNode":
        try:
            return self.start()
        except NodeUnavailable:
            self.__exit__(None, None, None)
            raise

    def start(self) -> "ScratchNode":
        """Starts the node (again) and waits until it is running; :class:`NodeUnavailable` if not."""
        tmp = self.home / "tmp"
        tmp.mkdir(exist_ok=True)
        log = open(self.home / "node.log", "ab")
        self.process = subprocess.Popen(
            # The opens bin/pravaha-server gives: without them Arrow cannot allocate a Flight
            # answer's buffers and every query that returns rows is reset (RST_STREAM).
            [self.java, "--add-opens=java.base/java.nio=ALL-UNNAMED",
             "--add-opens=java.base/java.lang=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED",
             f"-Djava.io.tmpdir={tmp}", f"-Duser.home={self.home}", *self.jvm, "-jar", str(self.jar),
             "--spring.profiles.active=dev", "--server.address=127.0.0.1",
             f"--server.port={self.http_port}", "--pravaha.flight.host=127.0.0.1",
             f"--pravaha.flight.port={self.flight_port}", *self.args],
            cwd=self.home, stdout=log, stderr=subprocess.STDOUT,
            env={**os.environ, **self.env} if self.env else None,
        )
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                break
            try:
                with urllib.request.urlopen(self.http + "/api/v1/status", timeout=2) as answer:
                    if json.loads(answer.read()).get("engineState") == "RUNNING":
                        return self
            except urllib.error.HTTPError as refused:
                # A node with users on (args) asks who is asking; its open readiness probe answers.
                if refused.code in (401, 403) and self._ready():
                    return self
            except OSError:
                pass
            time.sleep(0.25)
        tail = (self.home / "node.log").read_text(errors="replace")[-3000:]
        self.stop()
        raise NodeUnavailable(f"the scratch node did not start:\n{tail}")

    def _ready(self) -> bool:
        try:
            with urllib.request.urlopen(self.http + "/actuator/health/readiness", timeout=2) as answer:
                return bool(json.loads(answer.read()).get("status") == "UP")
        except OSError:
            return False

    def stop(self) -> None:
        """Stops the node, keeping its home: :meth:`start` brings it back as it was left."""
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=30)

    def __exit__(self, *_: object) -> None:
        self.stop()
        shutil.rmtree(self.home, ignore_errors=True)


# ---------------------------------------------------------------------------------- running


def _environment(config: pathlib.Path) -> "dict[str, str]":
    env = {k: v for k, v in os.environ.items() if not k.startswith("PRAVAHA_") and k != "NO_COLOR"}
    env.update(PRAVAHA_CONFIG_DIR=str(config), NO_COLOR="1", PYTHONPATH=str(SDK),
               COLUMNS="100", PYTHONIOENCODING="utf-8")
    return env


def pravaha(node: ScratchNode, env: "dict[str, str]", argv: "tuple[str, ...]") -> "tuple[int, str]":
    """``pravaha <argv>`` against the node, stdout and stderr as a terminal interleaves them."""
    done = subprocess.run(
        [sys.executable, "-m", "pravaha.cli", *argv, "--http", node.http, "--url", node.url],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120,
    )
    return done.returncode, done.stdout.decode("utf-8", errors="replace")


def normalise(text: str, node: ScratchNode, config: pathlib.Path, fingerprints: "list[str]") -> str:
    """The parts that differ between runs and machines, as stable words."""
    import pravaha

    swaps = [
        (f"127.0.0.1:{node.http_port}", "localhost:18080"),
        (f"127.0.0.1:{node.flight_port}", "localhost:19090"),
        (str(config), "~/.config/pravaha"),
        (sys.executable, "python"),
        (str(SDK), "sdk/python"),
    ]
    for old, new in swaps:
        text = text.replace(old, new)
    for fingerprint in fingerprints:
        # As wide as what it stands for, so a table's columns stay aligned.
        text = text.replace(fingerprint, "<fp>".ljust(len(fingerprint)))
    patterns = [
        (r"\b\d+\.\d+\.\d+(?:-SNAPSHOT)?(?=\s|$|,|\)|\")", "<version>"),
        (re.escape(pravaha.__version__) + r"\b", "<version>"),
        (r"\b\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)\b", "<time>"),
        (r"\b\d+ (ms|us|s)\b", r"N \1"),
        (r"(answered in) N ms", r"\1 N ms"),
        (r"JAVA_HOME=\S+?:", "JAVA_HOME=$JAVA_HOME:"),
        (r"Java \d+", "Java 21"),
        (r"pyarrow \S+, with Flight", "pyarrow <version>, with Flight"),
        (r"Python <version>", "Python 3.x"),
    ]
    for pattern, replacement in patterns:
        text = re.sub(pattern, replacement, text, flags=re.MULTILINE)
    return text


def _block(capture: Capture, code: int, output: str) -> str:
    lines = [line.rstrip() for line in output.rstrip("\n").splitlines()]
    lines = [line for line in lines if not any(re.search(p, line) for p in capture.omit)]
    shown = " ".join(_quoted(word) for word in capture.argv)
    body = "\n".join([f"$ pravaha {shown}", *lines] + ([f"(exit {code})"] if code else []))
    return f"```text\n{body}\n```\n"


def _quoted(word: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9_./:=,@+-]+", word):
        return word
    return '"' + word.replace("\\", "\\\\").replace('"', '\\"') + '"'


def capture_all() -> "dict[str, str]":
    """Every block, freshly captured. Raises :class:`NodeUnavailable` when no node can start."""
    node = ScratchNode(AI_ARGS)
    (node.home / "ai").mkdir()
    (node.home / "ai" / "mock.yaml").write_text(AI_MOCK, encoding="utf-8")
    with node:
        config = node.home / "config"
        env = _environment(config)
        for argv in SETUP:
            code, output = pravaha(node, env, argv)
            if code != 0:
                raise RuntimeError(f"setup `pravaha {' '.join(argv)}` exited {code}:\n{output}")
        _, listed = pravaha(node, env, ("queries", "--json"))
        fingerprints = sorted({q["fingerprint"] for q in json.loads(listed) if q.get("fingerprint")},
                              key=len, reverse=True)
        blocks = {}
        for capture in CAPTURES:
            code, output = pravaha(node, env, capture.argv)
            blocks[capture.id] = _block(capture, code, normalise(output, node, config, fingerprints))
        return blocks


# ---------------------------------------------------------------------------------- the docs


def rewrite(text: str, blocks: "dict[str, str]") -> str:
    def swap(match: "re.Match[str]") -> str:
        block_id = match.group("id")
        if block_id not in blocks:
            raise KeyError(f"<!-- capture: {block_id} --> names no declared capture")
        return match.group(1) + blocks[block_id] + match.group(4)

    return _BLOCK.sub(swap, text)


def documented() -> "dict[pathlib.Path, list[str]]":
    return {doc: [m.group("id") for m in _BLOCK.finditer(doc.read_text(encoding="utf-8"))]
            for doc in DOCS}


def stale(blocks: "dict[str, str]") -> "list[str]":
    """Each documented block that differs from ``blocks``, as a diff; and every capture declared
    but documented nowhere."""
    problems = []
    used = set()
    for doc in DOCS:
        text = doc.read_text(encoding="utf-8")
        fresh = rewrite(text, blocks)
        used.update(m.group("id") for m in _BLOCK.finditer(text))
        if fresh != text:
            problems.append("".join(difflib.unified_diff(
                text.splitlines(keepends=True), fresh.splitlines(keepends=True),
                str(doc.relative_to(REPO)), str(doc.relative_to(REPO)) + " (captured)", n=2)))
    for missing in sorted(set(blocks) - used):
        problems.append(f"capture {missing!r} is declared but no doc has <!-- capture: {missing} -->")
    return problems


def main(argv: "Optional[list[str]]" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a documented block is stale")
    args = parser.parse_args(argv)
    try:
        blocks = capture_all()
    except NodeUnavailable as exc:
        print(f"cli_captures: {exc}", file=sys.stderr)
        return 2
    if args.check:
        problems = stale(blocks)
        for problem in problems:
            print(problem, file=sys.stderr)
        if problems:
            print("documented CLI output is stale: run `.venv/bin/python tools/cli_captures.py` "
                  "in sdk/python", file=sys.stderr)
            return 1
        return 0
    for doc in DOCS:
        text = doc.read_text(encoding="utf-8")
        fresh = rewrite(text, blocks)
        if fresh != text:
            doc.write_text(fresh, encoding="utf-8")
            print(f"rewrote {doc.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
