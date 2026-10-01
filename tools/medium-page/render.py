# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
"""Render docs/publications/medium/pravaha-medium-post.md as one self-contained page in the console's crimson identity.

    console/.venv/bin/python tools/medium-page/render.py            # docs/publications/medium/pravaha-medium-post.html
    console/.venv/bin/python tools/medium-page/render.py OUT.html

Every diagram is inlined as a data URI -- the SVG, which is the source (docs/publications/medium/README.md) -- so the
page is one file that can be sent, attached or opened offline. The page is generated, never edited:
the markdown is the source of truth. Needs the `markdown` package, which the console's venv has.
"""
import base64
import html
import pathlib
import re
import sys

import markdown

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "docs" / "publications" / "medium" / "pravaha-medium-post.md"
IMAGES = SRC.parent / "images"
TEMPLATE = pathlib.Path(__file__).resolve().parent / "template.html"
OUT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else SRC.with_suffix(".html")
REPO = "https://github.com/ajsinha/pravaha/blob/main/"

text = SRC.read_text(encoding="utf-8")

# The page carries its own masthead: take the title (#), the subtitle (###) and the byline out of the body.
lines = text.splitlines()
title = lines[0].lstrip("# ").strip()
subtitle = next(l[4:].strip() for l in lines if l.startswith("### "))
byline = next(l.strip("* ") for l in lines if l.startswith("*By "))
body = text[text.index("---") + 3:]


def relink(md):
    """Relative links to repository files point at the repository; in-page anchors stay."""
    def fix(m):
        label, target = m.group(1), m.group(2)
        if target.startswith(("#", "http", "images/")):
            return m.group(0)
        return f"[{label}]({REPO}docs/publications/medium/{target})"
    return re.sub(r"(?<!!)\[([^\]]+)\]\(([^)\s]+)\)", fix, md)


content = markdown.markdown(relink(body), extensions=["tables", "fenced_code", "sane_lists"])


def inline(m):
    """An image and the italic line under it become a figure; the SVG is inlined as a data URI."""
    alt, src, caption = html.unescape(m.group(1)), m.group(2), m.group(3)
    svg = IMAGES / pathlib.Path(src).with_suffix(".svg").name
    data = base64.b64encode(svg.read_bytes()).decode("ascii")
    return (f'<figure><img src="data:image/svg+xml;base64,{data}" alt="{html.escape(alt, quote=True)}" '
            f'width="800" loading="lazy"><figcaption>{caption}</figcaption></figure>')


content, figures = re.subn(r'<p><img alt="([^"]*)" src="(images/[^"]+)" />\s*<em>(.*?)</em></p>', inline,
                           content, flags=re.S)
if 'src="images/' in content:
    sys.exit("an image was not inlined: every image needs its italic caption on the next line")

# Tables scroll inside their own box, never the page.
content = content.replace("<table>", '<div class="table-wrap"><table>').replace("</table>", "</table></div>")

page = TEMPLATE.read_text(encoding="utf-8")
page = (page.replace("@@TITLE@@", html.escape(title)).replace("@@SUBTITLE@@", html.escape(subtitle))
        .replace("@@BYLINE@@", html.escape(byline)).replace("@@CONTENT@@", content))
OUT.write_text(page, encoding="utf-8")
print(f"wrote {OUT.relative_to(ROOT) if OUT.is_relative_to(ROOT) else OUT}: {len(page):,} bytes, "
      f"{figures} figures inlined")
