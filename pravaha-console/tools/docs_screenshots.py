"""Regenerates the screenshots the documentation shows, from a declared list, so each can be made again.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Every image under ``docs/assets/screenshots/`` is one :data:`SHOTS` entry, made one of two ways:

* ``fake`` -- the console's browser-test harness (``tests/browser_harness.py``): the real application
  and real Chrome over the **fake engine** the product tests use, so names and numbers are the fake's
  (``big_txn``, ``hot``) and the same every run. The console screens in
  ``docs/design/architecture/console-screens.md`` and ``CONSOLE_DEVELOPMENT.md`` are these.
* ``node`` -- a **scratch node** (``sdk/python/tools/cli_captures.py``'s: the server jar the build
  made, free ports that are never the defaults, a temporary home, profiles ``dev,users``) with a
  ``txn`` stream declared in its configuration, and a console over it signed in as ``admin``. The two
  IDE-guide shots are these. The scratch ports on the page are shown as the standard ones (19090,
  18080), as the CLI captures normalise them, because the guide describes a node on the defaults.

Every shot is 1400x900, the Crimson theme, comfortable density, the browser's clock pinned and
animations off. ``tests/test_doc_images.py`` fails on an image a doc shows that is missing, an image
no doc shows, and one this list cannot make::

    cd pravaha-console
    .venv/bin/python tools/docs_screenshots.py --list
    .venv/bin/python tools/docs_screenshots.py                     # every shot
    .venv/bin/python tools/docs_screenshots.py --only console-query --only ide-workbench-explain

Needs Chrome or Chromium (``PRAVAHA_CHROME``), and for the ``node`` shots ``JAVA_HOME`` on a JDK 21
or later and the server jar built (``./mvnw -pl pravaha-server -am install -DskipTests``).
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import pathlib
import sys
import tempfile
import threading
import types
from dataclasses import dataclass
from typing import Any, Callable, Optional

CONSOLE = pathlib.Path(__file__).resolve().parents[1]
REPO = CONSOLE.parent
OUT = REPO / "docs" / "assets" / "screenshots"
WIDTH, HEIGHT = 1400, 900

#: The node shots' stream, as the IDE guide's "your own configuration" declares it.
NODE_CONFIG = """\
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
"""


@dataclass(frozen=True)
class Shot:
    """One documented image: its file name (without ``.png``), how it is made, and where.

    ``page`` names a ``browser_harness.PAGES`` entry (its path and its readiness check);
    otherwise ``path`` and ``ready`` say it here. ``action`` runs on the page before the shot."""

    name: str
    source: str  # "fake" or "node"
    page: Optional[str] = None
    path: Optional[str] = None
    ready: str = "true"
    action: Optional[str] = None


SHOTS: tuple[Shot, ...] = (
    Shot("console-operations", "fake", page="operations"),
    Shot("console-query", "fake", page="query"),
    Shot("console-workbench", "fake", page="workbench"),
    Shot("console-view", "fake", page="view"),
    Shot("console-catalog", "fake", page="catalog"),
    Shot("console-queries", "fake", page="queries"),
    Shot("console-replacement", "fake", page="replacement"),
    Shot("console-debug", "fake", page="debug"),
    Shot("console-overview", "fake", page="overview"),
    Shot("console-live", "fake", page="live"),
    Shot("console-plugins", "fake", page="plugins"),
    Shot("console-admin-access", "fake", page="admin-access"),
    Shot("console-help", "fake", page="help"),
    Shot("ide-console-signed-in", "node", page="landing"),
    Shot("ide-workbench-explain", "node", path="/workbench?new=1",
         ready="document.querySelector('[data-template=filter]')", action="explain-filter"),
)


def _paths() -> None:
    """The console, its tests (the harness) and this checkout's SDK, importable; no person's own
    assistant configuration read or written."""
    for path in (CONSOLE, CONSOLE / "tests", REPO / "sdk" / "python"):
        if str(path) not in sys.path:
            sys.path.insert(0, str(path))
    os.environ["PRAVAHA_CONFIG_DIR"] = tempfile.mkdtemp(prefix="pravaha-doc-shots-")
    os.environ.pop("PRAVAHA_ASSIST_CONFIG", None)


def _explain_filter(page: Any) -> None:
    """The IDE guide's end-to-end check: "Filter and project" from the library, then Explain."""
    page.click("[data-template=filter]")
    page.wait_for("document.querySelector('.validity.ok')", timeout=20)
    page.eval("[...document.querySelectorAll('.wb-toolbar button')].find(b => b.textContent.includes"
              "('Explain')).setAttribute('data-shot', 'explain')")
    page.click("[data-shot=explain]")
    page.wait_for("document.querySelectorAll('svg g.plan-node').length === 3", timeout=20)
    # Clicking scrolled the page; the guide shows it from the top, the plan below the editor.
    page.eval("(() => { document.activeElement && document.activeElement.blur(); "
              "window.scrollTo(0, 0); return true; })()")


ACTIONS: dict[str, Callable[[Any], None]] = {"explain-filter": _explain_filter}


def _standard_ports(page: Any, node: Any) -> None:
    """The scratch node's addresses on the page as the defaults the guide describes."""
    swaps = {f"127.0.0.1:{node.flight_port}": "localhost:19090",
             f"127.0.0.1:{node.http_port}": "localhost:18080"}
    page.eval("""((swaps) => {
      const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n = walk.nextNode(); n; n = walk.nextNode())
        for (const [from, to] of Object.entries(swaps)) n.nodeValue = n.nodeValue.split(from).join(to);
      return true;
    })(""" + __import__("json").dumps(swaps) + ")")


#: Chrome's PNG of a 1400x900 page is ~250 KB; a 256-colour palette of it is a fifth of that and
#: reads the same in a document. Pillow does it when an interpreter here has it (it is no
#: dependency of the console's); otherwise the PNG is kept as Chrome wrote it.
_COMPACT = ("import sys; from PIL import Image; p = sys.argv[1]; "
            "Image.open(p).convert('RGB').quantize(256, dither=Image.Dither.NONE).save(p, optimize=True)")


def compact(path: pathlib.Path) -> bool:
    """Palette-quantises ``path`` in place, with Pillow from this interpreter or ``python3``'s."""
    import shutil
    import subprocess

    for interpreter in dict.fromkeys(filter(None, (sys.executable, shutil.which("python3")))):
        done = subprocess.run([interpreter, "-c", _COMPACT, str(path)], capture_output=True)
        if done.returncode == 0:
            return True
    return False


def _sdk_tool() -> Any:
    spec = importlib.util.spec_from_file_location(
        "cli_captures", REPO / "sdk" / "python" / "tools" / "cli_captures.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def take(shots: list[Shot], out: pathlib.Path = OUT) -> list[pathlib.Path]:
    """Makes ``shots`` into ``out``; returns the files written."""
    _paths()
    import browser_harness as harness
    from cdp import Browser, find_chrome

    pages = {name: (path, ready) for name, path, _, ready in harness.PAGES}
    executable = find_chrome()
    if executable is None:
        raise SystemExit("docs_screenshots: no Chrome or Chromium (set PRAVAHA_CHROME to its path)")
    written: list[pathlib.Path] = []
    browser = Browser(executable)
    try:
        for source in ("fake", "node"):
            batch = [s for s in shots if s.source == source]
            if not batch:
                continue
            node = None
            if source == "fake":
                console = harness.Console(harness.BrowserEngine())
                password = harness.PASSWORD
            else:
                tool = _sdk_tool()
                config = pathlib.Path(tempfile.mkdtemp(prefix="pravaha-doc-shots-node-")) / "txn.yaml"
                config.write_text(NODE_CONFIG, encoding="utf-8")
                node = tool.ScratchNode(args=("--spring.profiles.active=dev,users",
                                              f"--spring.config.additional-location=file:{config}"))
                node.__enter__()
                from core.config.properties_configurator import PropertiesConfigurator

                settings = PropertiesConfigurator(str(CONSOLE / "config" / "application.yaml"))
                settings.set("engine.url", node.url)
                settings.set("engine.http_url", node.http)
                console = harness.Console(None)  # type: ignore[arg-type]  # the real engine adapter
                console.engine = types.SimpleNamespace(closed=threading.Event())
                password = "pravaha-dev-admin"  # the users profile's published dev password
            try:
                tab = browser.new_page(width=WIDTH, height=HEIGHT)
                tab.before_every_document(harness.DETERMINISM)
                tab.before_every_document(harness.theme_script("light"))
                tab.before_every_document("try{localStorage.removeItem('pravaha.workbench.tabs')}catch(e){}")
                tab.emulate(reduced_motion=True, scheme="light")
                harness.sign_in(tab, console, password=password)
                for shot in batch:
                    path, ready = pages[shot.page] if shot.page else (shot.path, shot.ready)
                    harness.open_page(tab, console, str(path), ready, timeout=40)
                    if shot.action:
                        ACTIONS[shot.action](tab)
                        tab.settle(quiet_ms=300)
                    if node is not None:
                        _standard_ports(tab, node)
                    target = out / f"{shot.name}.png"
                    target.write_bytes(tab.screenshot())
                    written.append(target)
                    note = "" if compact(target) else " (not compacted: no Pillow here)"
                    print(f"wrote {target.relative_to(REPO) if target.is_relative_to(REPO) else target}{note}")
                tab.close()
            finally:
                console.close()
                if node is not None:
                    node.__exit__(None, None, None)
    finally:
        browser.close()
    return written


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--list", action="store_true", help="list the declared shots and exit")
    parser.add_argument("--only", action="append", metavar="NAME", help="only this shot (repeatable)")
    parser.add_argument("--out", metavar="DIR", help=f"write here instead of {OUT.relative_to(REPO)}")
    args = parser.parse_args(argv)
    if args.list:
        for shot in SHOTS:
            where = f"page {shot.page}" if shot.page else shot.path
            print(f"{shot.name:24} {shot.source:5} {where}")
        return 0
    known = {s.name for s in SHOTS}
    unknown = sorted(set(args.only or ()) - known)
    if unknown:
        print(f"docs_screenshots: no shot named {', '.join(unknown)}; --list lists them", file=sys.stderr)
        return 2
    chosen = [s for s in SHOTS if not args.only or s.name in args.only]
    out = pathlib.Path(args.out) if args.out else OUT
    out.mkdir(parents=True, exist_ok=True)
    take(chosen, out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
