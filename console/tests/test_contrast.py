"""Design 23.14: contrast "in both themes -- verified by a token-level test, not by eye".

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The axe audit (``test_browser_accessibility.py``) checks the pixels a page actually drew, but
only where it can resolve a background -- it passes over text on a gradient, and it never sees
a combination no current page happens to use. This test checks the combinations the design
system *allows*: every text token on every ground it may sit on, in every theme, computed
with the WCAG 2.x relative-luminance formula from the hex values in ``base.html``. It needs
no browser, so it runs everywhere.
"""
from __future__ import annotations

import pathlib
import re

import pytest

BASE = pathlib.Path(__file__).resolve().parents[1] / "web" / "templates" / "base.html"

#: (foreground, background, minimum): text at 4.5:1, UI boundaries at 3:1 (WCAG 1.4.3, 1.4.11).
PAIRS = [
    *[(fg, bg, 4.5) for fg in ("ink", "slate", "muted", "flow")
      for bg in ("canvas", "surface", "raised", "stripe")],
    ("on-accent", "flow", 4.5),          # a primary button's label
    ("on-flow", "flow", 4.5),            # text on the accent (hero, filled chips)
    ("flow-d", "on-flow", 4.5),          # the hero's call to action, and a hovered link
    ("ok", "ok-soft", 4.5), ("warn", "warn-soft", 4.5),          # status chips
    ("bad", "bad-soft", 4.5), ("info", "info-soft", 4.5),
    *[(fg, bg, 4.5) for fg in ("ink", "flow")                   # alerts, and a link in one
      for bg in ("ok-soft", "warn-soft", "bad-soft", "info-soft")],
    ("edge", "surface", 3.0), ("edge", "canvas", 3.0),          # form-control borders
    ("flow", "surface", 3.0),                                   # the focus ring
    # The navigation bar is a gradient, which is the one background axe cannot resolve, so
    # its three stops are tokens and the bar's text is checked against each of them here.
    ("on-bar", "bar-from", 4.5), ("on-bar", "bar-via", 4.5), ("on-bar", "bar-to", 4.5),
]

#: Every theme base.html declares. Blue is a named palette, like terminal: a complete block of
#: its own, held to every rule below.
THEMES = ["light", "dark", "terminal", "blue"]
#: The themes whose accent is the product's own colour on a ground, rather than a palette whose
#: accent is by design a step of a status colour (terminal's amber is `--warn`'s hue).
BRAND_THEMES = ["light", "dark", "blue"]

#: How far apart, in CIELAB, the accent has to be from each status colour. The accent says
#: "this product" and the status colours say what is happening; a reader who has to compare
#: two swatches to tell a brand link from a failure has been told nothing by either. The bar
#: is the distance the palette already holds -- it exists so that the next accent cannot
#: quietly be picked next to `--bad`, which is exactly what a crimson brand invites.
ACCENT_APART = 25.0


def _themes() -> dict[str, dict[str, str]]:
    css = BASE.read_text(encoding="utf-8")
    blocks = {
        "light": re.search(r":root\{(.*?)\n\}", css, re.DOTALL),
        "dark": re.search(r':root\[data-theme="dark"\]\{(.*?)\n\}', css, re.DOTALL),
        "terminal": re.search(r':root\[data-theme="terminal"\]\{(.*?)\n\}', css, re.DOTALL),
        "blue": re.search(r':root\[data-theme="blue"\]\{(.*?)\n\}', css, re.DOTALL),
    }
    themes: dict[str, dict[str, str]] = {}
    light: dict[str, str] = {}
    for name, match in blocks.items():
        assert match, f"no {name} token block in base.html"
        tokens = dict(re.findall(r"--([a-z0-9-]+):\s*(#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{3})\b", match.group(1)))
        themes[name] = {**light, **tokens} if name != "light" else tokens
        if name == "light":
            light = tokens
    return themes


def _luminance(hex_colour: str) -> float:
    if len(hex_colour) == 4:
        hex_colour = "#" + "".join(c * 2 for c in hex_colour[1:])
    channels = [int(hex_colour[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def contrast(a: str, b: str) -> float:
    la, lb = sorted((_luminance(a), _luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def test_the_formula_matches_the_published_reference_points():
    assert contrast("#000000", "#FFFFFF") == pytest.approx(21.0)
    assert contrast("#777777", "#FFFFFF") == pytest.approx(4.48, abs=0.01)


@pytest.mark.parametrize("theme", THEMES)
def test_every_allowed_token_pair_meets_wcag_aa(theme):
    tokens = _themes()[theme]
    failures = []
    for fg, bg, minimum in PAIRS:
        assert fg in tokens and bg in tokens, f"{theme}: --{fg} or --{bg} is not a hex token"
        ratio = contrast(tokens[fg], tokens[bg])
        if ratio < minimum:
            failures.append(f"--{fg} {tokens[fg]} on --{bg} {tokens[bg]}: {ratio:.2f} < {minimum}")
    assert not failures, f"{theme} theme:\n  " + "\n  ".join(failures)


def _lab(hex_colour: str) -> tuple[float, float, float]:
    """CIELAB (D65), for a distance between two colours rather than a contrast ratio."""
    if len(hex_colour) == 4:
        hex_colour = "#" + "".join(c * 2 for c in hex_colour[1:])
    srgb = [int(hex_colour[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    r, g, b = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in srgb]
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

    def f(t: float) -> float:
        return t ** (1 / 3) if t > 216 / 24389 else (24389 / 27 * t + 16) / 116

    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def difference(a: str, b: str) -> float:
    """CIE76 ΔE. Blunt, and blunt is what is wanted: it is a floor, not a grade."""
    la, aa, ba = _lab(a)
    lb, ab, bb = _lab(b)
    return ((la - lb) ** 2 + (aa - ab) ** 2 + (ba - bb) ** 2) ** 0.5


@pytest.mark.parametrize("theme", BRAND_THEMES)
def test_the_accent_is_not_confusable_with_a_status_colour(theme):
    """The accent is the product; ok, warn, bad and info are what is happening. With a
    crimson accent `--bad` is the one that has to be moved out of its way, and this is what
    stops it drifting back.

    Every theme but terminal: blue is held to it as light is. The terminal theme is not the product's colours on another ground,
    it is a named palette whose accent *is* its amber, and there `--warn` is a step of that
    same amber (14.9 apart, and the next test says so rather than leaving it unmeasured).
    """
    tokens = _themes()[theme]
    too_close = [f"--flow {tokens['flow']} and --{name} {tokens[name]}: "
                 f"{difference(tokens['flow'], tokens[name]):.1f} < {ACCENT_APART}"
                 for name in ("ok", "warn", "bad", "info")
                 if difference(tokens["flow"], tokens[name]) < ACCENT_APART]
    assert not too_close, f"{theme} theme:\n  " + "\n  ".join(too_close)


@pytest.mark.parametrize("theme", THEMES)
def test_the_status_colours_are_not_confusable_with_each_other(theme):
    """The property that still has to hold in the terminal theme, where the accent shares
    its hue with `--warn`: "running", "watch this", "wrong" and "for information" have to be
    four colours, not two pairs."""
    tokens = _themes()[theme]
    names = ("ok", "warn", "bad", "info")
    too_close = [f"--{a} {tokens[a]} and --{b} {tokens[b]}: {difference(tokens[a], tokens[b]):.1f}"
                 for i, a in enumerate(names) for b in names[i + 1:]
                 if difference(tokens[a], tokens[b]) < ACCENT_APART]
    assert not too_close, f"{theme} theme:\n  " + "\n  ".join(too_close)


def _grayscale(hex_colour: str) -> str:
    """CSS ``filter: grayscale(1)``: the luminance matrix applied to the sRGB-encoded channels."""
    if len(hex_colour) == 4:
        hex_colour = "#" + "".join(c * 2 for c in hex_colour[1:])
    r, g, b = (int(hex_colour[i:i + 2], 16) / 255 for i in (1, 3, 5))
    y = min(1.0, 0.2126 * r + 0.7152 * g + 0.0722 * b)
    return "#" + f"{round(y * 255):02x}" * 3


@pytest.mark.parametrize("theme", THEMES)
def test_stale_data_keeps_its_contrast(theme):
    """Design 23.12's stale state greys out what it shows (``.stale``). It used to fade it to
    55% opacity instead, below 4.5:1 for every word; greyed, every allowed pair still passes."""
    css = BASE.read_text(encoding="utf-8")
    rule = re.search(r"\n\.stale\{([^}]*)\}", css)
    assert rule and "opacity" not in rule.group(1) and "grayscale(1)" in rule.group(1), rule
    tokens = _themes()[theme]
    failures = [f"--{fg} on --{bg}: {contrast(_grayscale(tokens[fg]), _grayscale(tokens[bg])):.2f}"
                for fg, bg, minimum in PAIRS
                if contrast(_grayscale(tokens[fg]), _grayscale(tokens[bg])) < minimum]
    assert not failures, f"{theme}, stale:\n  " + "\n  ".join(failures)


def test_the_dark_theme_the_os_selects_is_the_dark_theme_the_picker_selects():
    """Two copies of the dark tokens exist (the picker's, and prefers-color-scheme's); a fix
    to one that misses the other would pass every test run with the picker and fail every
    person who never touched it."""
    css = BASE.read_text(encoding="utf-8")
    media = re.search(r"@media \(prefers-color-scheme: dark\)\{\s*:root[^{]*\{(.*?)\n  \}", css, re.DOTALL)
    assert media
    picked = _themes()["dark"]
    for name, value in re.findall(r"--([a-z0-9-]+):\s*(#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{3})\b", media.group(1)):
        assert picked.get(name, "").lower() == value.lower(), f"--{name}: {value} vs {picked.get(name)}"


def test_every_named_palette_declares_every_token():
    """A named palette that leaves a token out inherits it from `:root`, the crimson light
    theme -- the "light island" bug, where a black page renders white chips. Every colour token
    the light theme declares is declared again by each of the others, in its own block."""
    css = BASE.read_text(encoding="utf-8")
    light = set(re.findall(r"--([a-z0-9-]+):", re.search(r":root\{(.*?)\n\}", css, re.DOTALL).group(1)))
    layout = ("sans", "serif", "code", "row-h", "cell-", "card-", "gutter", "section-", "state-")
    colour = {name for name in light if not name.startswith(layout)}
    for theme in THEMES[1:]:
        block = re.search(r':root\[data-theme="%s"\]\{(.*?)\n\}' % theme, css, re.DOTALL).group(1)
        missing = colour - set(re.findall(r"--([a-z0-9-]+):", block))
        assert not missing, f"{theme} leaves {sorted(missing)} to the crimson light theme"


def test_the_system_dark_theme_leaves_the_named_palettes_alone():
    """The OS preference applies to somebody who has not chosen. A named palette is a choice,
    so the media query must exclude every one of them -- or an OS set to dark repaints the blue
    theme's cards dark under its light text tokens."""
    css = BASE.read_text(encoding="utf-8")
    selector = re.search(r"@media \(prefers-color-scheme: dark\)\{\s*(:root[^{]*)\{", css).group(1)
    for theme in ("light", "terminal", "blue"):
        assert f':not([data-theme="{theme}"])' in selector, theme
    product = (BASE.parents[1] / "static" / "app" / "product.css").read_text(encoding="utf-8")
    scheme = re.search(r"@media \(prefers-color-scheme: dark\)\{(:root[^{]*)\{color-scheme:dark;", product).group(1)
    for theme in ("light", "blue"):
        assert f':not([data-theme="{theme}"])' in scheme, theme


def test_the_picker_offers_every_theme_the_stylesheet_declares():
    """theme.js is the single source of truth for which themes exist, and the inline snippet in
    base.html applies a stored one before the first paint; a theme either of them forgets is a
    theme that flashes crimson on every navigation, or cannot be chosen at all."""
    script = (BASE.parents[1] / "static" / "js" / "theme.js").read_text(encoding="utf-8")
    order = re.search(r"var ORDER = \[([^\]]*)\]", script).group(1)
    css = BASE.read_text(encoding="utf-8")
    for theme in THEMES[1:]:
        assert f'"{theme}"' in order, theme
        assert f't==="{theme}"' in css, theme
