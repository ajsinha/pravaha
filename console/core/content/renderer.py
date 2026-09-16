"""
Pravaha console — content library.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Markdown rendering.

Server-side and vendored, like everything else the interface depends on. The
alternative — shipping a JavaScript renderer and parsing in the browser — would
mean the help system stops working exactly when someone has disabled scripts or
is reading through a restricted desktop, which in a bank is a real audience
rather than a hypothetical one.

Extensions are chosen for what technical documentation actually needs: tables,
fenced code with highlighting, definition lists, footnotes, and heading anchors
so a section can be linked to directly from a runbook.
"""
from __future__ import annotations

import logging
import re
from typing import Any, ClassVar

import markdown

logger = logging.getLogger(__name__)

EXTENSIONS = ["extra", "tables", "fenced_code", "codehilite", "toc",
              "sane_lists", "attr_list", "admonition", "footnotes"]
CONFIG: dict[str, dict[str, Any]] = {
    "codehilite": {"css_class": "highlight", "guess_lang": False},
    "toc": {"permalink": False, "toc_depth": "2-3"},
}


class MarkdownRenderer:
    """Renders markdown to HTML and reports the headings it found.

    A fresh Markdown instance per render: the library carries state between
    calls (the toc, footnote counters), and reusing one silently leaks a
    document's table of contents into the next one.
    """

    #: Where a document that ships in `docs/` is reachable inside the console.
    #:
    #: The included documents cross-reference each other as files -- `[Concepts]
    #: (CONCEPTS.md)` -- which is right when they are read in a checkout and a
    #: dead link when they are read here. Rewritten rather than edited in the
    #: source, because the source is also read on disk and in a pull request,
    #: where the file link is the correct one.
    ROUTES: ClassVar[dict[str, str]] = {
        "QUICKSTART.md": "/help/quickstart",
        "CONCEPTS.md": "/help/concepts",
        "USER_GUIDE.md": "/help/user-guide",
        "CONTINUOUS_QUERIES.md": "/help/continuous-queries",
        "ARCHITECTURE.md": "/help/architecture",
        "EXECUTION_MODEL.md": "/help/execution-model",
        "CONNECTORS.md": "/help/connectors",
        "OPERATIONS.md": "/help/operations",
        "SECURITY.md": "/help/security",
        "TROUBLESHOOTING.md": "/help/troubleshooting",
    }

    _LINK = re.compile(r'(href=")([^"]+)(")')

    def render(self, text: str) -> tuple[str, list[dict[str, Any]]]:
        engine = markdown.Markdown(extensions=EXTENSIONS, extension_configs=CONFIG)
        html = self._relink(engine.convert(text))
        return html, self._headings(getattr(engine, "toc_tokens", []))

    def _relink(self, html: str) -> str:
        """Points cross-references at the console rather than at files on disk."""
        def fix(match):
            prefix, target, suffix = match.groups()
            if target.startswith(("http://", "https://", "#", "/", "mailto:")):
                return match.group(0)
            name = target.split("/")[-1]
            anchor = ""
            if "#" in name:
                name, anchor = name.split("#", 1)
                anchor = "#" + anchor
            route = self.ROUTES.get(name)
            if route:
                return prefix + route + anchor + suffix
            # A relative link to something the console does not serve -- an ADR,
            # a source file, an example. Left as text rather than pointed at a
            # route that does not exist: a link that 404s is worse than one that
            # does not invite the click.
            return prefix + "#" + suffix
        return self._LINK.sub(fix, html)

    def _headings(self, tokens) -> list[dict[str, Any]]:
        """Flatten the nested toc into a list a template can iterate."""
        out: list[dict[str, Any]] = []
        for t in tokens:
            out.append({"id": t["id"], "name": t["name"], "level": t["level"]})
            out.extend(self._headings(t.get("children", [])))
        return out
