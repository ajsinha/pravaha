"""The console on a phone: every page, at 360x740 and 390x844, measured rather than photographed.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The narrow visual baselines catch a change, not a page that was never usable: a table whose SQL
column wraps one word per line photographs the same way every run. This module holds what makes
a screen usable at phone width, on every page the visual test photographs (``PAGES``, signed in
as the administrator), the documents it does not, a few screens only the menu reaches, and the
public pages signed out, with the bar a stranger sees:

* **No horizontal page scroll.** ``scrollWidth`` of the document is at most the viewport (1px of
  rounding). Wide content -- a table, a plan, a block of code -- scrolls inside a box of its own.
* **No element wider than the viewport** outside such a box. The offender is named, outermost
  first, so the message points at the one rule to change rather than at its children.
* **Tap targets** (``TARGET`` CSS px, both ways): every visible ``a``, ``button``, ``input``,
  ``select``, ``textarea``, ``summary`` and ``[role=button]`` that is not disabled. A checkbox or
  radio is measured with its label, which is what a finger presses. Exempt, as WCAG 2.5.8 exempts
  them: a link that is ``display: inline`` inside running text -- its block holds at least three
  letters that belong to no control -- because its height is the line's. Anything else excused is
  in ``TARGET_ALLOWED``, with the reason.
* **Readable text**: every visible text node at least 12px; code (``code``, ``pre``, ``kbd``,
  ``samp``, a monospace face) at least 11px. SVG text is drawn in the figure's own units and is
  measured by the plan's own tests, so it is not counted here.
* **Inputs at 16px**: a text field, select or textarea under 16px makes iOS zoom the page when it
  takes focus, and the person then pans to find the button.
* **No squeezed controls**: a link or button whose own text is wider than its box (and is not cut
  off on purpose by an overflow rule) has been shrunk by a crowded row, and its words run into its
  neighbour's.

And the bar itself (``test_the_menu_opens_and_every_entry_is_reachable``): the menu button opens
it; every top-level entry, every mega-menu panel and every item in it, the tools, the theme and
account menus can be reached by scrolling the open menu, are inside the viewport, and are
targets of the same size; everything works by tap -- nothing in it depends on hover.
"""
from __future__ import annotations

import json

import pytest
from browser_harness import DETERMINISM, PAGES, BrowserEngine, Console, open_page, sign_in
from cdp import Browser, Page

pytestmark = pytest.mark.browser

#: A small Android phone, and the iPhone the visual test's "narrow" baselines are taken at.
VIEWPORTS = {"360": (360, 740), "390": (390, 844)}
TARGET = 40
BODY_TEXT = 12
CODE_TEXT = 11
INPUT_TEXT = 16

#: Screens the visual test does not photograph but a person reaches from the menu.
EXTRA_PAGES: list[tuple[str, str, bool, str]] = [
    ("alerts", "/alerts", True, "true"),
    ("admin-lanes", "/admin/lanes", True, "true"),
    ("admin-grants", "/admin/grants", True, "true"),
    ("admin-policies", "/admin/policies", True, "true"),
    ("dead-letters", "/queries/big_txn/dead-letters", True, "true"),
]
ALL_PAGES = PAGES + EXTRA_PAGES
PUBLIC = [p for p in ALL_PAGES if not p[2]]

#: Targets excused from the size rule, each with its reason. A selector here matches the element
#: itself or an ancestor.
TARGET_ALLOWED: dict[str, str] = {
    # Monaco draws its own editor: the hidden input area, the find widget, the suggestion rows.
    # It is one large target (the editor, full width), and its inner controls are Monaco's.
    ".monaco-editor": "Monaco's own widgets",
    ".monaco-diff-editor": "Monaco's own widgets",
    # The skip link is off screen until it has focus, when it is the only thing that matters.
    ".pv-skip": "off screen until focused",
}

#: The measurements, in the page. Returns ``{scroll, wide, targets, text, inputs}``: a number and
#: four lists of offenders, each named by a short selector.
AUDIT = """((W, TARGET, BODY, CODE, INPUT, allowed) => {
  const vw = Math.min(document.documentElement.clientWidth, W);
  const name = (e) => {
    const part = (n) => n.tagName.toLowerCase() + (n.id ? '#' + n.id :
      (typeof n.className === 'string' && n.className.trim() ? '.' + n.className.trim().split(/\\s+/).slice(0, 2).join('.') : ''));
    const parts = []; let n = e;
    for (let i = 0; n && n.nodeType === 1 && n !== document.body && i < 3; i++, n = n.parentElement) {
      parts.unshift(part(n)); if (n.id) break; }
    return parts.join(' > ');
  };
  const shown = (e) => { const s = getComputedStyle(e);
    if (s.display === 'none' || s.visibility === 'hidden' || parseFloat(s.opacity) === 0) return false;
    const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const hiddenText = (e) => !!e.closest('.visually-hidden, .visually-hidden-focusable, [aria-hidden=true], svg');
  // Whether a box between the element and the page clips it. Walked along the containing-block
  // chain, not the parent chain: an absolutely positioned element escapes every box that is not
  // positioned, and a fixed one escapes them all.
  const root = (a) => !a || a === document.body || a === document.documentElement;
  const holder = (e) => { const p = getComputedStyle(e).position;
    if (p === 'fixed') return null;
    let a = e.parentElement;
    if (p === 'absolute') while (!root(a) && getComputedStyle(a).position === 'static') a = a.parentElement;
    return a; };
  const contained = (e) => { for (let a = holder(e); !root(a); a = holder(a)) {
      if (getComputedStyle(a).overflowX !== 'visible') return true; } return false; };
  const excused = (e) => allowed.some((s) => e.closest(s));
  const out = {scroll: document.documentElement.scrollWidth - Math.min(window.innerWidth, W), wide: [], targets: [], text: [], inputs: [], squeezed: []};

  const wide = [];
  const tooWide = (e, r) => {
    // Past the right edge widens the page, even by a 1px box (a visually hidden label that escaped
    // its scroll box does exactly that); past the left edge is only lost, and a 1px box parked
    // there on purpose (an announcer) is not content.
    if ((r.right > vw + 1 || (r.left < -1 && r.width > 1)) && !contained(e) && !wide.some((w) => w.contains(e))) {
      wide.push(e); out.wide.push(name(e) + ' [' + Math.round(r.left) + '..' + Math.round(r.right) + ']'); } };
  for (const e of document.body.querySelectorAll('*')) if (shown(e)) tooWide(e, e.getBoundingClientRect());
  // Text that overflows the box it is in: the box fits, the word does not.
  const text = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); const range = document.createRange();
  for (let n = text.nextNode(); n; n = text.nextNode()) {
    const e = n.parentElement;
    if (!n.data.trim() || !e || e.closest('.visually-hidden') || !shown(e)) continue;
    range.selectNodeContents(n); const r = range.getBoundingClientRect();
    if (r.width > 0 && getComputedStyle(e).overflowX === 'visible') tooWide(e, r);
  }

  const controls = 'a[href], button, input:not([type=hidden]), select, textarea, summary, [role=button]';
  const ownText = (block) => { let t = ''; const walk = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
    for (let n = walk.nextNode(); n; n = walk.nextNode()) if (!n.parentElement.closest(controls + ', .visually-hidden')) t += n.data;
    return (t.match(/[A-Za-z]/g) || []).length; };
  const block = (e) => { let a = e.parentElement; while (a && getComputedStyle(a).display.startsWith('inline')) a = a.parentElement; return a; };
  for (const e of document.querySelectorAll(controls)) {
    if (e.disabled || e.getAttribute('aria-disabled') === 'true' || excused(e)) continue;
    if (e.closest('.visually-hidden, .visually-hidden-focusable') || !shown(e)) continue;
    if (e.tagName === 'A' && getComputedStyle(e).display === 'inline' && ownText(block(e)) >= 3) continue;
    let r = e.getBoundingClientRect(); let box = {l: r.left, t: r.top, r: r.right, b: r.bottom};
    if (e.type === 'checkbox' || e.type === 'radio') {
      const label = (e.id && document.querySelector('label[for="' + CSS.escape(e.id) + '"]')) || e.closest('label');
      if (label && shown(label)) { const q = label.getBoundingClientRect();
        box = {l: Math.min(box.l, q.left), t: Math.min(box.t, q.top), r: Math.max(box.r, q.right), b: Math.max(box.b, q.bottom)}; }
    }
    const w = box.r - box.l, h = box.b - box.t;
    if (w < TARGET - 0.5 || h < TARGET - 0.5) out.targets.push(name(e) + ' ' + Math.round(w) + 'x' + Math.round(h));
    if (!['INPUT', 'SELECT', 'TEXTAREA'].includes(e.tagName) && getComputedStyle(e).display !== 'inline'
        && getComputedStyle(e).overflowX === 'visible') {
      // The union of its visible text's boxes: a visually hidden name overflows its 1px box on purpose.
      const t = {l: Infinity, r: -Infinity, width: 0}; const inner = document.createRange();
      const words = document.createTreeWalker(e, NodeFilter.SHOW_TEXT);
      for (let n = words.nextNode(); n; n = words.nextNode()) {
        if (!n.data.trim() || n.parentElement.closest('.visually-hidden')) continue;
        inner.selectNodeContents(n); const q = inner.getBoundingClientRect();
        if (!q.width) continue;  // not rendered (display: none)
        t.l = Math.min(t.l, q.left); t.r = Math.max(t.r, q.right); t.width = t.r - t.l; }
      if (t.width > r.width + 1) out.squeezed.push(name(e) + ' ' + Math.round(t.width) + ' in ' + Math.round(r.width)); }
  }

  const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const seen = new Set();
  for (let n = walk.nextNode(); n; n = walk.nextNode()) {
    const e = n.parentElement;
    if (!n.data.trim() || !e || seen.has(e) || hiddenText(e) || !shown(e)) continue;
    seen.add(e);
    const s = getComputedStyle(e); const size = parseFloat(s.fontSize);
    const code = !!e.closest('code, pre, kbd, samp, .monaco-editor') || /mono/i.test(s.fontFamily);
    if (size < (code ? CODE : BODY) - 0.01) out.text.push(name(e) + ' ' + size + 'px');
  }

  for (const e of document.querySelectorAll('input, select, textarea')) {
    if (['hidden', 'checkbox', 'radio', 'submit', 'button', 'reset', 'range', 'color', 'file', 'image'].includes(e.type)) continue;
    if (e.closest('.visually-hidden') || !shown(e)) continue;
    const size = parseFloat(getComputedStyle(e).fontSize);
    if (size < INPUT - 0.01) out.inputs.push(name(e) + ' ' + size + 'px');
  }
  return out;
})"""


def audit(page: Page, width: int) -> dict:
    allowed = json.dumps(list(TARGET_ALLOWED))
    return dict(page.eval(f"{AUDIT}({width}, {TARGET}, {BODY_TEXT}, {CODE_TEXT}, {INPUT_TEXT}, {allowed})"))


def problems(found: dict) -> list[str]:
    lines = []
    if found["scroll"] > 1:
        lines.append(f"the page scrolls sideways by {found['scroll']}px")
    for check, what in (("wide", "wider than the viewport, outside a scroll box"),
                        ("targets", f"targets under {TARGET}x{TARGET}"),
                        ("text", f"text under {BODY_TEXT}px ({CODE_TEXT}px for code)"),
                        ("inputs", f"inputs under {INPUT_TEXT}px"),
                        ("squeezed", "controls whose text is wider than they are")):
        if found[check]:
            unique = sorted(set(found[check]))
            lines.append(f"{len(unique)} {what}: " + "; ".join(unique[:25]))
    return lines


@pytest.fixture(scope="module")
def mobile_console():
    """A console of this module's own over an untouched fake engine, as the visual test has."""
    server = Console(BrowserEngine())
    try:
        yield server
    finally:
        server.close()


@pytest.fixture(scope="module")
def phones(chrome: Browser, mobile_console: Console):
    tabs: dict[tuple[str, bool], Page] = {}

    def get(viewport: str, signed_in: bool = True) -> Page:
        key = (viewport, signed_in)
        if key not in tabs:
            width, height = VIEWPORTS[viewport]
            tab = chrome.new_page(width=width, height=height)
            tab.before_every_document(DETERMINISM)
            tab.before_every_document("try{localStorage.removeItem('pravaha.workbench.tabs')}catch(e){}")
            tab.emulate(reduced_motion=True)
            if signed_in:
                sign_in(tab, mobile_console)
            tabs[key] = tab
        return tabs[key]

    yield get
    for tab in tabs.values():
        tab.close()


@pytest.mark.parametrize("viewport", list(VIEWPORTS))
@pytest.mark.parametrize("name,path,ready", [(n, p, r) for n, p, _, r in ALL_PAGES], ids=[p[0] for p in ALL_PAGES])
def test_every_page_fits_a_phone(phones, mobile_console, name, path, ready, viewport):
    page = phones(viewport)
    open_page(page, mobile_console, path, ready)
    found = problems(audit(page, VIEWPORTS[viewport][0]))
    assert not found, f"{path} at {viewport}px:\n  " + "\n  ".join(found)


@pytest.mark.parametrize("name,path,ready", [(n, p, r) for n, p, _, r in PUBLIC], ids=[p[0] for p in PUBLIC])
def test_every_public_page_fits_a_phone_signed_out(phones, mobile_console, name, path, ready):
    page = phones("360", signed_in=False)
    open_page(page, mobile_console, path, ready)
    found = problems(audit(page, VIEWPORTS["360"][0]))
    assert not found, f"{path} signed out at 360px:\n  " + "\n  ".join(found)


#: Where an element is once scrolled into view inside the open menu: inside the viewport, the
#: element a tap at its centre reaches, and its size.
REACH = """((sel, i) => { const e = document.querySelectorAll(sel)[i]; if (!e) return null;
  e.scrollIntoView({block: 'nearest', inline: 'nearest'});
  const r = e.getBoundingClientRect(); const x = r.left + r.width / 2, y = r.top + r.height / 2;
  const hit = document.elementFromPoint(x, y);
  return {label: (e.textContent || e.getAttribute('aria-label') || '').trim().replace(/\\s+/g, ' ').slice(0, 40),
          left: r.left, right: r.right, top: r.top, bottom: r.bottom, w: r.width, h: r.height,
          vw: document.documentElement.clientWidth, vh: window.innerHeight,
          reached: !!hit && (hit === e || e.contains(hit))}; })"""


def _reach(page: Page, selector: str, index: int, where: str) -> list[str]:
    box = page.eval(f"{REACH}({json.dumps(selector)}, {index})")
    if box is None:
        return [f"{where}: no {selector}[{index}]"]
    wrong = []
    if box["left"] < -1 or box["right"] > box["vw"] + 1 or box["top"] < -1 or box["bottom"] > box["vh"] + 1:
        wrong.append(f"{where}: '{box['label']}' is outside the viewport {box}")
    if not box["reached"]:
        wrong.append(f"{where}: a tap on '{box['label']}' lands on something else")
    if box["w"] < TARGET - 0.5 or box["h"] < TARGET - 0.5:
        wrong.append(f"{where}: '{box['label']}' is {round(box['w'])}x{round(box['h'])}")
    return wrong


def _count(page: Page, selector: str) -> int:
    return int(page.eval(f"document.querySelectorAll({json.dumps(selector)}).length"))


@pytest.mark.parametrize("viewport", list(VIEWPORTS))
def test_the_menu_opens_and_every_entry_is_reachable(phones, mobile_console, viewport):
    page = phones(viewport)
    open_page(page, mobile_console, "/operations", "document.querySelector('#chart-rate canvas')")
    wrong = _reach(page, ".pv-nav .navbar-toggler", 0, "the menu button")
    page.click(".pv-nav .navbar-toggler")
    page.wait_for("document.querySelector('#pravaha-nav.show') !== null")
    tops = ".pv-nav .nav-item.mega > .nav-link"
    for i in range(_count(page, tops)):
        wrong += _reach(page, tops, i, f"top-level entry {i}")
        page.click(f".pv-nav .nav-item.mega:nth-child({i + 1}) > .nav-link")
        panel = f".pv-nav .nav-item.mega:nth-child({i + 1}) .mega-panel"
        page.wait_for(f"document.querySelector({json.dumps(panel + '.show')}) !== null")
        items = panel + " .mega-item"
        assert _count(page, items) > 0, panel
        for j in range(_count(page, items)):
            wrong += _reach(page, items, j, f"panel {i} item {j}")
        page.click(f".pv-nav .nav-item.mega:nth-child({i + 1}) > .nav-link")
        page.wait_for(f"document.querySelector({json.dumps(panel + '.show')}) === null")
    for tool in ("#palette-trigger", ".pv-tools > .tool", "#theme-toggle", "#account-menu"):
        for i in range(_count(page, tool)):
            wrong += _reach(page, tool, i, f"tool {tool}")
    for toggle, menu in (("#theme-toggle", ".theme-menu"),
                         ("#account-menu", ".pv-user-menu")):
        page.click(toggle)
        page.wait_for(f"document.querySelector({json.dumps(menu + '.show')}) !== null")
        entries = f"{menu}.show .dropdown-item"
        for j in range(_count(page, entries)):
            wrong += _reach(page, entries, j, f"{menu} item {j}")
        page.click(toggle)
    assert not wrong, f"the menu at {viewport}px:\n  " + "\n  ".join(wrong)
