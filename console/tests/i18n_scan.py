"""Finds user-visible English that bypasses the UI string catalog.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

A heuristic, and deliberately a narrow one: it must be quiet on the code as it is, so that
the first hard-coded sentence somebody adds is the thing it reports. What it reads:

- **islands and classic scripts** (``web/static/app/*.js``, ``web/static/js/*.js``), with
  comments removed: the text between tags in an ``html`...` `` template and its
  ``title``, ``aria-label``, ``placeholder`` and ``alt`` attributes; and every other string
  literal that reads as prose -- two or more words, at least one of them not a CSS class the
  console's stylesheets define (so ``"btn btn-sm btn-primary"`` is markup, and
  ``"the editor did not load"`` is English) -- or as a capitalised word, which is how a label
  is spelled and a status value is not;
- **templates** (``web/templates/*.html``), with Jinja tags and ``<script>``/``<style>``
  bodies removed: text between tags, and the same four attributes.

What it allows is in :data:`ALLOWED`, reviewed: codes, units, product and file names, key
names the browser reports, SQL keywords. A string that is not text for people -- an event
name, a MIME type -- is allowed there too rather than by weakening the rule.
"""
from __future__ import annotations

import pathlib
import re

CONSOLE = pathlib.Path(__file__).resolve().parents[1]
STATIC = CONSOLE / "web" / "static"
TEMPLATES = CONSOLE / "web" / "templates"

# Reviewed: none of these is a sentence a translation would change.
ALLOWED: frozenset[str] = frozenset({
    # the product, formats and file names
    "Pravaha", "Pravaha console", "SQL", "CSV", "SVG", "JSON", "PNG", "ELK", "Monaco",
    "ECharts", "Preact", "pravaha-result.csv", "use strict",
    # key names the browser reports, HTTP methods, SQL keywords and types
    "ArrowRight", "ArrowLeft", "ArrowUp", "ArrowDown", "Home", "End", "Enter", "Escape",
    "Delete", "Backspace", "Tab", "PageUp", "PageDown", "GET", "POST", "DELETE", "PUT",
    "SELECT", "FROM", "WHERE", "GROUP", "BY", "TIMESTAMP", "NOT NULL",
    # glyphs and units
    "×", "·", "—", "…", "ms", "s", "Z-set",
    # the author, and the word the product is named for (the landing page's wordmark)
    "Ashutosh Sinha", "ajsinha@gmail.com", "pravāha",
    # example identifiers and values in an input's placeholder: what to type, in any language
    "txn", "PT10S", "user_id, window_end", "hourly_spend",
    # a Monaco font style, not words
    "bold underline",
})

# Codes and identifiers: a PRV code, an ADR number, SQL.
_CODE = re.compile(r"(PRV-\d{4}|ADR-\S*|(SELECT|CREATE|DROP|PAUSE|RESUME|SHOW)\s.*)")

_ATTRS = ("title", "aria-label", "placeholder", "alt")
_WORD = re.compile(r"[A-Za-z][A-Za-z’']+")


def _css_classes() -> set[str]:
    names: set[str] = set()
    sheets = [*STATIC.glob("app/*.css"), *STATIC.glob("vendor/bootstrap/css/*.min.css"),
              *STATIC.glob("vendor/bootstrap-icons/*.css"), TEMPLATES / "base.html"]
    for sheet in sheets:
        names |= set(re.findall(r"\.(-?[A-Za-z_][\w-]*)", sheet.read_text(encoding="utf-8")))
    return names


def _strip_js_comments(text: str) -> str:
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "`":
            _, j = _template_body(text, i + 1)
            out.append(text[i:j + 1]); i = j + 1
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1]); i = j + 1
        elif text.startswith("//", i) and (i == 0 or text[i - 1] not in ":\\"):
            j = text.find("\n", i); i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2); j = n if j < 0 else j + 2
            out.append("\n" * text.count("\n", i, j)); i = j
        elif c == "/" and _regex_can_start(out):
            j = i + 1
            while j < n and text[j] not in "/\n":
                if text[j] == "[":
                    while j < n and text[j] != "]":
                        j += 2 if text[j] == "\\" else 1
                j += 2 if text[j] == "\\" else 1
            out.append(" "); i = j + 1
        else:
            out.append(c); i += 1
    return "".join(out)


def _regex_can_start(out: list[str]) -> bool:
    before = "".join(out[-40:]).rstrip()
    return not before or before[-1] in "(,=:[!&|?{};+" or before.endswith("return")


def _literals(code: str):
    """(kind, body, offset) for every string literal; kind is the quote, or 'html' for htm."""
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if c in "\"'":
            j = i + 1
            while j < n and code[j] != c and code[j] != "\n":
                j += 2 if code[j] == "\\" else 1
            yield c, code[i + 1:j], i
            i = j + 1
        elif c == "`":
            tagged = code[max(0, i - 4):i] == "html"
            body, j = _template_body(code, i + 1)
            yield ("html" if tagged else "`"), body, i
            i = j + 1
        else:
            i += 1


def _template_body(code: str, start: int) -> tuple[str, int]:
    """A template literal's text with each ${...} replaced by \x00, and nested literals scanned."""
    out, j, n = [], start, len(code)
    while j < n and code[j] != "`":
        if code[j] == "\\":
            out.append(code[j:j + 2]); j += 2
        elif code.startswith("${", j):
            depth, k = 1, j + 2
            while k < n and depth:
                if code[k] in "\"'":
                    q = code[k]; k += 1
                    while k < n and code[k] != q:
                        k += 2 if code[k] == "\\" else 1
                elif code[k] == "`":
                    _, k = _template_body(code, k + 1)
                elif code[k] == "{":
                    depth += 1
                elif code[k] == "}":
                    depth -= 1
                k += 1
            out.append("\x00"); j = k
        else:
            out.append(code[j]); j += 1
    return "".join(out), j


def _expressions(code: str, start: int) -> list[str]:
    """The ${...} expressions of the template literal opening at ``start`` (the backtick)."""
    exprs, j, n = [], start + 1, len(code)
    while j < n and code[j] != "`":
        if code[j] == "\\":
            j += 2
        elif code.startswith("${", j):
            depth, k = 1, j + 2
            while k < n and depth:
                if code[k] in "\"'":
                    q = code[k]; k += 1
                    while k < n and code[k] != q:
                        k += 2 if code[k] == "\\" else 1
                elif code[k] == "`":
                    _, k = _template_body(code, k + 1)
                elif code[k] == "{":
                    depth += 1
                elif code[k] == "}":
                    depth -= 1
                k += 1
            exprs.append(code[j + 2:k - 1]); j = k
        else:
            j += 1
    return exprs


def _is_text(value: str, classes: set[str]) -> bool:
    """Whether a fragment reads as words for people rather than markup, a key or a value."""
    value = value.replace("\x00", " ").strip()
    if not value or value in ALLOWED or _CODE.fullmatch(value.replace("\x00", "…")):
        return False
    if re.search(r"&[a-z]+;|&#\d+;", value) and not _WORD.search(re.sub(r"&#?\w+;", "", value)):
        return False
    words = _WORD.findall(value)
    if not words:
        return False
    rest = [w for w in words if w not in ALLOWED]
    return bool(rest)


def _is_prose(value: str, classes: set[str]) -> bool:
    """A plain JS string: prose, or a capitalised label. Class lists, keys and paths are not."""
    v = value.replace("\x00", " ").strip()
    if not v or v in ALLOWED or v.startswith(("/", "#", "--", "http", "pravaha.", "js.", "<")):
        return False
    if re.fullmatch(r"[\w.\-/:?=&%\[\]{}()*,$@!^+|<>~\\ ]*", v) and "{" in v:
        return False
    tokens = v.split()
    if len(tokens) >= 2:
        if all(t in classes or "-" in t or not t.isalpha() for t in tokens):
            return False
        words = [t for t in tokens if re.fullmatch(r"[A-Za-z’',.;:!?()—–]+", t)]
        return len(words) >= 2 and any(re.fullmatch(r"[a-z’']+[,.;:!?)]*", t) for t in tokens)
    word = tokens[0]
    return bool(re.fullmatch(r"[A-Z][a-z]+[a-z’']*[.…:]?|[A-Z][a-z]+ ?…", word))


def _text_nodes(markup: str):
    """Text between tags, and the visible attributes, of a stretch of HTML."""
    # Code, keys and literal text are what they say in every language, and so is an element
    # marked translate="no" (HTML's own word for it) -- the component gallery's sample data.
    markup = re.sub(r"(?s)<(code|kbd|pre|samp)\b[^>]*>.*?</\1>", "<x></x>", markup)
    markup = re.sub(r'(?s)<(\w+)\b[^>]*\btranslate="no"[^>]*>.*?</\1>', "<x></x>", markup)
    for m in re.finditer(r">([^<>]*)<", markup):
        yield m.group(1)
    for attr in _ATTRS:
        for m in re.finditer(rf'\s{attr}="([^"]*)"', markup):
            yield m.group(1)


def scan_script_text(name: str, text: str, classes: set[str]) -> list[str]:
    """Findings, as ``name:line: text``, in one island or classic script."""
    code = _strip_js_comments(text)
    found: list[str] = []
    _scan_code(name, code, code, 0, classes, found)
    return found


def _scan_code(name: str, whole: str, code: str, base: int, classes: set[str],
               found: list[str]) -> None:
    for kind, body, offset in _literals(code):
        if kind in "`html":
            for expr in _expressions(code, offset):
                start = code.find(expr, offset)
                _scan_code(name, whole, expr, base + max(start, offset), classes, found)
        # Everything inside t("...") is the catalog's; the key is not English.
        before = code[max(0, offset - 3):offset]
        if kind in "\"'" and before.endswith(("t(", "t( ")):
            continue
        if kind == "html":
            for piece in _text_nodes(">" + body + "<"):
                if _is_text(piece, classes):
                    found.append(_where(name, whole, base + offset, piece))
            continue
        if _is_prose(body, classes):
            found.append(_where(name, whole, base + offset, body))


def _where(name: str, code: str, offset: int, piece: str) -> str:
    line = code.count("\n", 0, offset) + 1
    return f"{name}:{line}: {' '.join(piece.replace(chr(0), '…').split())[:90]}"


def scan_template_text(name: str, text: str, classes: set[str]) -> list[str]:
    """Findings in one Jinja template; the line is approximate once tags are removed."""
    text = re.sub(r"(?s)\{#.*?#\}", "", text)
    text = re.sub(r"(?s)<(script|style)\b[^>]*>.*?</\1>", "<x></x>", text)
    text = re.sub(r"(?s)\{\{.*?\}\}", "\x00", text)
    text = re.sub(r"(?s)\{%.*?%\}", "", text)
    found = []
    for piece in _text_nodes(text):
        if _is_text(piece, classes):
            found.append(_where(name, text, text.find(piece), piece).replace(":", ":~", 1))
    return found


def scan() -> list[str]:
    """Every finding in the console's islands, classic scripts and templates."""
    classes = _css_classes()
    found: list[str] = []
    for path in sorted([*STATIC.glob("app/*.js"), *STATIC.glob("js/*.js")]):
        found += scan_script_text(str(path.relative_to(CONSOLE)), path.read_text(encoding="utf-8"), classes)
    for path in sorted(TEMPLATES.glob("*.html")):
        found += scan_template_text(str(path.relative_to(CONSOLE)), path.read_text(encoding="utf-8"), classes)
    return found


if __name__ == "__main__":
    for line in scan():
        print(line)
