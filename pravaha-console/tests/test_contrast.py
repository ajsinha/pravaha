"""Design 23.14: contrast "in every theme -- verified by a token-level test, not by eye".

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The axe audit (``test_browser_accessibility.py``) checks the pixels a page actually drew, but
only where it can resolve a background -- it passes over text on a gradient, and it never sees
a combination no current page happens to use. This test checks the combinations the design
system *allows*: every text token on every ground it may sit on, in every theme, computed
with the WCAG 2.x relative-luminance formula from ``static/css/tokens.css``.
It needs no browser, so it runs everywhere.

The console follows drishti's design language: drishti's seven themes, under drishti's ids,
with drishti's token values (``--d-*``), and the checks drishti's own ``tests/test_contrast.py``
makes -- every text token on every ground at 4.5:1, labels on the accent fills -- here for all
seven. Pravaha's role names (``--pv-*``) are aliases of those tokens, some through ``rgba`` or
``color-mix``; this test resolves each alias the way the browser does, laying a translucent
colour over the ground it sits on, so a pair is checked as it is drawn.
"""
from __future__ import annotations

import pathlib
import re

import pytest

STATIC = pathlib.Path(__file__).resolve().parents[1] / "web" / "static"
TOKENS = STATIC / "css" / "tokens.css"
CSS_DIRS = (STATIC / "css", STATIC / "app")
TEMPLATES = STATIC.parent / "templates"

#: drishti's seven, in its theme menu's order. Crimson is Pravaha's default.
THEMES = ["terminal", "light", "blue", "wallstreet", "green", "crimson", "crimson-dark"]
DEFAULT = "crimson"

#: drishti's checks: every token the console sets as a text colour, on every ground text sits on
#: (the page, the second ground, panels and the second surface).
TEXT_TOKENS = ("d-ink", "d-muted", "d-faint", "d-link", "d-accent", "d-accent-strong", "d-pos", "d-neg",
               "d-ok", "d-warn", "d-bad")
GROUNDS = ("d-bg", "d-bg-2", "d-surface", "d-surface-2")

#: Pravaha's own pairs, through its role names: (foreground, background, minimum). Text at 4.5:1,
#: large headings and UI boundaries at 3:1 (WCAG 1.4.3, 1.4.11).
PV_GROUNDS = ("pv-canvas", "pv-surface", "pv-crimson-tint", "pv-wash")
PAIRS = [
    # Text on the tinted grounds (a hovered row, the current menu item) is ink, muted, a link or a
    # heading -- as drishti draws the current item: ink with an accent bar, never accent on its tint.
    *[(fg, bg, 4.5) for fg in ("pv-ink", "pv-slate", "pv-info", "pv-heading") for bg in PV_GROUNDS],
    *[(fg, bg, 4.5) for fg in ("pv-crimson",) for bg in ("pv-canvas", "pv-surface", "pv-wash")],
    ("pv-on-crimson", "pv-crimson", 4.5), ("pv-on-crimson", "pv-crimson-strong", 4.5),   # a primary button
    ("pv-crimson-strong", "pv-surface", 4.5),                                           # a hovered link
    ("pv-ok", "pv-ok-soft", 4.5), ("pv-warn", "pv-warn-soft", 4.5),                     # status chips
    ("pv-bad", "pv-bad-soft", 4.5), ("pv-info", "pv-info-soft", 4.5),
    *[(fg, bg, 4.5) for fg in ("pv-ok", "pv-warn", "pv-bad", "pv-info") for bg in ("pv-surface", "pv-canvas")],
    *[(fg, bg, 4.5) for fg in ("pv-ink", "pv-info")                   # alerts, and a link in one
      for bg in ("pv-ok-soft", "pv-warn-soft", "pv-bad-soft", "pv-info-soft")],
    ("pv-ink", "pv-highlight", 4.5),                                   # a search hit (mark)
    ("pv-edge", "pv-surface", 3.0), ("pv-edge", "pv-canvas", 3.0),     # form-control borders
    ("pv-crimson", "pv-surface", 3.0),                                 # the focus ring
    # The bands (the four coloured top bars, About, sign-in) are gradients axe cannot resolve:
    # their white text is checked against each stop here.
    ("pv-on-nav", "pv-nav-from", 4.5), ("pv-on-nav", "pv-nav-via", 4.5), ("pv-on-nav", "pv-nav-to", 4.5),
    # A retraction on the landing page: its own colour on the page's grounds, and a −1 on a disc of it.
    ("pv-retract", "pv-canvas", 4.5), ("pv-retract", "pv-surface", 4.5), ("pv-on-crimson", "pv-retract", 4.5),
    # Gradient text on the big headings (drishti's gradients.css): large text, 3:1 at each stop.
    *[(fg, bg, 3.0) for fg in ("d-grad-from", "d-grad-to") for bg in ("d-bg", "d-bg-2", "d-surface")],
]

#: drishti's --g-bar: how much of each gradient colour the glass top bar mixes into the page
#: ground in the three themes whose bar is not a coloured band (gradients.css).
GLASS_BAR = {"terminal": 22, "light": 34, "wallstreet": 28}

ACCENT_APART = 25.0
SERIES = [f"pv-series-{n}" for n in range(1, 9)]
SERIES_APART = 20.0


# ----------------------------------------------------------------------------- the resolver
def _css() -> str:
    return TOKENS.read_text(encoding="utf-8")


def _declarations(body: str) -> dict[str, str]:
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL)
    return {k: v.strip() for k, v in re.findall(r"--([a-z0-9-]+):\s*([^;]+);", body)}


def _block(selector_regex: str) -> dict[str, str]:
    match = re.search(selector_regex + r"\s*\{(.*?)\n\}", _css(), re.DOTALL)
    assert match, selector_regex
    return _declarations(match.group(1))


def _theme_block(name: str) -> dict[str, str]:
    return _block(r':root\[data-theme="' + re.escape(name) + r'"\]')


def _aliases() -> dict[str, str]:
    return _block(r"\n:root, :root\[data-theme\]")


Colour = tuple[float, float, float, float]


def _parse(value: str, scope: dict[str, str]) -> Colour:
    value = value.strip()
    var = re.fullmatch(r"var\(--([a-z0-9-]+)\)", value)
    if var:
        return _parse(scope[var.group(1)], scope)
    if value.startswith("#"):
        h = value[1:]
        if len(h) == 3:
            h = "".join(c * 2 for c in h)
        return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 1.0)
    rgba = re.fullmatch(r"rgba?\(([^)]*)\)", value)
    if rgba:
        parts = [p.strip() for p in rgba.group(1).split(",")]
        return (float(parts[0]), float(parts[1]), float(parts[2]), float(parts[3]) if len(parts) > 3 else 1.0)
    mix = re.fullmatch(r"color-mix\(in srgb,\s*(.+?)\s+(\d+(?:\.\d+)?)%,\s*(.+)\)", value)
    if mix:
        a, b, p = _parse(mix.group(1), scope), _parse(mix.group(3), scope), float(mix.group(2)) / 100
        return tuple(a[i] * p + b[i] * (1 - p) for i in range(3)) + (a[3] * p + b[3] * (1 - p),)
    raise AssertionError(f"cannot resolve {value!r}")


def tokens(theme: str) -> dict[str, Colour]:
    """Every colour token of a theme, resolved: the theme's own block, then the aliases."""
    scope = {**_theme_block(theme), **_aliases()}
    out = {}
    for name, value in scope.items():
        try:
            out[name] = _parse(value, scope)
        except (AssertionError, ValueError, IndexError):
            continue   # a shadow, a font stack, a radius: not a colour
    return out


def _over(top: Colour, ground: Colour) -> Colour:
    a = top[3]
    return tuple(top[i] * a + ground[i] * (1 - a) for i in range(3)) + (1.0,)


def _solid(t: dict[str, Colour], name: str) -> Colour:
    """A ground as drawn: a translucent one over the surface it is laid on."""
    colour = t[name]
    return colour if colour[3] >= 1 else _over(colour, t["d-surface"])


def _luminance(c: Colour) -> float:
    channels = [x / 255 for x in c[:3]]
    linear = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def _ratio(a: Colour, b: Colour) -> float:
    la, lb = sorted((_luminance(a), _luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def pair(t: dict[str, Colour], fg: str, bg: str) -> float:
    ground = _solid(t, bg)
    return _ratio(_over(t[fg], ground), ground)


def _hex(c: Colour) -> str:
    return "#" + "".join(f"{round(x):02x}" for x in c[:3])


# ----------------------------------------------------------------------------- the tests
def test_the_formula_matches_the_published_reference_points():
    assert _ratio((0, 0, 0, 1), (255, 255, 255, 1)) == pytest.approx(21.0)
    assert _ratio((0x77, 0x77, 0x77, 1), (255, 255, 255, 1)) == pytest.approx(4.48, abs=0.01)


def test_the_seven_themes_are_drishtis_and_crimson_is_the_fallback():
    css = _css()
    for theme in THEMES:
        assert f':root[data-theme="{theme}"]' in css, theme
    assert re.search(r'^:root,\s*:root\[data-theme="crimson"\]\s*\{', css, re.MULTILINE)
    # No theme answers the system's dark preference: nothing chosen means Crimson, as theme-init.js does.
    assert "prefers-color-scheme" not in css


def test_every_theme_declares_every_token():
    """A theme that leaves a token out inherits it from `:root`, the crimson theme -- the "light
    island" bug. drishti's crimson-dark and wallstreet leave --d-on-accent to drishti's :root,
    which is Terminal's; here they say it."""
    reference = set(_theme_block(DEFAULT))
    for theme in THEMES:
        declared = set(_theme_block(theme))
        assert declared == reference, f"{theme}: missing {sorted(reference - declared)}, extra {sorted(declared - reference)}"


@pytest.mark.parametrize("theme", THEMES)
def test_every_text_colour_reads_on_every_ground(theme):
    """drishti's UX-04: every text token on every ground at 4.5:1 (WCAG 1.4.3)."""
    t = tokens(theme)
    low = [f"--{fg} {_hex(t[fg])} on --{bg} {_hex(t[bg])}: {pair(t, fg, bg):.2f}"
           for fg in TEXT_TOKENS for bg in GROUNDS if pair(t, fg, bg) < 4.5]
    assert not low, f"{theme}:\n  " + "\n  ".join(low)


@pytest.mark.parametrize("theme", THEMES)
def test_labels_on_accent_fills_read(theme):
    t = tokens(theme)
    for fill in ("d-accent", "d-accent-strong"):
        assert pair(t, "d-on-accent", fill) >= 4.5, (theme, fill, round(pair(t, "d-on-accent", fill), 2))


@pytest.mark.parametrize("theme", THEMES)
def test_every_allowed_token_pair_meets_wcag_aa(theme):
    t = tokens(theme)
    failures = [f"--{fg} {_hex(t[fg])} on --{bg} {_hex(_solid(t, bg))}: {pair(t, fg, bg):.2f} < {minimum}"
                for fg, bg, minimum in PAIRS if pair(t, fg, bg) < minimum]
    assert not failures, f"{theme} theme:\n  " + "\n  ".join(failures)


@pytest.mark.parametrize("theme", sorted(GLASS_BAR))
def test_the_glass_top_bar_reads_at_each_stop(theme):
    """Terminal, Parchment and Wall Street draw the bar as drishti does: the page ground with a share
    of each gradient colour mixed in at its ends, ink on it."""
    t = tokens(theme)
    share = GLASS_BAR[theme] / 100
    for stop in ("d-grad-from", "d-grad-to"):
        ground = tuple(t[stop][i] * share + t["d-bg"][i] * (1 - share) for i in range(3)) + (1.0,)
        for fg in ("d-ink",):
            assert _ratio(t[fg], ground) >= 4.5, (theme, fg, stop, round(_ratio(t[fg], ground), 2))


def _lab(c: Colour) -> tuple[float, float, float]:
    srgb = [x / 255 for x in c[:3]]
    r, g, b = [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in srgb]
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

    def f(v: float) -> float:
        return v ** (1 / 3) if v > 216 / 24389 else (24389 / 27 * v + 16) / 116

    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def difference(a: Colour, b: Colour) -> float:
    """CIE76 ΔE. Blunt, and blunt is what is wanted: it is a floor, not a grade."""
    la, aa, ba = _lab(a)
    lb, ab, bb = _lab(b)
    return ((la - lb) ** 2 + (aa - ab) ** 2 + (ba - bb) ** 2) ** 0.5


@pytest.mark.parametrize("theme", THEMES)
def test_the_accent_is_not_confusable_with_a_data_series(theme):
    """CON-10: a chart line next to an accent control must not read as the same colour."""
    t = tokens(theme)
    close = [f"--d-accent {_hex(t['d-accent'])} and --{s} {_hex(t[s])}: {difference(t['d-accent'], t[s]):.1f}"
             for s in SERIES if difference(t["d-accent"], t[s]) < ACCENT_APART]
    assert not close, f"{theme}:\n  " + "\n  ".join(close)


@pytest.mark.parametrize("theme", THEMES)
def test_the_data_series_are_not_confusable_with_each_other(theme):
    t = tokens(theme)
    close = [f"--{a} and --{b}: {difference(t[a], t[b]):.1f}" for i, a in enumerate(SERIES) for b in SERIES[i + 1:]
             if difference(t[a], t[b]) < SERIES_APART]
    assert not close, f"{theme}:\n  " + "\n  ".join(close)


@pytest.mark.parametrize("theme", THEMES)
def test_a_retraction_is_neither_the_accent_nor_a_failure(theme):
    """The landing figure draws an insert as a +1 in the accent and a retraction as a −1 in
    `--pv-retract`; a correction is neither the product nor an error."""
    t = tokens(theme)
    for other in ("d-accent", "d-bad"):
        apart = difference(t["pv-retract"], t[other])
        assert apart >= ACCENT_APART, f"{theme}: --pv-retract and --{other}: {apart:.1f}"


@pytest.mark.parametrize("theme", THEMES)
def test_the_status_colours_are_told_apart(theme):
    """"Running", "watch this" and "wrong" have to be three colours, not one. (In drishti's Crimson
    `bad` is the accent's own deeper step; status is never colour alone -- a glyph and a word ride
    with it -- so the accent's distance from `bad` is not held here, as drishti does not hold it.)"""
    t = tokens(theme)
    names = ("d-ok", "d-warn", "d-bad")
    close = [f"--{a} and --{b}: {difference(t[a], t[b]):.1f}" for i, a in enumerate(names) for b in names[i + 1:]
             if difference(t[a], t[b]) < ACCENT_APART]
    assert not close, f"{theme}:\n  " + "\n  ".join(close)


#: The dark themes grey stale data and lift it a step (``brightness``), or drishti's bright reds and
#: pinks would grey to a mid-grey that no longer reads on a near-black ground.
DARK = ("terminal", "wallstreet", "crimson-dark")


def _grayscale(c: Colour, lift: float = 1.0) -> Colour:
    """CSS ``filter: grayscale(1) brightness(lift)``: the luminance matrix applied to the
    sRGB-encoded channels, then each multiplied."""
    y = min(255.0, (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) * lift)
    return (y, y, y, c[3])


@pytest.mark.parametrize("theme", THEMES)
def test_stale_data_keeps_its_contrast(theme):
    """Design 23.12's stale state greys out what it shows (``.stale``); greyed, every text pair
    still passes."""
    css = (STATIC / "css" / "theme.css").read_text(encoding="utf-8")
    rule = re.search(r"\n\.stale \{([^}]*)\}", css)
    assert rule and "opacity" not in rule.group(1) and "grayscale(1)" in rule.group(1), rule
    lift = 1.0
    if theme in DARK:
        dark = re.search(r'\n:root:is\(\[data-theme="terminal"\], \[data-theme="wallstreet"\], '
                         r'\[data-theme="crimson-dark"\]\) \.stale \{ filter: grayscale\(1\) brightness\(([\d.]+)\);', css)
        assert dark, "the dark themes' stale rule"
        lift = float(dark.group(1))
    t = tokens(theme)
    failures = []
    for fg, bg, minimum in PAIRS:
        ground = _grayscale(_solid(t, bg), lift)
        ratio = _ratio(_grayscale(_over(t[fg], _solid(t, bg)), lift), ground)
        if ratio < minimum:
            failures.append(f"--{fg} on --{bg}: {ratio:.2f}")
    assert not failures, f"{theme}, stale:\n  " + "\n  ".join(failures)


def test_colour_is_defined_only_in_tokens_css():
    """drishti's rule: "Colours are defined here and nowhere else". No stylesheet but tokens.css
    states a hex colour, and the shell has no <style> of its own."""
    offenders = []
    for folder in CSS_DIRS:
        for sheet in folder.glob("*.css"):
            if sheet.name == "tokens.css":
                continue
            found = re.findall(r"#[0-9A-Fa-f]{6}\b|#[0-9A-Fa-f]{3}\b", re.sub(r"/\*.*?\*/", "", sheet.read_text(encoding="utf-8"), flags=re.DOTALL))
            if found:
                offenders.append(f"{sheet.name}: {sorted(set(found))}")
    assert not offenders, offenders
    base = (TEMPLATES / "base.html").read_text(encoding="utf-8")
    assert "<style>" not in base


def test_the_picker_offers_every_theme_the_stylesheet_declares():
    """theme.js is the single source of truth for which themes exist, theme-init.js applies a stored
    one before the first paint, and the theme menu offers each with a swatch."""
    script = (STATIC / "js" / "theme.js").read_text(encoding="utf-8")
    order = re.search(r"var THEMES = \[([^\]]*)\]", script).group(1)
    assert re.findall(r'"([a-z-]+)"', order) == THEMES
    init = (STATIC / "js" / "theme-init.js").read_text(encoding="utf-8")
    menu = (TEMPLATES / "_theme_menu.html").read_text(encoding="utf-8")
    swatches = TOKENS.read_text(encoding="utf-8")
    for theme in THEMES:
        assert f'"{theme}"' in init, theme
        assert f"'{theme}'" in menu, theme
        assert f".swatch-{theme} " in swatches, theme
    for gone in ("dark", "system"):
        assert f'"{gone}"' not in order
