# Deck generator

Regenerates Pravaha's deck from source, so it is reproducible rather than a binary
nobody can edit safely.

```bash
# once: a local environment, because python-pptx is in none of the project's venvs
uv venv tools/deck/.venv --python 3.12
uv pip install -p tools/deck/.venv -r tools/deck/requirements.txt

tools/deck/.venv/bin/python tools/deck/build.py                   # the deck, into docs/publications/
tools/deck/.venv/bin/python tools/deck/audit.py docs/publications/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx
tools/deck/.venv/bin/python -m pytest -q tests/deck               # the audit, as a test
```

`tools/deck/.venv/` is ignored by git.

## One deck

| Deck | Slides | Source |
|---|---|---|
| `docs/publications/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx` | 56 | `pravaha_deck.py`, then `deck_part1.py` to `deck_part4.py` |

**Who it is for, and how it tells it.** It is the owner's showcase of Pravaha 2.4: for an architect,
a data-platform lead or an SRE meeting the product for the first time. It teaches the category before
it names the product, then shows the product as it is, draws security as flows, and ends on evidence
and the road ahead. After a title slide and a **TL;DR** — an executive summary as five questions
(what is continuous SQL, what is Pravaha, why it matters, what it does today, where it is going) —
come five acts, each opened by a short crimson divider with its two-digit number (01–05) as a tone-on-tone watermark:

1. **The idea: continuous SQL** (`deck_part1.py`) — what continuous SQL and incremental view
   maintenance are; the problem, in the case studies' own words; one engine instead of a job, a sink
   and a second store; **ten principles** in numbered cards (one per section of `CONCEPTS.md`); *what
   the field says* — the seven families of product from the competitive landscape, in place of an
   "industry voices" slide, because the repository cites no quotation and none is invented; the
   primitives as a table; an architecture diagram of how an application uses a maintained view;
   **how applications use Pravaha in five numbered steps**; one query; one correction; the benefits.
2. **Pravaha 2.4: the product** (`deck_part2.py`) — an overview of hard numbers; *why Pravaha* in six
   bold-lead bullets; three ways to run it; the console as a table of thirteen screens, then screen by
   screen from the documentation's real screenshots; the CLI (`doctor`, contexts, `--dry-run`, `top`,
   `init`, `plugin new`) with output captured from a real node; connectors; SDKs.
3. **Security, drawn as flows** (`deck_part3.py`) — the three seams, then each flow as a numbered step
   tree in a code panel under a band naming who talks to whom: console sign-in, an API key from
   creation to revocation, the authorization check on a read, the audit trail; then the credentials
   the engine keeps, row filters and masks as policies, and transport security.
4. **Evidence, by the numbers** (`deck_part4.py`) — one idea a slide, with a number in its title:
   291 error codes, JDK 21 against 25, the throughput gates, many queries on one node, Nexmark, the
   adversarial round and its ten severe defects, zero open findings out of 582, Java 21 → 25, the
   test tiers, the compatibility promise.
5. **Where it is going** (`deck_part4.py`) — what `REMAINING.md` says is left, where to start, and
   thank you.

Many slides end with a **key insight**: one bold sentence in a tinted band above the footer
(`takeaway` in a spec). Every content slide carries the footer "Pravaha • Ashutosh Sinha" and its
number.

**What it deliberately is not.** It names no other product's deck, organisation or marking: its
storytelling follows a reference deck from another product, and a test fails on any of `BMO`,
`ERPM`, `SAJHA`, `MCP` or `Confidential` anywhere in a slide, a table, the notes or the document
properties. It invents no quotation, customer or statistic. Performance figures appear only with
the machine and method they were taken with, and a target that was not reached is shown as not
reached.

**Where every claim comes from.** Each slide's speaker notes begin `Source:` and name the files, then
`Talk track:` and what the presenter says. A test fails if a slide has either missing.
[`FACTS.md`](FACTS.md) lists every figure on the slides beside its source file; when a figure moves in
its source, change it there and on the slide together.

## How it is put together

| File | Purpose |
|---|---|
| `metrics.py` | The text estimator: greedy word-wrap simulation and paragraph heights. Shared by the builder and the audit, so the builder never believes a box fits that the audit then reports |
| `theme.py` | The design system of the console (`pravaha-console/web/templates/base.html`) — crimson (`#A51C30`, `#8A1626`, `#6E1120`, tint `#F6E6E9`, ink `#1A1A1A`, canvas `#F7F5F2`) and Source Sans 3 for headings and text — plus the flow mark drawn as shapes, tables, cards, stat bars, code panels, numbered discs, the act divider, screenshots in a hairline frame (`picture()`, from `docs/assets/screenshots/`, which `pravaha-console/tools/docs_screenshots.py` regenerates), and `fitted()`, which shrinks a text block until it fits or raises `DoesNotFit` |
| `layouts.py` | Slide kinds drawn from plain dictionaries: `title`, `act` (the act divider), `qa` (the executive summary), `steps` (numbered principles or steps), `bullets`, `table`, `cards`, `stats`, `split`, `flow`, `context` (a diagram), `code`, `tree` (a security flow), `shot` and `shots` (screenshots), `thanks`; the older `divider` is kept. A spec's `source` and `talk` become the slide's speaker notes, and `takeaway` its key-insight band |
| `pravaha_deck.py`, `deck_part1.py` … `deck_part4.py` | The deck, as data: the title slide and the executive summary in `pravaha_deck.py`, the five acts in order in the four part modules (acts 4 and 5 share the last). Split only to keep each file short; they are one deck and are meant to be read in order |
| `FACTS.md` | Every figure on the slides beside the file it comes from |
| `build.py` | Builds the deck and sets the document properties (author, title, subject) explicitly |
| `audit.py` | The geometry audit (below) |
| `requirements.txt` | `python-pptx` and `pytest`, pinned |

**Fonts and colour.** The deck follows the console: crimson, and Source Sans 3, which is
not installed everywhere a deck is opened. The estimator therefore measures against the
wider and taller face a viewer is likely to see instead, so text that fits the estimate
fits with room under Source Sans. Code is set in Consolas: a proportional stand-in for a
missing monospaced face collapses a code block's indentation, and Office ships Consolas
on every platform it runs on. Crimson is the owner's choice (2026-09-28);
`brand/README.md` records it.

A slide that cannot be made to fit **fails the build** naming the slide; the fix is to
shorten the text or split the slide, never to lower the floor.

## The audit

`audit.py` re-derives the geometry of every shape on every slide and reports: a shape
off the slide; a table taller than its frame (PowerPoint treats a row height as a
minimum); an opaque shape drawn over earlier content; anything printed over a table;
text escaping a filled or outlined container, or the card its textbox sits in; content
crossing the footer rule; and a free textbox whose overflow lands on another shape.
`tests/deck/test_deck_geometry.py` runs it on the deck and asserts the slide count above, a source and
a talk track on every slide, the footer on every content slide, document properties that name the
author and no tool, and that no other product's or organisation's name or marking (`FOREIGN`) appears
anywhere in the deck.

`python-pptx` does not measure text, and PowerPoint does not clip overflow. The estimator
is deliberately pessimistic; it is an estimate, and a deck should still be looked at
after a large change:

```bash
soffice --headless --convert-to pdf --outdir /tmp/deck docs/publications/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx
pdftoppm -r 50 -png /tmp/deck/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pdf /tmp/deck/p
```

---

Copyright © 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See [LICENSE](../../LICENSE).
