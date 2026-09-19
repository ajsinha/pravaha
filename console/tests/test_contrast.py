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
]


def _themes() -> dict[str, dict[str, str]]:
    css = BASE.read_text(encoding="utf-8")
    blocks = {
        "light": re.search(r":root\{(.*?)\n\}", css, re.DOTALL),
        "dark": re.search(r':root\[data-theme="dark"\]\{(.*?)\n\}', css, re.DOTALL),
        "terminal": re.search(r':root\[data-theme="terminal"\]\{(.*?)\n\}', css, re.DOTALL),
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


@pytest.mark.parametrize("theme", ["light", "dark", "terminal"])
def test_every_allowed_token_pair_meets_wcag_aa(theme):
    tokens = _themes()[theme]
    failures = []
    for fg, bg, minimum in PAIRS:
        assert fg in tokens and bg in tokens, f"{theme}: --{fg} or --{bg} is not a hex token"
        ratio = contrast(tokens[fg], tokens[bg])
        if ratio < minimum:
            failures.append(f"--{fg} {tokens[fg]} on --{bg} {tokens[bg]}: {ratio:.2f} < {minimum}")
    assert not failures, f"{theme} theme:\n  " + "\n  ".join(failures)


def _grayscale(hex_colour: str) -> str:
    """CSS ``filter: grayscale(1)``: the luminance matrix applied to the sRGB-encoded channels."""
    if len(hex_colour) == 4:
        hex_colour = "#" + "".join(c * 2 for c in hex_colour[1:])
    r, g, b = (int(hex_colour[i:i + 2], 16) / 255 for i in (1, 3, 5))
    y = min(1.0, 0.2126 * r + 0.7152 * g + 0.0722 * b)
    return "#" + f"{round(y * 255):02x}" * 3


@pytest.mark.parametrize("theme", ["light", "dark", "terminal"])
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
