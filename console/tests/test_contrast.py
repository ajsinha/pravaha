"""Design 23.14: contrast "in every theme -- verified by a token-level test, not by eye".

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The axe audit (``test_browser_accessibility.py``) checks the pixels a page actually drew, but
only where it can resolve a background -- it passes over text on a gradient, and it never sees
a combination no current page happens to use. This test checks the combinations the design
system *allows*: every text token on every ground it may sit on, in every theme, computed
with the WCAG 2.x relative-luminance formula from the hex values in ``static/css/tokens.css``.
It needs no browser, so it runs everywhere.

The console follows MAYA's design language: MAYA's four themes (Crimson, Dark, Blue, Green) and
MAYA's token roles, named ``--pv-<role>``. Where tokens.css departs from a MAYA value it says so
beside the value, and the reason is one of the assertions below.
"""
from __future__ import annotations

import pathlib
import re

import pytest

STATIC = pathlib.Path(__file__).resolve().parents[1] / "web" / "static"
TOKENS = STATIC / "css" / "tokens.css"
THEME_CSS = STATIC / "css" / "theme.css"
TEMPLATES = STATIC.parent / "templates"

#: The grounds text may sit on: the page, a card, a hovered row or menu item (the accent's tint,
#: as MAYA hovers), and the neutral wash under code and skeletons.
GROUNDS = ("canvas", "surface", "crimson-tint", "wash")

#: (foreground, background, minimum): text at 4.5:1, UI boundaries at 3:1 (WCAG 1.4.3, 1.4.11).
#: Token names are the roles, without the ``pv-`` prefix.
PAIRS = [
    *[(fg, bg, 4.5) for fg in ("ink", "slate", "crimson", "heading") for bg in GROUNDS],
    ("indigo", "canvas", 4.5), ("indigo", "surface", 4.5),       # the heading gradient's last stop
    ("on-crimson", "crimson", 4.5),         # a primary button's label
    ("on-crimson", "crimson-strong", 4.5),  # the same button, hovered
    ("crimson-strong", "surface", 4.5),     # a hovered link
    ("nav-from", "on-nav", 4.5),            # a light button on a hero, and the avatar's initial
    ("ok", "ok-soft", 4.5), ("warn", "warn-soft", 4.5),          # status chips
    ("bad", "bad-soft", 4.5), ("info", "info-soft", 4.5),
    *[(fg, bg, 4.5) for fg in ("ok", "warn", "bad", "info")     # outlined status pills
      for bg in ("surface", "canvas")],
    *[(fg, bg, 4.5) for fg in ("ink", "crimson")                # alerts, and a link in one
      for bg in ("ok-soft", "warn-soft", "bad-soft", "info-soft")],
    ("ink", "highlight", 4.5),                                  # a search hit
    ("edge", "surface", 3.0), ("edge", "canvas", 3.0),          # form-control borders
    ("crimson", "surface", 3.0),                                # the focus ring
    # The navigation bar is a gradient, which is the one background axe cannot resolve, so
    # its three stops are tokens and the bar's text is checked against each of them here.
    ("on-nav", "nav-from", 4.5), ("on-nav", "nav-via", 4.5), ("on-nav", "nav-to", 4.5),
    # A retraction's weight on the landing page: written in its own colour on the page's
    # ground (the gradient headline, the stats), and as a −1 on a disc of it (the figure and
    # its legend). Text, so 4.5 each way.
    ("retract", "canvas", 4.5), ("retract", "surface", 4.5), ("on-crimson", "retract", 4.5),
]

#: Every theme tokens.css declares: MAYA's four. "light" is the Crimson theme, stored under the
#: name MAYA stores it under.
THEMES = ["light", "dark", "blue", "green"]

#: How far apart, in CIELAB, the accent has to be from each status colour. The accent says
#: "this product" and the status colours say what is happening; a reader who has to compare
#: two swatches to tell a brand link from a failure has been told nothing by either. It is why
#: `bad` departs from MAYA's (MAYA's is its own crimson-strong).
ACCENT_APART = 25.0

_HEX = r"--pv-([a-z0-9-]+):\s*(#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{3})\b"


def _css() -> str:
    return TOKENS.read_text(encoding="utf-8")


def _body(name: str) -> str:
    css = _css()
    if name == "light":
        match = re.search(r"\n:root \{(.*?)\n\}", css, re.DOTALL)
    else:
        match = re.search(r'\n:root\[data-theme="' + name + r'"\] \{(.*?)\n\}', css, re.DOTALL)
    assert match, f"no {name} token block in tokens.css"
    return match.group(1)


def _themes() -> dict[str, dict[str, str]]:
    return {name: dict(re.findall(_HEX, _body(name))) for name in THEMES}


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
        assert fg in tokens and bg in tokens, f"{theme}: --pv-{fg} or --pv-{bg} is not a hex token"
        ratio = contrast(tokens[fg], tokens[bg])
        if ratio < minimum:
            failures.append(f"--pv-{fg} {tokens[fg]} on --pv-{bg} {tokens[bg]}: {ratio:.2f} < {minimum}")
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


@pytest.mark.parametrize("theme", THEMES)
def test_the_accent_is_not_confusable_with_a_status_colour(theme):
    """The accent is the product; ok, warn, bad and info are what is happening. With a
    crimson accent `bad` is the one that has to be moved out of its way, and this is what
    stops it drifting back -- green's accent sits beside a bluish `ok`, which is why green
    declares a yellower one."""
    tokens = _themes()[theme]
    too_close = [f"--pv-crimson {tokens['crimson']} and --pv-{name} {tokens[name]}: "
                 f"{difference(tokens['crimson'], tokens[name]):.1f} < {ACCENT_APART}"
                 for name in ("ok", "warn", "bad", "info")
                 if difference(tokens["crimson"], tokens[name]) < ACCENT_APART]
    assert not too_close, f"{theme} theme:\n  " + "\n  ".join(too_close)


@pytest.mark.parametrize("theme", THEMES)
def test_the_status_colours_are_not_confusable_with_each_other(theme):
    """"Running", "watch this", "wrong" and "for information" have to be four colours, not two
    pairs."""
    tokens = _themes()[theme]
    names = ("ok", "warn", "bad", "info")
    too_close = [f"--pv-{a} {tokens[a]} and --pv-{b} {tokens[b]}: {difference(tokens[a], tokens[b]):.1f}"
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
    css = THEME_CSS.read_text(encoding="utf-8")
    rule = re.search(r"\n\.stale \{([^}]*)\}", css)
    assert rule and "opacity" not in rule.group(1) and "grayscale(1)" in rule.group(1), rule
    tokens = _themes()[theme]
    failures = [f"--pv-{fg} on --pv-{bg}: {contrast(_grayscale(tokens[fg]), _grayscale(tokens[bg])):.2f}"
                for fg, bg, minimum in PAIRS
                if contrast(_grayscale(tokens[fg]), _grayscale(tokens[bg])) < minimum]
    assert not failures, f"{theme}, stale:\n  " + "\n  ".join(failures)


def test_the_dark_theme_the_os_selects_is_the_dark_theme_the_picker_selects():
    """Two copies of the dark tokens exist (the picker's, and prefers-color-scheme's); a fix
    to one that misses the other would pass every test run with the picker and fail every
    person who never touched it."""
    media = re.search(r"@media \(prefers-color-scheme: dark\) \{\s*:root[^{]*\{(.*?)\n  \}", _css(), re.DOTALL)
    assert media
    picked = _themes()["dark"]
    found = re.findall(_HEX, media.group(1))
    assert len(found) == len(picked)
    for name, value in found:
        assert picked.get(name, "").lower() == value.lower(), f"--pv-{name}: {value} vs {picked.get(name)}"


def test_every_theme_declares_every_token():
    """A theme that leaves a token out inherits it from `:root`, the crimson theme -- the "light
    island" bug, where a dark page renders white chips. MAYA's rule, and the console's: each
    theme redefines the same tokens and nothing else."""
    light = set(re.findall(r"--pv-([a-z0-9-]+):", _body("light")))
    for theme in THEMES[1:]:
        declared = set(re.findall(r"--pv-([a-z0-9-]+):", _body(theme)))
        assert declared == light, f"{theme}: missing {sorted(light - declared)}, extra {sorted(declared - light)}"


def test_colour_is_defined_only_in_tokens_css():
    """MAYA's rule: tokens.css is the only place a colour is defined. The theme stylesheet and
    the shell may use a token, never a hex value of their own (white and black shadows aside,
    which are depth rather than colour)."""
    theme = THEME_CSS.read_text(encoding="utf-8")
    assert not re.findall(r"#[0-9A-Fa-f]{6}\b|#[0-9A-Fa-f]{3}\b", theme)
    base = (TEMPLATES / "base.html").read_text(encoding="utf-8")
    assert "<style>" not in base


@pytest.mark.parametrize("theme", THEMES)
def test_a_retraction_is_neither_the_accent_nor_a_failure(theme):
    """The landing page draws an insert as a +1 in the accent and a retraction as a −1 in
    `--pv-retract`. That is the idea the product rests on -- the answer is maintained, and a
    retraction corrects it -- so the two have to be told apart at a glance; and a correction is
    not an error, so the −1 must not look like `bad` either."""
    tokens = _themes()[theme]
    for other in ("crimson", "bad"):
        apart = difference(tokens["retract"], tokens[other])
        assert apart >= ACCENT_APART, f"{theme}: --pv-retract {tokens['retract']} and --pv-{other} {tokens[other]}: {apart:.1f}"


def test_the_system_dark_theme_leaves_every_chosen_theme_alone():
    """The OS preference applies to somebody who has not chosen. Any choice is a choice, so the
    media query applies only where no theme is set -- or an OS set to dark repaints the blue
    theme's cards dark under its light text tokens."""
    selector = re.search(r"@media \(prefers-color-scheme: dark\) \{\s*(:root[^{]*)\{", _css()).group(1)
    assert selector.strip() == ":root:not([data-theme])"


def test_the_picker_offers_every_theme_the_stylesheet_declares():
    """theme.js is the single source of truth for which themes exist, the inline snippet in
    base.html applies a stored one before the first paint, and the theme menu offers each with a
    swatch; a theme any of them forgets is a theme that flashes crimson on every navigation, or
    cannot be chosen at all."""
    script = (STATIC / "js" / "theme.js").read_text(encoding="utf-8")
    order = re.search(r"var THEMES = \[([^\]]*)\]", script).group(1)
    base = (TEMPLATES / "base.html").read_text(encoding="utf-8")
    menu = (TEMPLATES / "_theme_menu.html").read_text(encoding="utf-8")
    for theme in THEMES:
        assert f'"{theme}"' in order, theme
        assert f'"{theme}"' in base.split("pravaha.theme")[1].split("</script>")[0], theme
        assert f"('{theme}'," in menu, theme
    for gone in ("terminal", "system"):
        assert gone not in order and f"('{gone}'," not in menu
