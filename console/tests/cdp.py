"""A small Chrome DevTools Protocol driver, in the standard library only.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Why this and not Playwright or Selenium: the machines that build Pravaha have no Node
toolchain and the console refuses to need one (design 23.3), and a browser-automation
package from an index is a second supply chain for a test. Chrome speaks the protocol
itself; this file is the ~300 lines that let a test speak it back.

The transport is ``--remote-debugging-pipe``: Chrome reads commands from file descriptor 3
and writes answers to 4, each a JSON object followed by a NUL byte. No port is opened, so
two test runs on one machine cannot collide, and nothing else on the host can attach to the
browser a test is driving -- which a debugging *port* would allow.

What it offers is deliberately what a journey needs: open a page, wait for a condition,
evaluate script, click and type through real input events (so focus, ``:focus-visible``
and key handlers behave as they do for a person), emulate a viewport, a colour scheme and
reduced motion, and take a screenshot. Every wait has a deadline and says what it was
waiting for when it runs out.
"""
from __future__ import annotations

import base64
import fcntl
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
from collections.abc import Callable
from typing import Any

#: Where Chrome is looked for, in order. ``PRAVAHA_CHROME`` wins over all of them.
CANDIDATES = ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome")


def find_chrome() -> str | None:
    explicit = os.environ.get("PRAVAHA_CHROME")
    if explicit:
        return explicit if os.path.exists(explicit) or shutil.which(explicit) else None
    for name in CANDIDATES:
        found = shutil.which(name)
        if found:
            return found
    return None


class CdpError(RuntimeError):
    """A protocol call Chrome refused, or a wait that ran out."""


# Keys a journey presses, with what Chrome needs to synthesise a real keystroke for each.
_KEYS = {
    "Tab": ("Tab", 9), "Enter": ("Enter", 13), "Escape": ("Escape", 27),
    "ArrowDown": ("ArrowDown", 40), "ArrowUp": ("ArrowUp", 38), "Backspace": ("Backspace", 8),
    "Space": ("Space", 32), "End": ("End", 35), "Home": ("Home", 36), "Delete": ("Delete", 46),
    "ArrowLeft": ("ArrowLeft", 37), "ArrowRight": ("ArrowRight", 39),
}
_MODIFIERS = {"Alt": 1, "Control": 2, "Meta": 4, "Shift": 8}


class Browser:
    """One headless Chrome, and the reader thread that sorts its answers from its events."""

    def __init__(self, executable: str, *, extra_args: tuple[str, ...] = ()) -> None:
        self._profile = tempfile.mkdtemp(prefix="pravaha-chrome-")
        to_chrome_r, self._to_chrome_w = os.pipe()
        self._from_chrome_r, from_chrome_w = os.pipe()

        def wire_descriptors() -> None:
            # Move both ends clear of 3 and 4 first: either may already BE 3 or 4 in this
            # process, and dup2 onto a descriptor that is still needed would lose it.
            low = fcntl.fcntl(to_chrome_r, fcntl.F_DUPFD, 10)
            high = fcntl.fcntl(from_chrome_w, fcntl.F_DUPFD, 10)
            os.dup2(low, 3)
            os.dup2(high, 4)

        args = [executable, "--headless=new", "--remote-debugging-pipe", "--no-first-run",
                "--no-default-browser-check", "--disable-gpu", "--disable-extensions",
                "--disable-background-networking", "--disable-sync", "--disable-translate",
                "--disable-component-update", "--mute-audio", "--hide-scrollbars",
                "--force-color-profile=srgb", "--font-render-hinting=none",
                "--disable-renderer-backgrounding", "--disable-background-timer-throttling",
                "--disable-backgrounding-occluded-windows",
                f"--user-data-dir={self._profile}", *extra_args, "about:blank"]
        if os.geteuid() == 0:
            args.insert(1, "--no-sandbox")
        # preexec_fn is safe here despite the console's server thread: the child runs only
        # fcntl and dup2 -- no allocation, no lock -- between fork and exec.
        self._process = subprocess.Popen(args, pass_fds=(3, 4), preexec_fn=wire_descriptors,  # noqa: PLW1509
                                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL)
        os.close(to_chrome_r)
        os.close(from_chrome_w)
        self._write_lock = threading.Lock()
        self._next_id = 0
        self._pending: dict[int, dict] = {}
        self._answers: dict[int, dict] = {}
        self._cond = threading.Condition()
        self._listeners: list[Callable[[dict], None]] = []
        self._closed = False
        self._reader = threading.Thread(target=self._read, name="cdp-reader", daemon=True)
        self._reader.start()
        self.version = self.send("Browser.getVersion")

    # ------------------------------------------------------------------ transport
    def _read(self) -> None:
        buffer = b""
        while True:
            try:
                chunk = os.read(self._from_chrome_r, 1 << 20)
            except OSError:
                chunk = b""
            if not chunk:
                with self._cond:
                    self._closed = True
                    self._cond.notify_all()
                return
            buffer += chunk
            while b"\0" in buffer:
                raw, buffer = buffer.split(b"\0", 1)
                message = json.loads(raw)
                if "id" in message:
                    with self._cond:
                        self._answers[message["id"]] = message
                        self._cond.notify_all()
                else:
                    for listener in list(self._listeners):
                        listener(message)

    def send(self, method: str, params: dict | None = None, *, session: str | None = None,
             timeout: float = 30.0) -> dict:
        with self._write_lock:
            self._next_id += 1
            ident = self._next_id
            message: dict[str, Any] = {"id": ident, "method": method, "params": params or {}}
            if session:
                message["sessionId"] = session
            os.write(self._to_chrome_w, json.dumps(message).encode() + b"\0")
        deadline = time.monotonic() + timeout
        with self._cond:
            while ident not in self._answers:
                if self._closed:
                    raise CdpError(f"Chrome exited while answering {method}")
                left = deadline - time.monotonic()
                if left <= 0:
                    raise CdpError(f"{method} had no answer in {timeout}s")
                self._cond.wait(left)
            answer = self._answers.pop(ident)
        if "error" in answer:
            raise CdpError(f"{method}: {answer['error'].get('message')} {answer['error'].get('data', '')}")
        return answer.get("result", {})

    def listen(self, listener: Callable[[dict], None]) -> Callable[[], None]:
        self._listeners.append(listener)
        return lambda: self._listeners.remove(listener) if listener in self._listeners else None

    # ------------------------------------------------------------------ pages
    def new_page(self, *, width: int = 1280, height: int = 900) -> Page:
        """A fresh tab in its own browser context: its own cookies and storage."""
        context = self.send("Target.createBrowserContext", {"disposeOnDetach": True})["browserContextId"]
        target = self.send("Target.createTarget", {"url": "about:blank", "browserContextId": context})["targetId"]
        session = self.send("Target.attachToTarget", {"targetId": target, "flatten": True})["sessionId"]
        return Page(self, session, target, context, width, height)

    def close(self) -> None:
        try:
            self.send("Browser.close", timeout=5)
        except CdpError:
            pass
        try:
            self._process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self._process.kill()
        for fd in (self._to_chrome_w, self._from_chrome_r):
            try:
                os.close(fd)
            except OSError:
                pass
        shutil.rmtree(self._profile, ignore_errors=True)


class Page:
    """One tab. Every method is a protocol call or a bounded wait on one."""

    def __init__(self, browser: Browser, session: str, target: str, context: str,
                 width: int, height: int) -> None:
        self.browser, self.session, self.target, self.context = browser, session, target, context
        self.events: list[dict] = []
        self.console: list[str] = []
        self.exceptions: list[str] = []
        self._load_count = 0
        self._stop = browser.listen(self._on_event)
        for domain in ("Page", "Runtime", "Network", "Performance"):
            self.send(f"{domain}.enable")
        self.send("Page.setLifecycleEventsEnabled", {"enabled": True})
        self.viewport(width, height)

    def send(self, method: str, params: dict | None = None, timeout: float = 30.0) -> dict:
        return self.browser.send(method, params, session=self.session, timeout=timeout)

    def _on_event(self, message: dict) -> None:
        if message.get("sessionId") != self.session:
            return
        method = message.get("method", "")
        params = message.get("params", {})
        if method == "Page.loadEventFired":
            self._load_count += 1
        elif method == "Runtime.consoleAPICalled":
            text = " ".join(str(a.get("value", a.get("description", ""))) for a in params.get("args", []))
            self.console.append(f"{params.get('type')}: {text}")
        elif method == "Runtime.exceptionThrown":
            detail = params.get("exceptionDetails", {})
            self.exceptions.append(str(detail.get("exception", {}).get("description") or detail.get("text")))
        if method.startswith(("Network.", "Page.")):
            self.events.append(message)

    # ------------------------------------------------------------------ emulation
    def viewport(self, width: int, height: int) -> None:
        self.width, self.height = width, height
        self.send("Emulation.setDeviceMetricsOverride",
                  {"width": width, "height": height, "deviceScaleFactor": 1, "mobile": width < 600})

    def emulate(self, *, scheme: str | None = None, reduced_motion: bool = False) -> None:
        features = []
        if scheme:
            features.append({"name": "prefers-color-scheme", "value": scheme})
        if reduced_motion:
            features.append({"name": "prefers-reduced-motion", "value": "reduce"})
        self.send("Emulation.setEmulatedMedia", {"features": features})

    def before_every_document(self, source: str) -> None:
        """Script that runs in every document this tab opens, before the page's own."""
        self.send("Page.addScriptToEvaluateOnNewDocument", {"source": source})

    # ------------------------------------------------------------------ navigation
    def goto(self, url: str, *, settle: str | None = None, timeout: float = 20.0) -> None:
        """Navigate and wait for the load event, then for ``settle`` (a JS expression) if given."""
        before = self._load_count
        answer = self.send("Page.navigate", {"url": url})
        if answer.get("errorText"):
            raise CdpError(f"{url}: {answer['errorText']}")
        self.wait(lambda: self._load_count > before, timeout, f"the load event of {url}")
        if settle:
            self.wait_for(settle, timeout=timeout)

    def wait_for_navigation(self, action: Callable[[], Any], timeout: float = 20.0) -> None:
        before = self._load_count
        action()
        self.wait(lambda: self._load_count > before, timeout, "a navigation")

    @staticmethod
    def wait(predicate: Callable[[], bool], timeout: float, what: str) -> None:
        deadline = time.monotonic() + timeout
        while not predicate():
            if time.monotonic() > deadline:
                raise CdpError(f"timed out after {timeout}s waiting for {what}")
            time.sleep(0.02)

    def wait_for(self, expression: str, timeout: float = 10.0) -> Any:
        """Poll a JavaScript expression until it is truthy, and return its value."""
        deadline = time.monotonic() + timeout
        last: Any = None
        while True:
            try:
                last = self.eval(expression)
            except CdpError as exc:
                last = exc
            # A DOM node comes back as {} -- falsy in Python, truthy in the page, which is the
            # truth that matters here.
            if not isinstance(last, CdpError) and last not in (None, False, 0, ""):
                return last
            if time.monotonic() > deadline:
                raise CdpError(f"timed out after {timeout}s waiting for `{expression}` (last: {last!r}) "
                               f"on {self.url()}; page errors: {self.exceptions[-3:]}")
            time.sleep(0.05)

    def url(self) -> str:
        try:
            return str(self.eval("location.href"))
        except CdpError:
            return "?"

    def settle(self, quiet_ms: int = 300, timeout: float = 15.0) -> None:
        """Wait until no request has started or finished for ``quiet_ms``, and fonts are ready."""
        self.eval("document.fonts ? document.fonts.ready.then(() => true) : true")
        deadline = time.monotonic() + timeout
        seen = len(self.events)
        quiet_since = time.monotonic()
        while time.monotonic() < deadline:
            time.sleep(0.05)
            now = len([e for e in self.events if e.get("method", "").startswith(("Network.request",
                                                                               "Network.loading"))])
            if now != seen:
                seen, quiet_since = now, time.monotonic()
            elif (time.monotonic() - quiet_since) * 1000 >= quiet_ms:
                return

    # ------------------------------------------------------------------ script
    def eval(self, expression: str, *, timeout: float = 30.0) -> Any:
        answer = self.send("Runtime.evaluate", {"expression": expression, "returnByValue": True,
                                                "awaitPromise": True, "userGesture": True},
                           timeout=timeout)
        if answer.get("exceptionDetails"):
            detail = answer["exceptionDetails"]
            raise CdpError(f"script failed: {detail.get('exception', {}).get('description') or detail.get('text')}")
        return answer.get("result", {}).get("value")

    def exists(self, selector: str) -> bool:
        return bool(self.eval(f"!!document.querySelector({json.dumps(selector)})"))

    def text(self, selector: str = "body") -> str:
        return str(self.eval(f"(document.querySelector({json.dumps(selector)}) || {{}}).innerText || ''"))

    def active(self) -> dict:
        """The focused element, described well enough to assert on."""
        return self.eval("""(() => { const e = document.activeElement; if (!e) return null;
            const s = getComputedStyle(e);
            return { tag: e.tagName.toLowerCase(), id: e.id, text: (e.innerText || e.value || '').trim().slice(0, 80),
                     label: e.getAttribute('aria-label') || '', href: e.getAttribute('href') || '',
                     role: e.getAttribute('role') || '', cls: e.className && e.className.baseVal === undefined ? e.className : '',
                     outline: s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0,
                     shadow: s.boxShadow !== 'none', visible: !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length) }; })()""")

    # ------------------------------------------------------------------ input
    def _center(self, selector: str) -> tuple[float, float]:
        box = self.eval(f"""(() => {{ const e = document.querySelector({json.dumps(selector)});
            if (!e) return null; e.scrollIntoView({{block: 'center', inline: 'center'}});
            const r = e.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; }})()""")
        if not box:
            raise CdpError(f"no element matches {selector} on {self.url()}")
        return float(box[0]), float(box[1])

    def click(self, selector: str) -> None:
        """A real mouse click at the element's centre, so the page sees what a person's would."""
        x, y = self._center(selector)
        for kind in ("mouseMoved", "mousePressed", "mouseReleased"):
            self.send("Input.dispatchMouseEvent", {"type": kind, "x": x, "y": y, "button": "left",
                                                   "clickCount": 1})

    def focus(self, selector: str) -> None:
        self.eval(f"document.querySelector({json.dumps(selector)}).focus()")

    def type(self, text: str) -> None:
        """Text as an input method would deliver it, into whatever has focus."""
        self.send("Input.insertText", {"text": text})

    def press(self, key: str, *modifiers: str) -> None:
        """One keystroke -- ``Tab``, ``Escape``, ``Enter``, ``k`` with ``Control`` -- as keyDown and keyUp."""
        mask = 0
        for modifier in modifiers:
            mask |= _MODIFIERS[modifier]
        if key in _KEYS:
            name, code = _KEYS[key]
            key_name = " " if key == "Space" else name
            text = "\r" if key == "Enter" else (" " if key == "Space" else "")
        else:
            key_name, name, code, text = key, "Key" + key.upper(), ord(key.upper()), key
        base = {"key": key_name, "code": name, "windowsVirtualKeyCode": code,
                "nativeVirtualKeyCode": code, "modifiers": mask}
        down = dict(base, type="keyDown" if (text and not mask) else "rawKeyDown")
        if text and not mask:
            down["text"] = text
        self.send("Input.dispatchKeyEvent", down)
        self.send("Input.dispatchKeyEvent", dict(base, type="keyUp"))

    # ------------------------------------------------------------------ output
    def screenshot(self, *, full_page: bool = False) -> bytes:
        params: dict[str, Any] = {"format": "png", "captureBeyondViewport": full_page}
        if full_page:
            size = self.eval("[document.documentElement.scrollWidth, document.documentElement.scrollHeight]")
            params["clip"] = {"x": 0, "y": 0, "width": size[0], "height": size[1], "scale": 1}
        return base64.b64decode(self.send("Page.captureScreenshot", params)["data"])

    def metrics(self) -> dict[str, float]:
        return {m["name"]: m["value"] for m in self.send("Performance.getMetrics")["metrics"]}

    def close(self) -> None:
        self._stop()
        try:
            self.browser.send("Target.closeTarget", {"targetId": self.target}, timeout=5)
            self.browser.send("Target.disposeBrowserContext", {"browserContextId": self.context}, timeout=5)
        except CdpError:
            pass
