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

import html as html_text
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
        "CONNECTOR_TLS.md": "/help/connector-tls",
        "system_design.md": "/help/system-design",
        "PYTHON_API_GUIDE.md": "/help/python-api-guide",
        # The four lessons in docs/guides/tutorials/, which link to each other and are linked from the
        # case studies by file name.
        "01-your-first-maintained-view.md": "/tutorials/first-maintained-view",
        "02-following-a-view.md": "/tutorials/following-a-view",
        "03-changing-a-running-query.md": "/tutorials/changing-a-running-query",
        "04-investigating-an-incident.md": "/tutorials/investigating-an-incident",
        "RELEASE_NOTES.md": "/help/whats-new",
        "REMAINING.md": "/help/roadmap",
        "LIMITS.md": "/help/limits",
        "DEPLOYMENT.md": "/help/deploying",
        "COMPETITIVE_LANDSCAPE.md": "/about/competitive",
        # The architecture's component pages, docs/design/architecture/*.md.
        "foundations.md": "/help/architecture-foundations",
        "planning.md": "/help/architecture-planning",
        "runtime.md": "/help/architecture-runtime",
        "registry.md": "/help/architecture-registry",
        "ingest-and-egress.md": "/help/architecture-ingest-egress",
        "serving.md": "/help/architecture-serving",
        "governance.md": "/help/architecture-governance",
        "hosts.md": "/help/architecture-hosts",
        "clients-and-console.md": "/help/architecture-clients-console",
        "observability-and-packaging.md": "/help/architecture-observability-packaging",
    }

    #: A help topic linked from a document as its file -- ``../pravaha-console/content/topics/event-time-watermarks.md#late-data``
    #: -- is served at /help/topics/<its stem>; the file link is the right one on GitHub.
    _TOPIC = re.compile(r"(?:^|/)content/topics/([a-z0-9-]+)\.md$")

    #: An ADR, linked as ``adr/043-how-a-continuous-query-names-its-sink.md`` from docs/ or
    #: as ``043-....md`` from inside docs/design/adr/, is served at /help/decisions/<its stem>.
    _ADR = re.compile(r"^(\d{3}-[a-z0-9-]+)\.md$")
    _CODE = re.compile(r"\bPRV-\d{4}\b")
    _TAG = re.compile(r"(<[^>]+>)")
    _GITHUB_RUN = re.compile(r"-{2,}")

    _LINK = re.compile(r'(href=")([^"]+)(")')
    #: An image a document embeds by a path relative to itself, as the IDE guide's screenshots do.
    #: The console does not serve docs/assets/, so it shows the image's description in its place
    #: rather than a broken image.
    _RELATIVE_IMG = re.compile(r'<img alt="([^"]*)" src="(?!https?://|/|data:)[^"]*"\s*/?>')

    def render(self, text: str) -> tuple[str, list[dict[str, Any]]]:
        engine = markdown.Markdown(extensions=EXTENSIONS, extension_configs=CONFIG)
        html = self.link_codes(self._relink(engine.convert(self._brackets(text))))
        html = self._RELATIVE_IMG.sub(lambda m: f'<em class="text-muted">Screenshot: {m.group(1)}</em>', html)
        # A code block wider than its card scrolls, and a region that scrolls must be reachable
        # by keyboard (WCAG 2.1.1; axe's scrollable-region-focusable), so every <pre> is a tab stop.
        html = html.replace("<pre>", '<pre tabindex="0">')
        return html, self._headings(getattr(engine, "toc_tokens", []))

    @staticmethod
    def _brackets(text: str) -> str:
        """``\\<`` and ``\\>`` outside code, as the entities GitHub reads them as.

        The documents write an address as ``\\<ajsinha@gmail.com\\>`` so GitHub does not take it
        for a tag; Python-Markdown does not treat ``<`` as escapable and printed the backslash.
        Code blocks are left alone -- there a backslash is the author's.
        """
        out, fenced = [], False
        for line in text.split("\n"):
            if line.lstrip().startswith(("```", "~~~")):
                fenced = not fenced
            elif not fenced and "\\<" in line or not fenced and "\\>" in line:
                line = line.replace("\\<", "&lt;").replace("\\>", "&gt;")
            out.append(line)
        return "\n".join(out)

    def _relink(self, html: str) -> str:
        """Points cross-references at the console rather than at files on disk."""
        def fix(match):
            prefix, target, suffix = match.groups()
            if target.startswith("#"):
                # GitHub keeps "--" where a heading had " & " or " — "; the toc extension collapses
                # it to one hyphen. Written for GitHub, read here: point at the id this page has.
                return prefix + "#" + self._GITHUB_RUN.sub("-", target[1:]) + suffix
            if target.startswith(("http://", "https://", "/", "mailto:")):
                return match.group(0)
            name = target.split("/")[-1]
            anchor = ""
            if "#" in name:
                name, anchor = name.split("#", 1)
                anchor = "#" + self._GITHUB_RUN.sub("-", anchor)
            topic = self._TOPIC.search(target.split("#", 1)[0])
            if topic:
                return prefix + "/help/topics/" + topic.group(1) + anchor + suffix
            route = self.ROUTES.get(name)
            if route:
                return prefix + route + anchor + suffix
            adr = self._ADR.match(name)
            if adr and ("adr/" in target or "/" not in target):
                return prefix + "/help/decisions/" + adr.group(1) + anchor + suffix
            # A relative link to something the console does not serve -- an ADR,
            # a source file, an example. Left as text rather than pointed at a
            # route that does not exist: a link that 404s is worse than one that
            # does not invite the click.
            return prefix + "#" + suffix
        return self._LINK.sub(fix, html)

    @classmethod
    def link_codes(cls, html: str) -> str:
        """Every ``PRV-nnnn`` in running text becomes a link to its page (design 23.4b).

        Not inside a link already, and not inside ``<pre>``: a code sample is copied, and a
        link in the middle of one is a surprise to whoever selects it.
        """
        out: list[str] = []
        in_link = in_pre = 0
        for part in cls._TAG.split(html):
            if part.startswith("<"):
                tag = part[1:].split(None, 1)[0].rstrip(">").lower() if len(part) > 2 else ""
                if tag == "a":
                    in_link += 1
                elif tag == "/a":
                    in_link = max(0, in_link - 1)
                elif tag == "pre":
                    in_pre += 1
                elif tag == "/pre":
                    in_pre = max(0, in_pre - 1)
                out.append(part)
            elif in_link or in_pre:
                out.append(part)
            else:
                out.append(cls._CODE.sub(lambda m: f'<a class="prv" href="/help/codes/{m.group(0)}">{m.group(0)}</a>', part))
        return "".join(out)

    def _headings(self, tokens) -> list[dict[str, Any]]:
        """Flatten the nested toc into a list a template can iterate."""
        out: list[dict[str, Any]] = []
        for t in tokens:
            # The toc extension hands the name back HTML-escaped; unescaped here so the template's
            # own escaping is the only one. (The template read a `text` key that never existed, so
            # every "On this page" link was empty -- found by the axe audit's link-name rule.)
            out.append({"id": t["id"], "name": html_text.unescape(t["name"]), "level": t["level"]})
            out.extend(self._headings(t.get("children", [])))
        return out
