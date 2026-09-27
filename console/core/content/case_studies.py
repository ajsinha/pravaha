"""The case studies in Help: a card for each, and each study's README rendered as its page.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The READMEs are the source, and so is the list of them: the table in
``examples/case-studies/README.md`` gives each study's order, title, domain, store and what it
shows, so a study appears in Help when it appears there, and nothing in the console has to be kept
in step. A row whose folder has no README is left out rather than shown as a card that leads
nowhere.

Rendering is the console's own renderer, plus the links a README writes for a reader of the
repository: a link to another study opens that study's page, a link to the setup notes opens the
setup tutorial, and a link to one of the study's own files (``sql/01-….sql``, ``conf/…``) opens it
in the repository, since the console serves the study's prose and not its scripts.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

REPOSITORY = "https://github.com/ajsinha/pravaha/blob/main/examples/case-studies"

# | [Title](folder/) | Domain | Store | What it shows |
_ROW = re.compile(r"^\|\s*\[([^\]]+)\]\(([\w-]+)/?\)\s*\|([^|]*)\|([^|]*)\|([^|\n]*)\|", re.M)
_LINK = re.compile(r"\]\(([^)\s]+)\)")


@dataclass(frozen=True)
class CaseStudy:
    """One row of the index table, for a card."""

    slug: str
    title: str
    domain: str
    store: str
    shows: str
    number: int


def _plain(markdown_cell: str) -> str:
    """A table cell as a card reads it: no emphasis marks, no backticks."""
    return re.sub(r"\*\*|`", "", markdown_cell).strip()


def catalog(root: Path) -> list[CaseStudy]:
    """Every study the index lists and the installation carries, in the index's order.

    ``root`` is the directory ``include:`` resolves from: the repository root in a checkout,
    ``/opt/pravaha`` in the console image.
    """
    studies = root / "examples" / "case-studies"
    index = studies / "README.md"
    if not index.is_file():
        return []
    out: list[CaseStudy] = []
    for title, folder, domain, store, shows in _ROW.findall(index.read_text(encoding="utf-8")):
        if (studies / folder / "README.md").is_file():
            out.append(CaseStudy(folder, title.strip(), _plain(domain), _plain(store), _plain(shows), len(out) + 1))
    return out


def find(root: Path, slug: str) -> CaseStudy | None:
    """The study called ``slug``, if the index lists it. The allow-list a path is built from."""
    return next((s for s in catalog(root) if s.slug == slug), None)


def neighbours(root: Path, study: CaseStudy) -> tuple[CaseStudy | None, CaseStudy | None]:
    """The studies before and after this one, for the page's previous and next links."""
    studies = catalog(root)
    at = next(i for i, s in enumerate(studies) if s.slug == study.slug)
    return (studies[at - 1] if at > 0 else None, studies[at + 1] if at + 1 < len(studies) else None)


def relink(readme: str, slug: str, root: Path) -> str:
    """Rewrites a README's relative Markdown links for where the console serves it.

    Done on the Markdown, before rendering: the console's renderer turns a relative link it does
    not recognise into ``#``, which is right for a help topic and would leave every link in a
    study -- to its SQL, its configuration, the study beside it -- dead. Fenced code is left alone.
    """
    studies = root / "examples" / "case-studies"

    def target_for(href: str) -> str:
        if href.startswith(("http://", "https://", "#", "mailto:", "/")):
            return href
        path, _, anchor = href.partition("#")
        suffix = f"#{anchor}" if anchor else ""
        other = re.fullmatch(r"\.\./([\w-]+)/?(?:README\.md)?", path)
        if other and (studies / other.group(1) / "README.md").is_file():
            return f"/help/case-studies/{other.group(1)}{suffix}"
        if path in ("../SETUP.md", "../SETUP"):
            return "/tutorials/setup"
        if path in ("../README.md", "../", ".."):
            return "/help/case-studies"
        if path.startswith("../../../docs/"):
            # A document the console serves itself -- a guide, a decision record: the renderer
            # knows its route, so it is handed over as written.
            return href
        if path.startswith("../../../"):
            # Out of examples/ altogether: docs/, LICENSE. The repository has them.
            return f"{REPOSITORY.rsplit('/examples/', 1)[0]}/{path[len('../../../'):]}{suffix}"
        target = path[3:] if path.startswith("../") else f"{slug}/{path}"
        return f"{REPOSITORY}/{target}{suffix}"

    parts = re.split(r"(```.*?```)", readme, flags=re.S)
    for i, part in enumerate(parts):
        if not part.startswith("```"):
            parts[i] = _LINK.sub(lambda m: f"]({target_for(m.group(1))})", part)
    return "".join(parts)


def title_of(readme: str, fallback: str) -> str:
    """The README's first ``# `` heading, which is the study's own name for itself."""
    return next((line[2:].strip() for line in readme.splitlines() if line.startswith("# ")), fallback)
