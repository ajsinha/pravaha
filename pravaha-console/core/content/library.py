"""
Pravaha console — content library.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The content library.

Help and explanatory pages are markdown files on disk, rendered at request
time. They are not templates and not database rows, and both of those were
considered.

Not templates, because help text that lives inside markup can only be changed by
someone who can edit markup, which is the wrong constraint on the people who
actually know what the help should say. Not rows, because then it would need a
migration path, an editor, and a backup story to change a sentence — and it
would stop being reviewable in a pull request alongside the behaviour it
describes.

Files get the useful properties for free: version control, diff review, blame,
and a topic that cannot drift from the release it shipped with.

Rendered HTML is cached against the file's modification time, so editing a topic
shows up on the next request without a restart, and an unchanged topic is not
re-parsed on every page view.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from core.content.frontmatter import split
from core.content.renderer import MarkdownRenderer

logger = logging.getLogger(__name__)


@dataclass
class Topic:
    """One rendered content file."""
    slug: str
    title: str
    summary: str = ""
    section: str = "General"
    order: int = 500
    icon: str = "file-text"
    audience: str = ""
    html: str = ""
    headings: list[dict[str, Any]] = field(default_factory=list)
    source: str = ""
    #: Every front-matter key, including the ones only one area reads -- a help topic's
    #: ``category``, ``badge``, ``guide`` and ``keywords`` -- so an area can add a field
    #: without this class learning about it.
    meta: dict[str, Any] = field(default_factory=dict)
    #: The markdown the page was rendered from, for search and for the checks that read it.
    body: str = ""

    @property
    def anchors(self) -> list[dict[str, Any]]:
        """Second-level headings only — an on-page contents list, not an index."""
        return [h for h in self.headings if h["level"] == 2]


class ContentLibrary:
    """Loads a directory of markdown into Topics, cached on modification time."""

    def __init__(self, root: Path, renderer: MarkdownRenderer | None = None,
                 include_root: Path | None = None):
        self.root = Path(root).resolve()
        self.renderer = renderer or MarkdownRenderer()
        # Where `include:` resolves from: the repository's own docs/ tree. A
        # topic may point at one of those documents rather than copying it.
        self.include_root = Path(include_root).resolve() if include_root \
            else self.root.parent.parent
        self._cache: dict[Path, tuple[float, Topic]] = {}

    # --------------------------------------------------------------- include
    def _included(self, meta: dict[str, Any], body: str, origin: Path) -> str:
        """Renders a document that lives elsewhere, when a topic names one.

        The engine's documentation is in ``docs/`` at the repository root and is
        the source of truth for it. Copying a document here to give it a card in
        the index would create a second copy that drifts from the first, and the
        drift would be invisible: both would render, and only one would be right.

        So a topic may carry ``include: docs/guides/CONCEPTS.md`` and hold nothing but
        its own front matter and, optionally, a paragraph of its own before the
        included text. One document, two places it can be read from.
        """
        source = meta.get("include")
        if not source:
            return body
        target = (self.include_root / source).resolve()
        # Refused rather than read. An `include` that escapes the root is either
        # a mistake or an attempt, and reading it would turn a content directory
        # into a file browser.
        if self.include_root not in target.parents:
            logger.warning("%s includes %s, which is outside %s -- refused",
                           origin, source, self.include_root)
            return body + f"\n\n> This topic names `{source}`, which is outside the "\
                          f"documentation root and was not read."
        if not target.exists():
            logger.warning("%s includes %s, which does not exist", origin, source)
            return body + f"\n\n> This topic names `{source}`, which is not present in "\
                          f"this installation."
        return body + "\n\n" + target.read_text(encoding="utf-8")

    # ------------------------------------------------------------------ load
    def _load(self, path: Path) -> Topic | None:
        try:
            stamp = path.stat().st_mtime
        except OSError as exc:
            logger.warning("content file vanished between listing and load: %s (%s)",
                           path, exc)
            return None
        cached = self._cache.get(path)
        if cached and cached[0] == stamp:
            return cached[1]

        meta, body = split(path.read_text(encoding="utf-8"), origin=str(path))
        body = self._included(meta, body, path)
        html, headings = self.renderer.render(body)
        topic = Topic(
            slug=meta.get("slug") or self._slug(path),
            title=meta.get("title") or self._slug(path).replace("-", " ").capitalize(),
            summary=meta.get("summary", ""), section=meta.get("section", "General"),
            order=int(meta.get("order", 500)), icon=meta.get("icon", "file-text"),
            audience=meta.get("audience", ""), html=html, headings=headings,
            source=str(path.relative_to(self.root)) if self.root in path.parents
            else path.name, meta=meta, body=body)
        self._cache[path] = (stamp, topic)
        return topic

    @staticmethod
    def _slug(path: Path) -> str:
        """Filenames carry a numeric prefix for ordering; URLs should not."""
        stem = path.stem
        return stem.split("-", 1)[1] if stem[:2].isdigit() and "-" in stem else stem

    # ----------------------------------------------------------------- query
    def paths(self, area: str) -> list[Path]:
        directory = self.root / area
        return sorted(directory.glob("*.md")) if directory.is_dir() else []

    def topics(self, area: str) -> list[Topic]:
        """Every topic in an area, ordered by ``order`` then title."""
        found = [t for t in (self._load(p) for p in self.paths(area)) if t]
        return sorted(found, key=lambda t: (t.order, t.title))

    def get(self, area: str, slug: str) -> Topic | None:
        return next((t for t in self.topics(area) if t.slug == slug), None)

    def sections(self, area: str) -> list[tuple[str, list[Topic]]]:
        """Topics grouped into sections, each in the order the group first appears."""
        grouped: dict[str, list[Topic]] = {}
        for topic in self.topics(area):
            grouped.setdefault(topic.section, []).append(topic)
        return list(grouped.items())

    def available(self) -> bool:
        return self.root.is_dir()
