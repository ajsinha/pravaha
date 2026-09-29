"""What the browser tests share: a real console on a real port, and a real Chrome to drive it.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The console under test is the application ``create_app`` builds -- routes, templates, the
session gate, the vendored islands -- served by uvicorn on a loopback port, with exactly one
object replaced: the engine adapter, by the same fake the product tests use. Chrome is the
one on this machine, driven by ``cdp.py``. When there is no Chrome, every browser test skips
and says so; ``make test`` on a machine without one is still a green run of everything else.

Determinism, because the screenshots are compared with committed baselines: the engine's
answers are fixed, the browser's clock is pinned (``FIXED_CLOCK``), animations and
transitions are switched off, fonts are the vendored ones, and the few things on a page that
are *about* the passage of time -- chart canvases, "updated 3 s ago" -- are masked by name.
"""
from __future__ import annotations

import contextlib
import dataclasses
import json
import os
import pathlib
import queue
import re
import socket
import tempfile
import threading
import time
from collections.abc import Iterator

import pytest
from cdp import Browser, Page, find_chrome
from fake_engine import FakeEngine
from fake_identity import ADMIN, ADMIN_PASSWORD

from core.config.properties_configurator import PropertiesConfigurator
from run_pravaha_web import create_app

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
#: Who the browser tests sign in as (ADR-052): the engine's bootstrap administrator.
USERNAME = ADMIN
PASSWORD = ADMIN_PASSWORD

#: 2026-09-19 09:30:00 UTC. Every page a baseline was taken of believes it is this moment.
FIXED_CLOCK_MS = 1789810200000
#: The same moment, for the fake engine's identity authority: sessions, keys and sign-ins are
#: stamped with it, so the account and people screens photograph the same way every time.
FIXED_CLOCK_S = FIXED_CLOCK_MS / 1000

#: Runs before any page script: a pinned clock that still advances (timers, debounces and
#: the palette's own waits keep working), and no animation or transition anywhere.
DETERMINISM = """
(() => {
  const base = __BASE__, started = performance.now(), Real = Date;
  const now = () => base + Math.floor(performance.now() - started);
  class Pinned extends Real {
    constructor(...args) { if (args.length) super(...args); else super(now()); }
    static now() { return now(); }
  }
  Pinned.UTC = Real.UTC; Pinned.parse = Real.parse;
  window.Date = Pinned;
  const style = () => {
    const css = document.createElement('style');
    css.setAttribute('data-test', 'determinism');
    css.textContent = '*,*::before,*::after{animation:none!important;transition:none!important;' +
      'caret-color:transparent!important;scroll-behavior:auto!important}';
    (document.head || document.documentElement).appendChild(css);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', style); else style();
})();
""".replace("__BASE__", str(FIXED_CLOCK_MS))


class BrowserEngine(FakeEngine):
    """The product tests' fake, with a live tail a test can push changes into.

    ``fresh=True`` is an engine with nothing registered and no stream declared -- a first run,
    which is what sends a signed-in person to onboarding.
    """

    def __init__(self, fresh: bool = False, follow_lifecycle: bool = False) -> None:
        # Unlimited sessions per person: a module keeps a signed-in tab per theme, viewport and
        # density -- twenty of them, all the administrator -- and the engine's rule of three
        # would sign the first seventeen out. The rule itself is tested in test_identity.
        super().__init__(clock=lambda: FIXED_CLOCK_S, max_sessions=None)
        _seed_people(self)
        self._tails: list[queue.Queue] = []
        self._tails_lock = threading.Lock()
        self.closed = threading.Event()
        self.lifecycle_calls: list[tuple[str, str]] = []
        #: Whether pause, resume and drop change what the engine then lists, as the real one's
        #: do. Off for the shared console, whose screens the audit and the budget expect fixed.
        self.follow_lifecycle = follow_lifecycle
        #: What a view answers, by name, when a journey needs two views to differ; any other
        #: view answers ``rows``.
        self.view_rows: dict[str, list[list]] = {}
        #: Every subscription the console opened, as (view, filters), in order.
        self.tails_opened: list[tuple[str, object]] = []
        if fresh:
            self._queries = []
            self.streams_list = []
            self.metrics_text = ""

    def register(self, name, sql, keys, sink=None, retention=None):
        row = super().register(name, sql, keys, sink, retention)
        self._queries.append(row)
        object.__setattr__(row, "_shared", False)
        return row

    def lifecycle(self, action, name):
        super().lifecycle(action, name)
        self.lifecycle_calls.append((action, name))
        if not self.follow_lifecycle:
            return
        for i, q in enumerate(self._queries):
            if q.name != name:
                continue
            if action == "drop":
                del self._queries[i]
            else:
                shared = getattr(q, "_shared", False)
                row = dataclasses.replace(q, state="PAUSED" if action == "pause" else "RUNNING")
                object.__setattr__(row, "_shared", shared)
                self._queries[i] = row
            return

    def query_typed(self, sql, parameters=None):
        self._check()
        self.queries_seen.append((sql, parameters))
        named = re.search(r"\bFROM\s+([A-Za-z_][A-Za-z0-9_]*)", sql, re.IGNORECASE)
        rows = self.view_rows.get(named.group(1), self.rows) if named else self.rows
        return ["txn_id", "user_id", "amount"], [list(r) for r in rows], ["int64", "string", "int64"]

    def mirror(self, view, filters=None):
        # Registered before the snapshot is handed over, as the engine registers a snapshot
        # subscriber in the step that takes its snapshot: a change committed after this point
        # reaches it, and one before is in the snapshot.
        self._check()
        mine: queue.Queue = queue.Queue()
        with self._tails_lock:
            self._tails.append(mine)
            # Recorded like a plain tail's, so a journey can see which filters reached the engine.
            self.tails_opened.append((view, filters))
        try:
            # Filtered by the engine, as the real subscription is: the console never filters.
            rows = [r for r in self.snapshot_rows()
                    if all(str(r.get(k)) == v for k, v in (filters or {}).items())]
            yield ("snapshot", rows, 1)
            frontier = 1
            while not self.closed.is_set():
                try:
                    change = mine.get(timeout=0.2)
                except queue.Empty:
                    continue
                frontier += 1
                yield ("commit", [change], frontier)
        finally:
            with self._tails_lock:
                self._tails.remove(mine)

    def commit(self, change: dict) -> None:
        """What the engine would publish when a view changes: to every open subscription.

        Waits for one to exist: the browser's stream is open before the console's feed thread
        has taken its first step into ``tail``, and a change committed in that gap would be
        a change nobody was subscribed to -- true of the real engine, and not what a test means.
        """
        deadline = time.monotonic() + 5
        while not self._tails and time.monotonic() < deadline:
            time.sleep(0.02)
        with self._tails_lock:
            for tail in self._tails:
                tail.put(change)

    def tail(self, view, filters=None):
        # One queue per subscription, like the engine's own: a subscription the console has
        # already released (its thread is still parked here) must not swallow a change meant
        # for the one that replaced it.
        self._check()
        mine: queue.Queue = queue.Queue()
        with self._tails_lock:
            self._tails.append(mine)
            self.tails_opened.append((view, filters))
        try:
            while not self.closed.is_set():
                try:
                    yield mine.get(timeout=0.2)
                except queue.Empty:
                    continue
        finally:
            with self._tails_lock:
                self._tails.remove(mine)


def _seed_people(engine: FakeEngine) -> None:
    """The people, keys and sessions the account and admin screens are photographed with: fixed
    ids and fixed times, so a baseline is the same picture every run."""
    day = 86400
    now = FIXED_CLOCK_S
    identity = engine.identity
    identity.add_user("ann", "Ann-password-12", ["analyst"], display_name="Ann Analyst", tenant="risk")
    identity.add_user("carol", "Carol-password-12", ["operator", "developer"], display_name="Carol Operator")
    identity.add_user("dave", "Dave-password-12", ["analyst"], display_name="Dave Former")
    identity.users["dave"].status = "disabled"
    identity.users["carol"].last_login = now - 3600
    identity.seed_key("3f9a1c2b7d10", ADMIN, "ci-deploy", ["operator"], created=now - 30 * day,
                      expires=now + 60 * day, last_used=now - 3600)
    identity.seed_key("a17e55d0c942", "ann", "notebook", ["analyst"], created=now - 80 * day,
                      expires=now + 10 * day)
    identity.seed_key("c0ffee123456", "carol", "etl", ["operator"], created=now - 85 * day,
                      expires=now + 5 * day, last_used=now - 600, superseded_by="d00d00123456")
    identity.seed_key("d00d00123456", "carol", "etl", ["operator"], created=now - day,
                      expires=now + 89 * day, last_used=now - 60)
    identity.seed_session("s0c0ffee0001", "carol", created=now - 2 * 3600, seen=now - 300)
    identity.seed_session("s0c0ffee0002", "carol", created=now - 40 * 60, seen=now - 60)


#: The assistant's configuration every browser console starts with (ADR-058 phase 3): the SDK's
#: scripted ``fake`` provider, so nothing leaves the machine, and fixed stamps for the screenshots.
_EXPLAINED = json.dumps({"summary": "Keeps each transaction over 100, keyed by its id.",
                         "steps": ["Reads txn.", "Keeps the rows whose amount is over 100."], "notes": []})
ASSIST_SEED = {
    "version": 3, "changed_at": "2026-09-19T09:00:00Z", "changed_by": "admin",
    "default_profile": "explain",
    "providers": [{"id": "local", "type": "fake"},
                  {"id": "gateway", "type": "openai-compatible", "endpoint": "http://127.0.0.1:9/v1"}],
    "models": [{"id": "drafter", "provider": "local", "model": "fake-large", "options": {"replies": [_EXPLAINED]}},
               {"id": "explainer", "provider": "local", "model": "fake-small", "options": {"replies": [_EXPLAINED]}},
               {"id": "spare", "provider": "gateway", "model": "llama3.1:70b", "enabled": False}],
    "profiles": {"draft": ["drafter"], "explain": ["explainer", "drafter"]},
    "budgets": {"per_user_daily_tokens": 200000, "per_request_max_tokens": 16000},
}


def _free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


class Console:
    """One console application on a loopback port, stopped by ``close``."""

    def __init__(self, engine: BrowserEngine, *, default_role: str = "operator",
                 assist: dict | None = None) -> None:
        import uvicorn

        self.engine = engine
        config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
        config.set("console.session_secret", "browser-test-session-secret")
        config.set("console.secure_cookies", "false")
        config.set("ui.default_role", default_role)
        # The component gallery is a page the audit and the screenshots cover like any other.
        config.set("ui.component_gallery", "true")
        # ADR-058 phase 3: the assistant's files, per console, seeded with a fixed configuration
        # so its screens are photographed the same way every run, and never a person's own.
        assist_dir = pathlib.Path(tempfile.mkdtemp(prefix="pravaha-browser-assist-"))
        (assist_dir / "assist.json").write_text(json.dumps(assist or ASSIST_SEED), encoding="utf-8")
        config.set("assist.config", str(assist_dir / "assist.json"))
        config.set("assist.usage", str(assist_dir / "usage.json"))
        config.set("assist.log", str(assist_dir / "log.jsonl"))
        config.set("assist.watch_seconds", "3600")
        self.app = create_app(config, engine=engine)
        self.port = _free_port()
        self.base = f"http://127.0.0.1:{self.port}"
        self._server = uvicorn.Server(uvicorn.Config(
            self.app, host="127.0.0.1", port=self.port, log_level="warning", lifespan="off",
            timeout_graceful_shutdown=1, access_log=False))
        self._thread = threading.Thread(target=self._server.run, name=f"console-{self.port}", daemon=True)
        self._thread.start()
        deadline = time.monotonic() + 15
        while not self._server.started:
            if time.monotonic() > deadline or not self._thread.is_alive():
                raise RuntimeError("the console did not start for the browser tests")
            time.sleep(0.02)

    def url(self, path: str) -> str:
        return self.base + path

    def close(self) -> None:
        self.engine.closed.set()
        self._server.should_exit = True
        self._thread.join(timeout=10)


def sign_in(page: Page, console: Console, role: str | None = None, next_path: str = "/home",
            username: str = USERNAME, password: str = PASSWORD) -> None:
    """Through the real sign-in form: the username and password typed, the button pressed.

    ``role`` is the landing persona, which is no longer on the sign-in form (ADR-052): it is
    chosen after signing in, through the account menu's own form, as a person chooses it; and
    then the browser goes where it was going, as it would have from the sign-in.
    """
    page.goto(console.url("/login?next=" + ("/account" if role else next_path)))
    page.focus("#username")
    page.type(username)
    page.focus("#password")
    page.type(password)
    page.wait_for_navigation(lambda: page.click("form[action='/login'] button[type=submit]"))
    if role:
        page.wait_for_navigation(lambda: page.eval(
            f"(() => {{ const input = document.querySelector('#account-landing-form input[value={role}]');"
            f" input.checked = true; input.form.submit(); return true; }})()"))
        page.goto(console.url(next_path))


def settled(page: Page) -> None:
    """The page has loaded, its islands have mounted and the network has gone quiet."""
    page.wait_for("document.readyState === 'complete'")
    page.settle()


# ====================================================================== fixtures

@pytest.fixture(scope="session")
def chrome() -> Iterator[Browser]:
    if os.environ.get("PRAVAHA_BROWSER_TESTS", "1") == "0":
        pytest.skip("browser tests switched off (PRAVAHA_BROWSER_TESTS=0)")
    executable = find_chrome()
    if executable is None:
        pytest.skip("no Chrome or Chromium on this machine (set PRAVAHA_CHROME to its path); "
                    "the browser journeys, the axe audit, the screenshots and the budget did not run")
    browser = Browser(executable)
    try:
        yield browser
    finally:
        browser.close()


@pytest.fixture(scope="session")
def console() -> Iterator[Console]:
    """The console over the standard fake: three queries, one stream, two sinks."""
    server = Console(BrowserEngine())
    try:
        yield server
    finally:
        server.close()


@contextlib.contextmanager
def fresh_console(**kwargs) -> Iterator[Console]:
    server = Console(BrowserEngine(fresh=True), **kwargs)
    try:
        yield server
    finally:
        server.close()


@contextlib.contextmanager
def own_console(**kwargs) -> Iterator[Console]:
    """The standard fake, for one journey alone, with pause, resume and drop taking effect:
    a journey that changes what is registered must not change the screens other tests see."""
    server = Console(BrowserEngine(follow_lifecycle=True), **kwargs)
    try:
        yield server
    finally:
        server.close()


@pytest.fixture
def page(chrome: Browser) -> Iterator[Page]:
    tab = chrome.new_page()
    tab.before_every_document(DETERMINISM)
    try:
        yield tab
    finally:
        tab.close()


def theme_script(theme: str) -> str:
    """Chosen the way a person chooses it -- the picker's own localStorage key."""
    return f"try{{localStorage.setItem('pravaha.theme', {json.dumps(theme)})}}catch(e){{}}"


def density_script(density: str) -> str:
    """Chosen the way a person chooses it -- the density toggle's own localStorage key."""
    return f"try{{localStorage.setItem('pravaha.density', {json.dumps(density)})}}catch(e){{}}"


# ====================================================================== the pages

#: Every screen the console renders, as (name, path, needs a session, ready when).
#: "Ready" is the island's own evidence that it mounted -- Monaco's editor, ECharts' canvas,
#: the onboarding steps -- so nothing is audited or photographed half-built.
PAGES: list[tuple[str, str, bool, str]] = [
    ("landing", "/", False, "window.PravahaLanding && (PravahaLanding.stills() > 0 || PravahaLanding.frames() > 0)"),
    ("about", "/about", False, "true"),
    ("help", "/help", False, "true"),
    ("help-topic", "/help/quickstart", False, "true"),
    ("help-topic-page", "/help/topics/first-view", False, "true"),
    ("help-connector", "/help/topics/source-jdbc", False, "true"),
    ("help-search", "/help/search?q=watermark", False, "true"),
    ("help-codes", "/help/codes", False, "true"),
    ("help-guides", "/help/guides", False, "true"),
    ("tutorials", "/tutorials", False, "true"),
    ("tutorial", "/tutorials/first-maintained-view", False, "true"),
    ("case-studies", "/help/case-studies", False, "true"),
    ("case-study", "/help/case-studies/trade-processing", False, "true"),
    ("help-code", "/help/codes/PRV-2050", False, "true"),
    # The competitive landscape, drawn whole from docs/COMPETITIVE_LANDSCAPE.md in MAYA's form:
    # photographed like About, first screen only (the hero and the landscape's opening), because
    # its layout is the console's. The FAQ is audited in every theme and not photographed: its
    # pixels move with the prose.
    ("competitive", "/about/competitive", False, "true"),
    ("help-faq", "/help/topics/faq", False, "true"),
    ("login", "/login", False, "true"),
    # ADR-052's public page for a person holding a reset token an administrator issued.
    ("login-reset", "/login/reset", False, "true"),
    ("start", "/start", True, "document.querySelector('#start-app h2')"),
    ("catalog", "/catalog", True, "true"),
    ("catalog-queries", "/catalog?tab=queries", True, "true"),
    ("catalog-sinks", "/catalog?tab=sinks", True, "true"),
    ("stream", "/catalog/streams/txn", True, "true"),
    ("views", "/views", True, "true"),
    ("view", "/views/big_txn", True, "true"),
    ("view-lookup", "/views/big_txn?key=user_id&value=u1", True, "true"),
    ("live", "/views/big_txn/live", True,
     "document.querySelector('#live-chart canvas') && document.getElementById('live-state').dataset.state === 'fresh'"),
    ("operations", "/operations", True, "document.querySelector('#chart-rate canvas')"),
    ("workbench", "/workbench?query=big_txn", True,
     "document.querySelector('.monaco-editor .view-line') && document.querySelector('.validity.ok')"),
    # The Compare panel (23.7), compared on arrival: SQL diff, both plans marked, the lists.
    ("workbench-diff", "/workbench?query=big_txn&panel=diff&against=hot", True,
     ("document.querySelectorAll('#diff-result svg g.plan-node').length === 6"
      " && document.querySelector('.diff-sql[data-diff-ready=yes] .monaco-diff-editor .view-line')")),
    ("queries", "/queries", True, "true"),
    ("query", "/queries/big_txn", True, "true"),
    # B9's blue/green screen as an engine with nothing being replaced shows it: the state
    # every query is in most of the time, and the form that starts the first one.
    ("replacement", "/queries/big_txn/replacement", True, "document.querySelector('#rep-none')"),
    # B9's debugger (23.9) as a node debugging nothing shows it: what a fork is, what it
    # cannot touch, and the positions this node could still start one from.
    ("debug", "/queries/big_txn/debug", True, "document.querySelector('#dbg-fork-form')"),
    ("overview", "/overview", True, "true"),
    ("plugins", "/plugins", True, "true"),
    ("admin-access", "/admin/access", True, "true"),
    ("admin-audit", "/admin/audit", True, "true"),
    ("admin-audit-filtered", "/admin/audit?principal=carol&decision=deny", True, "true"),
    ("admin-tenants", "/admin/tenants", True, "true"),
    # ADR-052: the signed-in person's own account, the password page, and the people screens.
    # The sessions screen is photographed narrowed to carol, whose two sessions are seeded with
    # fixed times: the administrator's own sessions are one per signed-in tab, and how many tabs
    # have signed in by the time a shot is taken depends on which tests ran first.
    ("account", "/account", True, "document.querySelector('#key-create-form')"),
    ("account-password", "/account/password", True, "true"),
    ("admin-users", "/admin/users", True, "document.querySelector('#users-table')"),
    ("admin-keys", "/admin/keys", True, "document.querySelector('#keys-table')"),
    ("admin-sessions", "/admin/sessions?user=carol", True, "document.querySelector('#sessions-table')"),
    # ADR-058 phase 3: the assistant's models, over the configuration every browser console is
    # seeded with (ASSIST_SEED): two providers, three models, two profiles, budgets.
    ("admin-ai-models", "/admin/ai-models", True, "document.querySelector('#models-table')"),
    ("not-found", "/views/no_such_view", True, "true"),
    ("components", "/_components", True, "document.querySelector('#state-unauthorized button')"),
]

#: Pages whose body is a repository document included verbatim. They are audited like every
#: other page, but not photographed: their pixels change whenever the documentation does,
#: and a baseline that breaks on a README edit teaches people to regenerate without looking.
#: The help's own pages -- the index, a topic, a connector page, search, the guides browser --
#: About and the competitive landscape ARE photographed: their layout is the console's, and only the
#: first screen is taken.
DOCUMENT_PAGES = {"help-topic", "tutorial", "help-code", "help-codes", "help-faq"}

AXE = (CONSOLE_ROOT / "tests" / "vendor" / "axe-core" / "axe.min.js").read_text(encoding="utf-8")

#: WCAG 2.0, 2.1 and 2.2 at A and AA -- the standard design 23.14 names -- plus the
#: landmark and heading rules axe files under best practice, because "every page has one
#: main, and everything sits in a landmark" is what makes a screen reader's region list
#: usable, and it is cheap to hold.
AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]
AXE_BEST_PRACTICE = ["region", "landmark-one-main", "landmark-no-duplicate-banner",
                     "landmark-no-duplicate-contentinfo", "landmark-unique", "page-has-heading-one",
                     "heading-order", "bypass", "aria-dialog-name", "empty-heading"]


def axe(page: Page) -> list[dict]:
    """Every violation axe finds on the page as it is now, one entry per rule."""
    if not page.eval("typeof window.axe === 'object'"):
        page.eval(AXE + "\n;true")
    rules = json.dumps({"runOnly": {"type": "tag", "values": AXE_TAGS}, "resultTypes": ["violations"]})
    extra = json.dumps({"runOnly": {"type": "rule", "values": AXE_BEST_PRACTICE},
                        "resultTypes": ["violations"]})
    script = f"""(async () => {{
        const shape = (r) => r.violations.map((v) => ({{ id: v.id, impact: v.impact, help: v.help,
            nodes: v.nodes.slice(0, 6).map((n) => ({{ target: n.target.join(' '), summary: n.failureSummary }})),
            count: v.nodes.length }}));
        const a = await axe.run(document, {rules});
        const b = await axe.run(document, {extra});
        return shape(a).concat(shape(b));
    }})()"""
    return list(page.eval(script, timeout=60) or [])


def describe(violations: list[dict]) -> str:
    lines = []
    for v in violations:
        lines.append(f"  [{v['impact']}] {v['id']} x{v['count']}: {v['help']}")
        for node in v["nodes"]:
            summary = (node["summary"] or "").strip().splitlines()
            lines.append(f"      {node['target']}: {summary[-1] if summary else ''}")
    return "\n".join(lines)


#: Compares two PNGs inside Chrome: decode both onto canvases, count pixels whose channels
#: differ by more than ``threshold``, and paint a diff image (changed pixels red over a faded
#: copy of the baseline). Decoding a 1280x800 PNG in pure Python takes seconds; here it is
#: milliseconds, and the browser that took the screenshot is already running.
_COMPARE = """(async (a, b, threshold) => {
  const load = (src) => new Promise((ok, fail) => { const i = new Image(); i.onload = () => ok(i);
    i.onerror = () => fail(new Error('undecodable PNG')); i.src = 'data:image/png;base64,' + src; });
  const [x, y] = await Promise.all([load(a), load(b)]);
  const w = Math.max(x.width, y.width), h = Math.max(x.height, y.height);
  const pixels = (img) => { const c = new OffscreenCanvas(w, h); const g = c.getContext('2d');
    g.drawImage(img, 0, 0); return g.getImageData(0, 0, w, h).data; };
  const p = pixels(x), q = pixels(y);
  const out = new OffscreenCanvas(w, h); const g = out.getContext('2d'); const d = g.createImageData(w, h);
  let changed = 0;
  for (let i = 0; i < p.length; i += 4) {
    const diff = Math.max(Math.abs(p[i] - q[i]), Math.abs(p[i + 1] - q[i + 1]), Math.abs(p[i + 2] - q[i + 2]));
    if (diff > threshold) { changed++; d.data[i] = 255; d.data[i + 1] = 0; d.data[i + 2] = 0; d.data[i + 3] = 255; }
    else { const grey = (p[i] + p[i + 1] + p[i + 2]) / 3; d.data[i] = d.data[i + 1] = d.data[i + 2] = 200 + grey / 5; d.data[i + 3] = 255; }
  }
  g.putImageData(d, 0, 0);
  const blob = await out.convertToBlob({ type: 'image/png' });
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let bin = ''; for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return { changed, total: w * h, sameSize: x.width === y.width && x.height === y.height, diff: btoa(bin) };
})"""


def compare_png(page: Page, baseline: bytes, actual: bytes, threshold: int = 24) -> dict:
    """``{changed, total, sameSize, diff}`` -- ``diff`` is a PNG, base64."""
    import base64

    a = json.dumps(base64.b64encode(baseline).decode())
    b = json.dumps(base64.b64encode(actual).decode())
    return dict(page.eval(f"{_COMPARE}({a}, {b}, {threshold})", timeout=60))


def open_page(page: Page, console: Console, path: str, ready: str, timeout: float = 20.0) -> None:
    page.goto(console.url(path))
    settled(page)
    page.wait_for(ready, timeout=timeout)
    page.settle(quiet_ms=200)
