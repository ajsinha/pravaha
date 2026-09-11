"""Serving the repository's documentation from the console.

An operator reading a console is already in the place where the question arose. Sending
them to a browser tab, a wiki, or a search engine to find out what a retention window is
loses the thread and usually loses the person -- so the documents are here, next to the
thing they explain.

They are rendered from the Markdown in ``docs/`` rather than duplicated. A copy would drift
from the source, and the whole point of the build checking those files is that they can be
trusted; a stale copy in a console would quietly undo that.
"""
from __future__ import annotations

import html
import pathlib
import re

# Walk up until the repository root: the console lives two levels below it, but an installed
# copy may not sit inside the repository at all, in which case there are simply no docs and
# the pages say so rather than failing.
_HERE = pathlib.Path(__file__).resolve()


def docs_root() -> pathlib.Path | None:
    for parent in _HERE.parents:
        candidate = parent / "docs"
        if (candidate / "README.md").exists() and (candidate / "adr").exists():
            return candidate
    return None


#: The pages worth offering, in the order somebody should meet them. Not every file in docs/:
#: the specification and the implementation plan are for whoever is building Pravaha, and an
#: operator looking for help does not need a three-thousand-line design document in the list.
PAGES: list[tuple[str, str, str]] = [
    ("QUICKSTART.md", "Quickstart", "Clone to a running continuous query, in ten minutes"),
    ("CONCEPTS.md", "Concepts", "The eight ideas everything follows from. Most surprises are one of these working correctly"),
    ("USER_GUIDE.md", "User guide", "The whole surface, task by task, in Java, Python and the shell"),
    ("SQL_SUPPORT.md", "What SQL it runs", "Every construct that works and every one that does not"),
    ("TROUBLESHOOTING.md", "Troubleshooting", "Every PRV- code, and the five you will actually meet"),
    ("OPERATIONS.md", "Operations", "Memory, disk, admission, what to watch"),
    ("SECURITY.md", "Security", "Authentication, authorization, row filters, audit"),
    ("ARCHITECTURE.md", "Architecture", "How it works inside, in two pages"),
]


#: The worked systems, which are the most practical documentation there is. They live under
#: examples/ rather than docs/, and are offered here because somebody deciding how to shape a
#: query wants an example far more often than a specification.
STUDIES: list[tuple[str, str, str]] = [
    ("trade-processing", "Trade processing", "A feed with no aggregation: many filtered subscribers on one computation"),
    ("banking-card-velocity", "Card velocity", "Tumbling windows, a temporal lookup join, filter pushdown"),
    ("finance-counterparty-exposure", "Counterparty exposure", "A relational source; money as minor units; value time versus insert time"),
    ("trading-order-flow", "Order flow surveillance", "Hopping windows, and what to do when you want CASE"),
    ("biology-sequencing-qc", "Sequencing QC", "The same engine on a domain with no money in it"),
]


def studies_root() -> pathlib.Path | None:
    root = docs_root()
    if root is None:
        return None
    candidate = root.parent / "examples" / "case-studies"
    return candidate if candidate.exists() else None


def available_studies() -> list[tuple[str, str, str]]:
    root = studies_root()
    if root is None:
        return []
    return [study for study in STUDIES if (root / study[0] / "README.md").exists()]


def load_study(name: str) -> str | None:
    """Reads one case study, from the allow-list only -- same control as :func:`load`."""
    if name not in {study[0] for study in STUDIES}:
        return None
    root = studies_root()
    if root is None:
        return None
    path = root / name / "README.md"
    return path.read_text(encoding="utf-8") if path.exists() else None


def available() -> list[tuple[str, str, str]]:
    root = docs_root()
    if root is None:
        return []
    return [page for page in PAGES if (root / page[0]).exists()]


def load(name: str) -> str | None:
    """Reads one documentation page, refusing anything that is not one of ours.

    The allow-list is the security control, not the path arithmetic. A console that
    accepted a name and joined it to a directory would serve ``../../etc/passwd`` to
    anybody who asked, and no amount of normalising afterwards is as reliable as never
    accepting the name in the first place.
    """
    if name not in {page[0] for page in PAGES}:
        return None
    root = docs_root()
    if root is None:
        return None
    path = root / name
    return path.read_text(encoding="utf-8") if path.exists() else None


def render_markdown(text: str) -> str:
    """Enough Markdown for these documents, and no dependency.

    Deliberately small: headings, code fences, tables, lists, blockquotes, links, inline
    code and emphasis. A full CommonMark implementation would be a dependency the console
    does not otherwise need, and these are documents we control.
    """
    out: list[str] = []
    in_code = False
    in_table = False
    in_list = False

    def close_blocks() -> None:
        nonlocal in_table, in_list
        if in_table:
            out.append("</table>")
            in_table = False
        if in_list:
            out.append("</ul>")
            in_list = False

    for raw in text.splitlines():
        if raw.startswith("```"):
            close_blocks()
            out.append("</pre>" if in_code else "<pre>")
            in_code = not in_code
            continue
        if in_code:
            out.append(html.escape(raw))
            continue

        line = raw.rstrip()
        if not line:
            close_blocks()
            continue

        # Tables: a row of pipes. The separator row is skipped rather than rendered.
        if line.startswith("|") and line.endswith("|"):
            cells = [cell.strip() for cell in line.strip("|").split("|")]
            if all(re.fullmatch(r":?-{2,}:?", cell or "-") for cell in cells):
                continue
            if not in_table:
                close_blocks()
                out.append("<table>")
                in_table = True
            tag = "td"
            out.append("<tr>" + "".join(f"<{tag}>{_inline(cell)}</{tag}>" for cell in cells) + "</tr>")
            continue
        if in_table:
            out.append("</table>")
            in_table = False

        heading = re.match(r"^(#{1,6})\s+(.*)$", line)
        if heading:
            close_blocks()
            level = min(len(heading.group(1)) + 1, 6)
            out.append(f"<h{level}>{_inline(heading.group(2))}</h{level}>")
            continue

        if line.startswith("> "):
            close_blocks()
            out.append(f"<blockquote>{_inline(line[2:])}</blockquote>")
            continue

        bullet = re.match(r"^\s*[-*]\s+(.*)$", line)
        if bullet:
            if not in_list:
                out.append("<ul>")
                in_list = True
            out.append(f"<li>{_inline(bullet.group(1))}</li>")
            continue
        if in_list:
            out.append("</ul>")
            in_list = False

        if re.fullmatch(r"-{3,}", line):
            out.append("<hr>")
            continue

        out.append(f"<p>{_inline(line)}</p>")

    close_blocks()
    if in_code:
        out.append("</pre>")
    return "\n".join(out)


def _inline(text: str) -> str:
    escaped = html.escape(text)
    escaped = re.sub(r"`([^`]+)`", r"<code>\1</code>", escaped)
    escaped = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", escaped)
    # Links to other documentation pages stay inside the console; anything else is left as
    # text, because a console that opened arbitrary URLs from a document would be a console
    # that can be pointed anywhere by editing a file.
    def link(match: re.Match) -> str:
        label, target = match.group(1), match.group(2)
        page = target.split("/")[-1]
        if page in {name for name, _, _ in PAGES}:
            return f'<a href="/help/{page}">{label}</a>'
        # A case study links to its neighbours by directory, e.g. ../trading-order-flow/
        study = target.strip("/").split("/")[-1]
        if study in {name for name, _, _ in STUDIES}:
            return f'<a href="/help/study/{study}">{label}</a>'
        return label

    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, escaped)
