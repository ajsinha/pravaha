# Deck generator

Regenerates Pravaha's deck from source, so it is reproducible rather than a binary
nobody can edit safely.

```bash
# once: a local environment, because python-pptx is in none of the project's venvs
uv venv tools/deck/.venv --python 3.12
uv pip install -p tools/deck/.venv -r tools/deck/requirements.txt

tools/deck/.venv/bin/python tools/deck/build.py                   # the deck, into docs/
tools/deck/.venv/bin/python tools/deck/audit.py docs/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx
tools/deck/.venv/bin/python -m pytest -q tests/deck               # the audit, as a test
```

`tools/deck/.venv/` is ignored by git.

## One deck

| Deck | Slides | Source |
|---|---|---|
| `docs/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx` | 91 | `pravaha_deck.py`, then `deck_part1.py` to `deck_part4.py` |

**Who it is for.** The people who have to trust the engine's answers: an architect
deciding whether it belongs in a design, an SRE who will be paged for it, a
data-platform lead who will be asked why a number moved. It describes release 1.0.0
(one node; cluster mode, wave 11, on hold). After the title and a slide on what 1.0.0
is, it answers their questions in the order they ask them, in twelve parts:

1. **Why ask once, answer always** — what a batch answer costs and what polling
   re-reads and misses, in the case studies' own words; the inversion; one query end
   to end.
2. **The vocabulary, from nothing** — stream, source, binding, lookup, continuous
   query, view, event time, watermark, weight, commit, lane, checkpoint, sink — no word
   used before it is defined.
3. **A query's life** — `CREATE CONTINUOUS QUERY`, what registration does, what it
   refuses, reading, subscribing.
4. **Correctness** — one checkpoint as one cut (ADR-008), output cut at the same
   marker, recovery, weights and retractions, late data, survival, bounds.
5. **Scale on one node** — lanes, threads following cores, the auto sharing mode,
   dedicated lanes, admin rebalance, exact-seam reader sharing (ADR-054), the equality
   index (ADR-055).
6. **Changing a running query** — blue/green replacement meeting the running
   version at a position (ADR-046).
7. **Answers built on answers** — queries on queries (ADR-056), the consumed answer as
   the seam, alerts that fire and clear (ADR-057), and their guarantees.
8. **Connectors** — only what is in `plugins/`: nine sources, two lookups, six sinks,
   CDC without Debezium.
9. **Security, identity and governance** — ADR-031, the catalogue's grants, row
   filters and masks as policies, vacuity (ADR-059), ADR-052, tenancy and ownership
   (ADR-050, ADR-060), and what is not built.
10. **Operating it** — deployment and one `/opt/pravaha` in and out of Docker, the
    console, `pravaha` and `pravaha-engine`, SDK reconnect, SDKs on their own, BI tools
    over the PostgreSQL protocol, metrics and observability, the assistant
    (experimental), the debugger.
11. **Thirteen worked systems** — the case studies, how the build checks them, four
    in depth and two joins.
12. **Evidence, 1.0, and where to start** — the gates, Nexmark and micro-benchmarks,
    the test tiers, what testing found, what 1.x promises, what is not built.

**What it deliberately is not.** It carries no implementation-status register. It has
one slide from the findings file, and that slide is about the method — what each
defect was found by — not a list of what is open. Performance figures appear only with
the machine they were taken on and the gate they were measured against, and a target
that was not reached is shown as not reached.

**Where every claim comes from.** Each slide's speaker notes begin `Source:` and name
the files — `README.md`, `docs/*.md`, the ADRs in `docs/adr/`, `docs/RELEASE_NOTES.md`,
the gate packs in `docs/gates/`, `benchmarks/README.md`, and the case-study READMEs. A
test fails if a slide has no source. Case-study numbers are quoted from their READMEs,
where they are either the output of a real run or answers the build checks
(`CaseStudyRunTest`).

## How it is put together

| File | Purpose |
|---|---|
| `metrics.py` | The text estimator: greedy word-wrap simulation and paragraph heights. Shared by the builder and the audit, so the builder never believes a box fits that the audit then reports |
| `theme.py` | The design system of the console (`console/web/templates/base.html`) — crimson (`#A51C30`, `#8A1626`, `#6E1120`, tint `#F6E6E9`, ink `#1A1A1A`, canvas `#F7F5F2`) and Source Sans 3 for headings and text — plus the flow mark drawn as shapes, tables, cards, stat bars, code panels, and `fitted()`, which shrinks a text block until it fits or raises `DoesNotFit` |
| `layouts.py` | Slide kinds drawn from plain dictionaries: `title`, `divider`, `bullets`, `table`, `cards`, `stats`, `split`, `flow`, `code`, `context`. A spec's `source` becomes the slide's speaker notes |
| `pravaha_deck.py`, `deck_part1.py` … `deck_part4.py` | The deck, as data: the title slide and the 1.0.0 slide in `pravaha_deck.py`, the twelve parts in order in the four part modules. Split only to keep each file short; they are one deck and are meant to be read in order |
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
`tests/deck/test_deck_geometry.py` runs it on the deck and asserts the slide count
above, a source on every slide, and document properties that name the author and no
tool.

`python-pptx` does not measure text, and PowerPoint does not clip overflow. The estimator
is deliberately pessimistic; it is an estimate, and a deck should still be looked at
after a large change:

```bash
soffice --headless --convert-to pdf --outdir /tmp/deck docs/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx
pdftoppm -r 50 -png /tmp/deck/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pdf /tmp/deck/p
```

---

Copyright © 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See [LICENSE](../../LICENSE).
