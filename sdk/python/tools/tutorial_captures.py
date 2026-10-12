"""Captures the hands-on tutorials' output (docs/tutorials/) from a scratch node per tutorial.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Each tutorial is a **scenario**, ``tools/tutorials/tNN.py``: the node it needs (its configuration and
sample data under ``docs/tutorials/data/tNN/``, the profiles, any plugin jar) and the steps a reader
takes, in order -- ``pravaha`` commands, shell commands (appending a row to the file a source follows,
``psql`` against the PostgreSQL gateway), the tutorial's Python scripts, HTTP calls, waits, a restart.
A step with an id is **captured**: what it printed is written into the tutorial between markers::

    <!-- capture: t01-register -->
    ```text
    $ pravaha register --name spend_per_minute --sql-file queries/spend_per_minute.sql --keys 0,1
    ...
    ```
    <!-- /capture -->

The same scenario drives the tutorial's screenshots (``pravaha-console/tools/docs_screenshots.py
--tutorial tNN``): its ``Open``/``Snap`` steps say where the browser is and when to take a picture, and
are skipped here, so the pictures and the text are of one run of one story.

What differs between runs is normalised (:func:`normalise`): the scratch node's ports become the ones a
reader's node has (18080, 19090, the gateway's 5433), instants that are wall-clock time ``<time>`` (the
sample data's own event times are kept: they are the lesson), ids ``<id>``, session tokens ``<token>``,
durations ``N``, versions ``<version>``. A test runs ``--check`` wherever the real-engine tests run
(``tests/test_tutorial_captures.py``), so a tutorial cannot drift from what the product prints::

    cd sdk/python
    .venv/bin/python tools/tutorial_captures.py                 # every tutorial, rewritten
    .venv/bin/python tools/tutorial_captures.py --only t02      # one
    .venv/bin/python tools/tutorial_captures.py --check         # exit 1 if a block is stale

Standard library only (the scenarios' Python scripts use the SDK, run with this interpreter).
"""

from __future__ import annotations

import argparse
import difflib
import importlib.util
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

HERE = pathlib.Path(__file__).resolve().parent
SDK = HERE.parent
REPO = SDK.parent.parent
TUTORIALS = REPO / "docs" / "tutorials"
DATA = TUTORIALS / "data"
SCENARIOS = HERE / "tutorials"

sys.path.insert(0, str(HERE))
# The scenarios import their step types from here: as a script this module is __main__, and a second
# copy imported by name would make every isinstance() below false.
sys.modules.setdefault("tutorial_captures", sys.modules[__name__])
from cli_captures import NodeUnavailable, ScratchNode, free_port  # noqa: E402

#: The development account the ``users`` profile ensures at every start (captive): what a reader signs
#: in as on a node of their own, and so what the tutorials sign in as.
USER, PASSWORD = "pravaha-dev-admin", "pravaha-dev-admin123"

_BLOCK = re.compile(r"(<!-- capture: (?P<id>t\d\d-[a-z0-9-]+) -->\n)(?P<body>.*?)(<!-- /capture -->)", re.DOTALL)


# ---------------------------------------------------------------------------------- the steps


@dataclass(frozen=True)
class Container:
    """A server the tutorial runs beside its node -- a Kafka broker, a PostgreSQL -- in Docker.

    The tutorial shows it as a reader starts it: ``docker run --name <name> -p <port>:<port> <image>``,
    reached at ``localhost:<port>``. The run starts it under a name of its own (``<name>-<pid>``) on a
    free port (never the reader's, which may be someone's own service), listening on that port inside
    the container too, so the address it advertises works from both sides. ``localhost:<port>`` in
    the node's configuration becomes the real one; a step's ``run`` may name ``{<name>}`` (the
    container) and ``{<name>.port}``; and the real port is shown as ``<port>``. ``env`` and ``args``
    may name ``{port}``; ``ready`` is a shell command, run until it succeeds (``{container}``, ``{port}``).
    ``inside`` is the port the server listens on in the container when it cannot be told the free one
    (PostgreSQL's 5432). ``options`` are ``docker run``'s own, before the image (``--user``, ``-v``), and
    may name ``{home}``, the node's home, as ``env`` and ``args`` may. ``later`` leaves it to an
    :class:`Up` step, for a container that runs what the scenario has made (a home it has laid out).
    The container is removed when the run ends, whatever happened."""

    name: str
    image: str
    port: int
    inside: int = 0
    env: "tuple[tuple[str, str], ...]" = ()
    args: "tuple[str, ...]" = ()
    ready: str = "true"
    timeout: float = 120
    options: "tuple[str, ...]" = ()
    later: bool = False


@dataclass(frozen=True)
class Node:
    """The node a tutorial runs against. ``config`` is the tutorial's ``application.yaml`` (under
    ``docs/tutorials/data/``); ``files`` copies sample data into the node's home (source -> place),
    where its relative paths resolve, as under ``PRAVAHA_HOME``. ``jars`` go to ``plugins/`` (``-Dloader.path``),
    found under ``~/.m2/repository``; ``pgwire`` turns the PostgreSQL gateway on, on a free port;
    ``containers`` are started first (:class:`Container`). ``plugins`` loads ``plugins/`` (as
    ``bin/pravaha-server`` does) when the jars there are the scenario's own, not Maven's. ``flight_ca``
    is the CA (in the home) that signed the certificate of a node whose configuration puts Flight on
    TLS: the reader's ``PRAVAHA_URL`` is then ``grpc+tls://`` and their ``PRAVAHA_TLS_CA`` that file."""

    config: str
    files: "tuple[tuple[str, str], ...]" = ()
    profiles: str = "dev,users"
    args: "tuple[str, ...]" = ()
    jars: "tuple[str, ...]" = ()
    pgwire: bool = False
    data_date: str = "2026-10-01"
    containers: "tuple[Container, ...]" = ()
    plugins: bool = False
    flight_ca: str = ""


@dataclass(frozen=True)
class Cli:
    """``pravaha <argv>``, as a reader types it (the node's address comes from the environment)."""

    id: Optional[str]
    argv: "tuple[str, ...]"
    stdin: Optional[str] = None
    omit: "tuple[str, ...]" = ()
    show: Optional[str] = None
    #: (regex, replacement) over this step's output, for a number that is a rate or a timing.
    subs: "tuple[tuple[str, str], ...]" = ()


@dataclass(frozen=True)
class Sh:
    """A shell command, run in the node's home with ``pravaha`` on the PATH. ``run`` may name
    ``{pg}`` (the gateway's port), ``{http}`` and ``{url}``; ``show`` is what the tutorial prints."""

    id: Optional[str]
    run: str
    show: Optional[str] = None
    omit: "tuple[str, ...]" = ()
    subs: "tuple[tuple[str, str], ...]" = ()


@dataclass(frozen=True)
class Py:
    """One of the tutorial's own Python scripts (``docs/tutorials/data/tNN/<script>``), run with the SDK."""

    id: Optional[str]
    script: str
    args: "tuple[str, ...]" = ()


@dataclass(frozen=True)
class Http:
    """A call to the HTTP API, shown as the request and the answer; ``keep`` picks the fields shown."""

    id: Optional[str]
    method: str
    path: str
    body: Optional[dict] = None
    keep: Optional[Callable[[Any], Any]] = None


@dataclass(frozen=True)
class Background:
    """Starts ``step`` (a ``Cli``, ``Sh`` or ``Py``) and goes on; :class:`Join` collects it."""

    name: str
    step: Any
    settle: float = 1.5  # seconds for it to have opened its stream before the next step writes


@dataclass(frozen=True)
class Join:
    """Waits for a :class:`Background` step to end (``timeout`` seconds, then it is stopped) and captures it."""

    name: str
    id: Optional[str]
    timeout: float = 60


@dataclass(frozen=True)
class Until:
    """Repeats ``pravaha <argv>`` until its output matches ``pattern`` (a regex), or fails the run.
    ``argv`` may instead be a shell command (a string), run as a :class:`Sh` step's ``run`` is."""

    argv: "tuple[str, ...] | str"
    pattern: str
    timeout: float = 90


@dataclass(frozen=True)
class Up:
    """Starts the :class:`Container` named ``name`` that was declared ``later``."""

    name: str


@dataclass(frozen=True)
class Sleep:
    seconds: float


@dataclass(frozen=True)
class Restart:
    """Stops the node and starts it again: same home, same ports, same configuration. ``crash`` kills
    it (``kill -9``: no shutdown, no last checkpoint) instead of stopping it; ``during`` is a step
    taken while it is down (a change to a table it follows, a row appended to a file)."""

    crash: bool = False
    during: Any = None


@dataclass(frozen=True)
class Open:
    """Screenshots only: the browser opens ``path`` and waits for ``ready`` (JavaScript)."""

    path: str
    ready: str = "true"


@dataclass(frozen=True)
class Snap:
    """Screenshots only: after ``js`` runs and ``ready`` holds, the picture ``img/tNN/<name>.png``."""

    name: str
    ready: str = "true"
    js: Optional[str] = None
    timeout: float = 30


@dataclass(frozen=True)
class Ui:
    """What a reader does in the console. In the screenshot run the browser does it (``js``, then
    ``ready``); in a capture run ``instead`` does the same through the API or the CLI, so both runs
    leave the node in the same state."""

    js: str
    ready: str
    instead: Any
    timeout: float = 30


# ---------------------------------------------------------------------------------- the run


@dataclass
class Run:
    """One scenario against one node: the environment every step sees, and what was captured."""

    scenario: Any
    node: ScratchNode
    pg_port: int = 0
    env: "dict[str, str]" = field(default_factory=dict)
    blocks: "dict[str, str]" = field(default_factory=dict)
    background: "dict[str, tuple[Any, subprocess.Popen[bytes], pathlib.Path]]" = field(default_factory=dict)
    token: str = ""
    #: Each container's real name and port, by the name the tutorial gives it.
    services: "dict[str, tuple[str, int]]" = field(default_factory=dict)


def load(name: str) -> Any:
    """The scenario module ``tools/tutorials/<name>.py``."""
    path = SCENARIOS / f"{name}.py"
    spec = importlib.util.spec_from_file_location(f"tutorial_{name}", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.NAME = name
    return module


def scenarios() -> "list[str]":
    return sorted(p.stem for p in SCENARIOS.glob("t[0-9][0-9].py"))


def _jar(coordinate: str) -> pathlib.Path:
    """``group:artifact:version`` in the local Maven repository (the build has it)."""
    group, artifact, version = coordinate.split(":")
    for repo in (REPO / ".m2-local", pathlib.Path.home() / ".m2" / "repository"):
        jar = repo / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.jar"
        if jar.is_file():
            return jar
    raise NodeUnavailable(f"{coordinate} is not in the local Maven repository; build the project once")


def _docker() -> "list[str]":
    """``docker``, or ``sg docker -c docker`` for a session started before its user joined the group."""
    if subprocess.run(["docker", "ps", "-q"], capture_output=True).returncode == 0:
        return ["docker"]
    return ["sg", "docker", "-c", "docker"]


def _docker_run(docker: "list[str]", args: "list[str]", **kw: Any) -> "subprocess.CompletedProcess[bytes]":
    if docker[0] == "sg":
        return subprocess.run(["sg", "docker", "-c", " ".join(["docker", *(_shell(a) for a in args)])], **kw)
    return subprocess.run([*docker, *args], **kw)


def _shell(word: str) -> str:
    return word if re.fullmatch(r"[A-Za-z0-9_./:=,@+%-]+", word) else "'" + word.replace("'", "'\\''") + "'"


def start_containers(run: Run) -> None:
    """The scenario's containers, each on a free port, ready; recorded in :attr:`Run.services`."""
    specs: "tuple[Container, ...]" = run.scenario.NODE.containers
    if not specs:
        return
    if _docker_run(_docker(), ["ps", "-q"], capture_output=True).returncode != 0:
        raise NodeUnavailable("this tutorial needs Docker, and `docker ps` does not answer here")
    for spec in specs:
        if not spec.later:
            start_container(run, spec)


def start_container(run: Run, spec: Container) -> None:
    """One container, on a free port, ready; recorded in :attr:`Run.services`."""
    docker = _docker()
    taken = {run.node.http_port, run.node.flight_port, run.pg_port, *(p for _, p in run.services.values())}
    port = free_port(taken)
    name = f"{spec.name}-{os.getpid()}"

    def fill(text: str) -> str:
        return text.replace("{port}", str(port)).replace("{home}", str(run.node.home))

    # Recorded before it runs, so a failure below still removes it.
    run.services[spec.name] = (name, port)
    args = ["run", "-d", "--rm", "--name", name, "-p", f"127.0.0.1:{port}:{spec.inside or port}"]
    for key, value in spec.env:
        args += ["-e", f"{key}={fill(value)}"]
    args += [fill(o) for o in spec.options]
    args += [spec.image, *(fill(a) for a in spec.args)]
    started = _docker_run(docker, args, capture_output=True)
    if started.returncode != 0:
        raise RuntimeError(f"docker run {spec.image} failed:\n{started.stderr.decode(errors='replace')}")
    ready = spec.ready.replace("{container}", name).replace("{port}", str(port))
    deadline = time.monotonic() + spec.timeout
    while subprocess.run(["bash", "-c", ready], env=run.env, capture_output=True).returncode != 0:
        if time.monotonic() > deadline:
            raise RuntimeError(f"{spec.name} ({spec.image}) was not ready in {spec.timeout} s")
        time.sleep(1)


def stop_containers(run: Run) -> None:
    if not run.services:
        return
    docker = _docker()
    for name, _ in run.services.values():
        _docker_run(docker, ["rm", "-f", name], capture_output=True)


def make_node(scenario: Any) -> "tuple[ScratchNode, int]":
    """The scenario's node, not yet started: its configuration, data and plugins in place."""
    spec: Node = scenario.NODE
    pg = 0
    args = [f"--spring.profiles.active={spec.profiles}", *spec.args]
    jvm: "list[str]" = []
    if spec.jars or spec.plugins:
        jvm.append("-Dloader.path=plugins")
    node = ScratchNode(args=(), jvm=tuple(jvm))
    if spec.flight_ca:
        node.scheme = "grpc+tls"
    # As bin/pravaha-server runs a node from PRAVAHA_HOME: the layout (pravaha-home.yaml -- the
    # registry journal, checkpoints, the identity store, logs/pravaha-server.log) under the home,
    # then the home's conf/, which wins.
    node.env = {"PRAVAHA_HOME": str(node.home)}
    if spec.pgwire:
        pg = free_port({node.http_port, node.flight_port})
        args += ["--pravaha.pgwire.enabled=true", "--pravaha.pgwire.host=127.0.0.1", f"--pravaha.pgwire.port={pg}"]
    args.append("--spring.config.additional-location=optional:classpath:/pravaha-home.yaml,file:conf/application.yaml")
    node.args = tuple(args)
    return node, pg


def _source(node: ScratchNode, name: str) -> pathlib.Path:
    """``@dir/file`` is in the node's home (a file a PRE step wrote, as ``pravaha init`` does); else
    under ``docs/tutorials/data/``."""
    return node.home / name[1:] if name.startswith("@") else DATA / name


def lay_out(node: ScratchNode, spec: Node, services: "Optional[dict[str, tuple[str, int]]]" = None) -> None:
    """The configuration and sample data in the node's home, where a reader's ``PRAVAHA_HOME`` has them;
    a container's ``localhost:<port>`` in the configuration is the port it really has."""
    conf = node.home / "conf"
    conf.mkdir(exist_ok=True)
    config = _source(node, spec.config).read_text(encoding="utf-8")
    for container in spec.containers:
        if services and container.name in services:
            real = services[container.name][1]
            config = re.sub(rf"\b(localhost|127\.0\.0\.1):{container.port}\b", rf"\g<1>:{real}", config)
    (conf / "application.yaml").write_text(config, encoding="utf-8")
    for source, place in spec.files:
        target = node.home / place
        target.parent.mkdir(parents=True, exist_ok=True)
        if _source(node, source).is_dir():
            shutil.copytree(_source(node, source), target)
        else:
            shutil.copy(_source(node, source), target)
    if spec.jars:
        (node.home / "plugins").mkdir(exist_ok=True)
        for coordinate in spec.jars:
            shutil.copy(_jar(coordinate), node.home / "plugins")


def _shim(home: pathlib.Path) -> pathlib.Path:
    """``bin/pravaha`` in the node's home: this checkout's CLI, run by this interpreter."""
    bin_dir = home / "bin"
    bin_dir.mkdir(exist_ok=True)
    shim = bin_dir / "pravaha"
    shim.write_text(f'#!/bin/sh\nexec "{sys.executable}" -m pravaha.cli "$@"\n', encoding="utf-8")
    shim.chmod(0o755)
    return bin_dir


def environment(run: Run) -> "dict[str, str]":
    env = {k: v for k, v in os.environ.items() if not k.startswith(("PRAVAHA_", "PG")) and k != "NO_COLOR"}
    env.update(PRAVAHA_URL=run.node.url, PRAVAHA_HTTP=run.node.http, PRAVAHA_CONFIG_DIR=str(run.node.home / "config"),
               PRAVAHA_INSECURE_TOKEN="true", NO_COLOR="1", PYTHONPATH=str(SDK), COLUMNS="110",
               PYTHONIOENCODING="utf-8", PYTHONUNBUFFERED="1", TZ="UTC",
               PATH=os.pathsep.join(filter(None, (str(_shim(run.node.home)), str(pathlib.Path(run.node.java).parent),
                                                   os.environ.get("PATH", "")))))
    if run.token:
        env["PRAVAHA_TOKEN"] = run.token
    if run.scenario.NODE.flight_ca:
        env["PRAVAHA_TLS_CA"] = str(run.node.home / run.scenario.NODE.flight_ca)
    return env


def _command(run: Run, step: Any) -> "tuple[list[str], str, Optional[str]]":
    """The process a step starts, what the tutorial shows for it, and its stdin."""
    if isinstance(step, Cli):
        shown = step.show or "pravaha " + " ".join(_quoted(w) for w in step.argv)
        return [sys.executable, "-m", "pravaha.cli", *step.argv], shown, step.stdin
    if isinstance(step, Sh):
        command = (step.run.replace("{pg}", str(run.pg_port)).replace("{http}", run.node.http)
                   .replace("{url}", run.node.url))
        for service, (container, port) in run.services.items():
            command = command.replace("{" + service + ".port}", str(port)).replace("{" + service + "}", container)
        return ["bash", "-c", command], step.show or step.run, None
    if isinstance(step, Py):
        script = DATA / run.scenario.NAME / step.script
        shown = "python " + " ".join([step.script, *(_quoted(a) for a in step.args)])
        return [sys.executable, str(script), *step.args], shown, None
    raise TypeError(f"not a process step: {step!r}")


def _quoted(word: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9_./:=,@+%-]+", word):
        return word
    if "'" not in word:
        return "'" + word + "'"
    return '"' + word.replace("\\", "\\\\").replace('"', '\\"') + '"'


def execute(run: Run, step: Any, timeout: float = 120) -> "tuple[int, str]":
    argv, _, stdin = _command(run, step)
    done = subprocess.run(argv, cwd=run.node.home, env=run.env, input=stdin.encode() if stdin else None,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    return done.returncode, done.stdout.decode("utf-8", errors="replace")


def block(run: Run, step: Any, code: int, output: str) -> str:
    _, shown, _ = _command(run, step)
    for pattern, replacement in getattr(step, "subs", ()):
        output = re.sub(pattern, replacement, output, flags=re.MULTILINE)
    lines = [line.rstrip() for line in normalise(output, run).rstrip("\n").splitlines()]
    omit = getattr(step, "omit", ())
    lines = [line for line in lines if not any(re.search(p, line) for p in omit)]
    body = "\n".join([f"$ {shown}", *lines] + ([f"(exit {code})"] if code else []))
    return f"```text\n{body}\n```\n"


def _http(run: Run, step: Http) -> "tuple[int, Any]":
    data = json.dumps(step.body).encode() if step.body is not None else None
    request = urllib.request.Request(run.node.http + step.path, data=data, method=step.method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if run.token:
        request.add_header("Authorization", f"Bearer {run.token}")
    try:
        with urllib.request.urlopen(request, timeout=30) as answer:
            status, raw = answer.status, answer.read()
    except urllib.error.HTTPError as refused:
        status, raw = refused.code, refused.read()
    try:
        return status, json.loads(raw) if raw else None
    except ValueError:
        return status, raw.decode("utf-8", errors="replace")


def http_block(run: Run, step: Http, status: int, answer: Any) -> str:
    shown = answer if step.keep is None or status >= 300 else step.keep(answer)
    request = f"{step.method} {step.path}" + (f"  {json.dumps(step.body)}" if step.body is not None else "")
    text = json.dumps(shown, indent=2) if not isinstance(shown, str) else shown
    return f"```text\n{normalise(request, run)}\n{status}\n{normalise(text, run)}\n```\n"


def sign_in(run: Run) -> None:
    """The session token a reader exports as ``PRAVAHA_TOKEN`` after ``pravaha login``."""
    if "users" not in run.scenario.NODE.profiles.split(","):
        return
    code, output = execute(run, Cli(None, ("login", "--user", USER, "--password-stdin"), stdin=PASSWORD + "\n"))
    tokens = re.findall(r"^(prv_s_\S+)$", output, re.MULTILINE)
    if code != 0 or not tokens:
        raise RuntimeError(f"pravaha login exited {code}:\n{output}")
    run.token = tokens[0]
    run.env = environment(run)


def perform(run: Run, step: Any, on_snap: Optional[Callable[[Any], None]] = None) -> None:
    """One step; its output into :attr:`Run.blocks` when it has an id."""
    if isinstance(step, (Cli, Sh, Py)):
        code, output = execute(run, step)
        if step.id:
            run.blocks[step.id] = block(run, step, code, output)
        elif code != 0:
            raise RuntimeError(f"`{_command(run, step)[1]}` exited {code}:\n{output}")
    elif isinstance(step, Http):
        status, answer = _http(run, step)
        if step.id:
            run.blocks[step.id] = http_block(run, step, status, answer)
        elif status >= 300:
            raise RuntimeError(f"{step.method} {step.path} answered {status}: {answer}")
    elif isinstance(step, Background):
        argv, _, _ = _command(run, step.step)
        log = run.node.home / f"background-{step.name}.out"
        process = subprocess.Popen(argv, cwd=run.node.home, env=run.env, stdout=open(log, "wb"),
                                   stderr=subprocess.STDOUT)
        run.background[step.name] = (step.step, process, log)
        time.sleep(step.settle)
    elif isinstance(step, Join):
        inner, process, log = run.background.pop(step.name)
        try:
            code = process.wait(timeout=step.timeout)
        except subprocess.TimeoutExpired:
            process.terminate()
            code = process.wait(timeout=30)
            code = 0 if code in (-15, 143) else code
        output = log.read_text(encoding="utf-8", errors="replace")
        if step.id:
            run.blocks[step.id] = block(run, inner, code, output)
    elif isinstance(step, Until):
        deadline = time.monotonic() + step.timeout
        output = ""
        while time.monotonic() < deadline:
            _, output = execute(run, Sh(None, step.argv) if isinstance(step.argv, str) else Cli(None, step.argv))
            if re.search(step.pattern, output, re.MULTILINE):
                return
            time.sleep(0.5)
        shown = step.argv if isinstance(step.argv, str) else "pravaha " + " ".join(step.argv)
        raise RuntimeError(f"`{shown}` never matched {step.pattern!r}:\n{output}")
    elif isinstance(step, Sleep):
        time.sleep(step.seconds)
    elif isinstance(step, Up):
        start_container(run, next(c for c in run.scenario.NODE.containers if c.name == step.name))
    elif isinstance(step, Restart):
        if step.crash and run.node.process is not None:
            run.node.process.kill()
            run.node.process.wait(timeout=30)
        run.node.stop()
        if step.during is not None:
            perform(run, step.during)
        run.node.start()
        sign_in(run)
    elif isinstance(step, (Open, Snap)):
        if on_snap is not None:
            on_snap(step)
    elif isinstance(step, Ui):
        if on_snap is not None:
            on_snap(step)
        else:
            perform(run, step.instead)
    else:
        raise TypeError(f"unknown step {step!r}")


def play(name: str, on_snap: Optional[Callable[[Any], None]] = None,
         on_start: Optional[Callable[[Run], None]] = None, hold: Optional[pathlib.Path] = None) -> "dict[str, str]":
    """Runs scenario ``name`` from a fresh node to its end; returns its captured blocks.

    ``on_start`` is called once the node runs and the reader is signed in (the screenshot tool
    starts its console there), ``on_snap`` at each ``Open`` and ``Snap``."""
    scenario = load(name)
    node, pg = make_node(scenario)
    run = Run(scenario, node, pg)
    run.env = environment(run)
    try:
        return _play(run, scenario, name, on_snap, on_start, hold)
    finally:
        stop_containers(run)


def _play(run: Run, scenario: Any, name: str, on_snap: Optional[Callable[[Any], None]],
          on_start: Optional[Callable[[Run], None]], hold: Optional[pathlib.Path]) -> "dict[str, str]":
    node = run.node
    try:
        start_containers(run)
        # Before the node: what a reader does with no node running (pravaha init), captured too.
        for step in getattr(scenario, "PRE", ()):
            perform(run, step)
        lay_out(node, scenario.NODE, run.services)
        # Laid out, not started: what a reader prepares in the node's home first (a database file).
        for step in getattr(scenario, "BEFORE_START", ()):
            perform(run, step)
    except BaseException:
        shutil.rmtree(node.home, ignore_errors=True)
        raise
    with node:
        sign_in(run)
        if on_start is not None:
            on_start(run)
        try:
            for step in scenario.STEPS:
                perform(run, step, on_snap)
        except Exception:
            # What the run printed up to the step that failed, and the node's last words: the
            # cause is nearly always in one of them.
            for block_id, text in run.blocks.items():
                print(f"--- {block_id}\n{text}", file=sys.stderr)
            log = node.home / "node.log"
            if log.is_file():
                print("--- node.log (tail)\n" + log.read_text(errors="replace")[-4000:], file=sys.stderr)
            raise
        finally:
            for _, process, _ in run.background.values():
                process.terminate()
        if hold is not None:
            # An author's pause: the node stays up, its environment written out, until the file goes.
            hold.write_text("".join(f"export {k}='{v}'\n" for k, v in run.env.items()
                                    if k.startswith(("PRAVAHA_", "PATH"))) + f"cd {node.home}\n", encoding="utf-8")
            hold.with_suffix(".blocks.md").write_text("".join(
                f"<!-- capture: {k} -->\n{v}<!-- /capture -->\n\n" for k, v in run.blocks.items()), encoding="utf-8")
            print(f"holding: source {hold}; delete it to stop", flush=True)
            while hold.exists() and node.process is not None and node.process.poll() is None:
                time.sleep(1)
        steps = [*getattr(scenario, "PRE", ()), *getattr(scenario, "BEFORE_START", ()), *scenario.STEPS]
        steps = [getattr(s, "during", None) or s for s in steps]
        missing = [s.id for s in steps if getattr(s, "id", None) and s.id not in run.blocks]
        assert not missing, f"{name}: steps with ids that captured nothing: {missing}"
        return run.blocks


# ---------------------------------------------------------------------------------- normalising


def normalise(text: str, run: Run) -> str:
    """What differs between runs and machines, as stable words; the sample data's times are kept."""
    node = run.node
    swaps = [
        (f"127.0.0.1:{node.http_port}", "localhost:18080"),
        (f"127.0.0.1:{node.flight_port}", "localhost:19090"),
        (f"localhost:{node.http_port}", "localhost:18080"),
        (f"localhost:{node.flight_port}", "localhost:19090"),
        (str(node.home / "config"), "~/.config/pravaha"),
        (str(node.home) + "/", ""),
        (str(node.home), "."),
        (sys.executable, "python"),
        (str(SDK), "sdk/python"),
    ]
    if run.pg_port:
        swaps += [(f"127.0.0.1:{run.pg_port}", "127.0.0.1:5433"), (f"port={run.pg_port}", "port=5433"),
                  (f"port {run.pg_port}", "port 5433")]
    if run.token:
        swaps.append((run.token, "<token>"))
    for spec in run.scenario.NODE.containers:
        if spec.name in run.services:
            container, port = run.services[spec.name]
            swaps.append((container, spec.name))
            text = re.sub(rf"(?<![\d.]){port}(?!\d)", str(spec.port), text)
    for old, new in swaps:
        text = text.replace(old, new)
    date = run.scenario.NODE.data_date
    patterns = [
        (r"\bprv_s_[A-Za-z0-9_-]{10,}", "<token>"),
        # An API key, shown once when it is made: prv_<environment>_<key id>_<secret>.
        (r"\bprv_[a-z]+_[0-9a-f]{12}_[A-Za-z0-9_-]{10,}", "<key>"),
        (r"\bkey [0-9a-f]{12}\b", "key <id>"),
        (r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", "<id>"),
        (r"\bdbg-[0-9a-f]+-\d+\b", "<session>"),
        (r"(?<![\d.])\d+\.\d+\.\d+(?:-SNAPSHOT)?(?![\d.])", "<version>"),
        # Wall-clock instants; the sample data's own (its date) are what the tutorial is about.
        (r"\b(?!" + re.escape(date) + r")\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)?",
         "<time>"),
        # A time something took; not an amount the configuration states ("120000 ms of lateness").
        (r"\b\d+(?:\.\d+)? (ms|us|s)\b(?! of\b)", r"N \1"),
        (r"\bN (ms|s) ago\b", r"N \1 ago"),
        (r"\(in \d+(?:\.\d+)? (h|d|min)\)", r"(in N \1)"),
        (r"\b\d+ rows/s\b(?!\))", "N rows/s"),
        (r"JAVA_HOME=\S+?:", "JAVA_HOME=$JAVA_HOME:"),
        (r"Java \d+", "Java 21"),
        (r"pyarrow \S+, with Flight", "pyarrow <version>, with Flight"),
        (r"Python <version>", "Python 3.x"),
    ]
    for pattern, replacement in patterns:
        text = re.sub(pattern, _keeping_columns(replacement), text, flags=re.MULTILINE)
    # The node's "sources bound" line names each binding's options in no particular order.
    text = re.sub(r"^(sources bound: .*)$", lambda m: re.sub(
        r"\[([^\[\]]+)\]", lambda o: "[" + ", ".join(sorted(o.group(1).split(", "))) + "]", m.group(1)),
        text, flags=re.MULTILINE)
    return text


def _keeping_columns(replacement: str) -> "Callable[[re.Match[str]], str]":
    """``<id>`` and ``<time>`` padded to what they stand for when a table column follows, so it stays aligned."""
    def swap(match: "re.Match[str]") -> str:
        out = match.expand(replacement)
        if out in ("<id>", "<time>") and match.string[match.end():match.end() + 2] == "  ":
            return out.ljust(len(match.group(0)))
        return out
    return swap


# ---------------------------------------------------------------------------------- the docs


def document(name: str) -> pathlib.Path:
    return TUTORIALS / load(name).DOC


def rewrite(text: str, blocks: "dict[str, str]") -> str:
    def swap(match: "re.Match[str]") -> str:
        block_id = match.group("id")
        if block_id not in blocks:
            raise KeyError(f"<!-- capture: {block_id} --> names no captured step")
        return match.group(1) + blocks[block_id] + match.group(4)

    return _BLOCK.sub(swap, text)


def documented(name: str) -> "list[str]":
    return [m.group("id") for m in _BLOCK.finditer(document(name).read_text(encoding="utf-8"))]


def declared(name: str) -> "list[str]":
    """The ids a scenario captures, in order (a Join's id is its background step's capture)."""
    scenario = load(name)
    steps = [*getattr(scenario, "PRE", ()), *getattr(scenario, "BEFORE_START", ()), *scenario.STEPS]
    steps = [getattr(s, "during", None) or s for s in steps]
    return [s.id for s in steps if getattr(s, "id", None)]


def stale(name: str, blocks: "dict[str, str]") -> "list[str]":
    doc = document(name)
    text = doc.read_text(encoding="utf-8")
    problems = []
    fresh = rewrite(text, blocks)
    if fresh != text:
        problems.append("".join(difflib.unified_diff(
            text.splitlines(keepends=True), fresh.splitlines(keepends=True),
            str(doc.relative_to(REPO)), str(doc.relative_to(REPO)) + " (captured)", n=2)))
    for missing in sorted(set(blocks) - set(documented(name))):
        problems.append(f"{name}: capture {missing!r} is made but {doc.name} has no <!-- capture: {missing} -->")
    return problems


def main(argv: "Optional[list[str]]" = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a captured block is stale")
    parser.add_argument("--only", action="append", metavar="tNN", help="only this tutorial (repeatable)")
    parser.add_argument("--list", action="store_true", help="the tutorials and their captures")
    parser.add_argument("--hold", metavar="FILE", help="after the last step keep the node up, its environment "
                        "in FILE, until FILE is deleted (writing a tutorial)")
    args = parser.parse_args(argv)
    names = [n for n in scenarios() if not args.only or n in args.only]
    if args.list:
        for name in names:
            print(name, load(name).DOC, " ".join(declared(name)))
        return 0
    failed = 0
    for name in names:
        try:
            blocks = play(name, hold=pathlib.Path(args.hold) if args.hold else None)
        except NodeUnavailable as exc:
            print(f"tutorial_captures: {exc}", file=sys.stderr)
            return 2
        if args.check:
            problems = stale(name, blocks)
            for problem in problems:
                print(problem, file=sys.stderr)
            failed += bool(problems)
            continue
        doc = document(name)
        text = doc.read_text(encoding="utf-8")
        fresh = rewrite(text, blocks)
        if fresh != text:
            doc.write_text(fresh, encoding="utf-8")
            print(f"rewrote {doc.relative_to(REPO)}")
        else:
            print(f"{doc.relative_to(REPO)} unchanged")
    if failed:
        print("tutorial output is stale: run `.venv/bin/python tools/tutorial_captures.py` in sdk/python",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
