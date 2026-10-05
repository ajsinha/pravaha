# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
"""Render docs/guides/PYTHON_API_GUIDE.md as one self-contained page in the console's crimson identity.

    pravaha-console/.venv/bin/python tools/guide-page/render.py OUT.html tools/guide-page/template.html

The published page is generated, never edited: docs/guides/PYTHON_API_GUIDE.md is the source of truth, and
the console serves the same file at /help/python-api-guide.
"""
import html, posixpath, re, sys
import markdown

SRC = str(__import__("pathlib").Path(__file__).resolve().parents[2] / "docs" / "guides" / "PYTHON_API_GUIDE.md")
OUT = sys.argv[1]
REPO = "https://github.com/ajsinha/pravaha/blob/main/"

text = open(SRC, encoding="utf-8").read()
# The page carries its own header and contents rail; drop the document's title, copyright line and
# hand-written contents list, keep everything from the first part onwards.
intro_start = text.index("> **Who this is for.**")
intro_end = text.index("**Contents**")
intro = "\n".join(l[2:] if l.startswith("> ") else ("" if l.strip() == ">" else l) for l in text[intro_start:intro_end].strip().splitlines())
body = text[text.index("# Part I"):]

def relink(md):
    # Relative links to other repository files point at the repository; in-page anchors stay.
    def fix(m):
        label, target = m.group(1), m.group(2)
        if target.startswith(("#", "http")):
            return m.group(0)
        # Resolved from where the guide sits in the repository, docs/guides/.
        path = posixpath.normpath(posixpath.join("docs/guides", target))
        return f"[{label}]({REPO}{path})"
    return re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", fix, md)

md = markdown.Markdown(extensions=["tables", "fenced_code", "codehilite", "toc", "sane_lists"],
                       extension_configs={"codehilite": {"css_class": "hl", "guess_lang": False},
                                          "toc": {"toc_depth": "1-2"}})
content = md.convert(relink(body))
intro_html = markdown.markdown(relink(intro))
tokens = md.toc_tokens

# Tables scroll inside their own box, never the page.
content = content.replace("<table>", '<div class="table-wrap"><table>').replace("</table>", "</table></div>")

def rail():
    out = []
    for part in tokens:
        name = re.sub(r"^Part [IV]+ — ", "", part["name"])
        label = re.match(r"^(Part [IV]+)", part["name"])
        out.append('<div class="rail-group">')
        out.append(f'<p class="rail-part">{html.escape(label.group(1)) if label else ""}'
                   f'<span>{html.escape(name)}</span></p><ol>')
        for ch in part["children"]:
            m = re.match(r"^(\d+)\.\s+(.*)$", ch["name"])
            num, title = (m.group(1), m.group(2)) if m else ("", ch["name"])
            out.append(f'<li><a href="#{ch["id"]}"><span class="n">{num}</span>{html.escape(html.unescape(title))}</a></li>')
        out.append("</ol></div>")
    return "\n".join(out)

page = open(sys.argv[2], encoding="utf-8").read()
page = page.replace("@@RAIL@@", rail()).replace("@@INTRO@@", intro_html).replace("@@CONTENT@@", content)
open(OUT, "w", encoding="utf-8").write(page)
print("wrote", OUT, len(page), "bytes;", sum(len(p["children"]) for p in tokens), "chapters")
