"""The documentation's screenshots: none missing, none left over, every one reproducible.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Every image under ``docs/assets/screenshots/`` is made by ``tools/docs_screenshots.py`` from its
declared list. A document that shows an image that is not there fails here, so does an image that no
document shows, and so does an image the list cannot make -- the three ways a screenshot rots. No
browser is needed: this checks the bookkeeping, and the tool makes the pictures.
"""
from __future__ import annotations

import importlib.util
import pathlib
import re
import sys

CONSOLE = pathlib.Path(__file__).resolve().parents[1]
REPO = CONSOLE.parent
SCREENSHOTS = REPO / "docs" / "assets" / "screenshots"
#: Where documents that may show a screenshot live: the docs, and the console's own content.
SEARCHED = (REPO / "docs", CONSOLE / "content", REPO / "README.md")
IMAGE = re.compile(r"(?:!\[[^\]]*\]\(|src=[\"'])([^)\s\"']*assets/screenshots/[^)\s\"']+\.png)")


def _tool():
    spec = importlib.util.spec_from_file_location("docs_screenshots", CONSOLE / "tools" / "docs_screenshots.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # a dataclass resolves its annotations through it
    spec.loader.exec_module(module)
    return module


def _documents() -> list[pathlib.Path]:
    found: list[pathlib.Path] = []
    for place in SEARCHED:
        found.extend([place] if place.is_file() else sorted(place.rglob("*.md")))
    return found


def shown() -> dict[str, list[str]]:
    """Each screenshot a document shows, by file name, with the documents showing it. A reference
    that does not resolve under ``docs/assets/screenshots/`` is kept as written, so it is missing."""
    out: dict[str, list[str]] = {}
    for doc in _documents():
        for reference in IMAGE.findall(doc.read_text(encoding="utf-8")):
            target = (doc.parent / reference).resolve()
            name = target.name if target.parent == SCREENSHOTS.resolve() else reference
            out.setdefault(name, []).append(str(doc.relative_to(REPO)))
    return out


def test_every_screenshot_a_document_shows_exists_and_is_a_png():
    references = shown()
    assert len(references) >= 15
    for name, documents in references.items():
        image = SCREENSHOTS / name
        assert image.is_file(), (f"{documents} show {name}, which is missing: "
                                 "pravaha-console/.venv/bin/python tools/docs_screenshots.py --only "
                                 f"{pathlib.Path(name).stem}")
        data = image.read_bytes()
        assert data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) > 2000, name


def test_no_screenshot_is_left_over_that_no_document_shows():
    present = {p.name for p in SCREENSHOTS.glob("*") if p.is_file()}
    orphans = sorted(present - set(shown()))
    assert not orphans, f"docs/assets/screenshots/ holds images no document shows: {orphans}"


def test_every_screenshot_is_one_the_tool_can_make():
    tool = _tool()
    declared = {f"{s.name}.png" for s in tool.SHOTS}
    assert len(declared) == len(tool.SHOTS), "a shot is declared twice"
    unmade = sorted(set(shown()) - declared)
    assert not unmade, f"documents show screenshots tools/docs_screenshots.py does not declare: {unmade}"
    undocumented = sorted(declared - set(shown()))
    assert not undocumented, f"declared shots no document shows: {undocumented}"


def test_each_declared_shot_names_a_page_the_harness_has_or_a_path_and_a_known_action():
    tool = _tool()
    harness = (CONSOLE / "tests" / "browser_harness.py").read_text(encoding="utf-8")
    for shot in tool.SHOTS:
        assert shot.source in ("fake", "node"), shot
        if shot.page:
            assert f'("{shot.page}", "/' in harness, f"{shot.name}: no harness page {shot.page!r}"
        else:
            assert shot.path and shot.path.startswith("/"), shot
        assert shot.action is None or shot.action in tool.ACTIONS, shot


def test_a_missing_or_orphan_image_is_caught(tmp_path, monkeypatch):
    """The checks above, against a docs tree with one of each."""
    shots = tmp_path / "docs" / "assets" / "screenshots"
    shots.mkdir(parents=True)
    (shots / "kept.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"0" * 3000)
    (shots / "orphan.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"0" * 3000)
    (tmp_path / "docs" / "guide.md").write_text(
        "![a](assets/screenshots/kept.png) ![b](assets/screenshots/gone.png)\n", encoding="utf-8")
    monkeypatch.setattr(sys.modules[__name__], "SCREENSHOTS", shots)
    monkeypatch.setattr(sys.modules[__name__], "SEARCHED", (tmp_path / "docs",))
    monkeypatch.setattr(sys.modules[__name__], "REPO", tmp_path)
    references = shown()
    assert set(references) == {"kept.png", "gone.png"}
    assert not (shots / "gone.png").is_file()
    assert {p.name for p in shots.iterdir()} - set(references) == {"orphan.png"}
