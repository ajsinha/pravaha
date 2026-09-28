# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The one Pravaha deck, as data: the title slide here, and the eleven parts in
order in ``deck_part1`` to ``deck_part4``.

It is written for the people who have to trust the engine's answers -- an
architect, an SRE, a data-platform lead -- and it answers their questions in the
order they ask them: why ask once and answer always, the vocabulary, a query's
life, correctness, scale on one node, changing a running query, connectors,
security and identity, operating it, thirteen worked systems, and what is
measured, what is not built and where to start.

Every figure comes from the repository -- the code, README.md, docs/*.md, the
ADRs in docs/adr, docs/RELEASE_NOTES.md, the gate packs and the case-study
READMEs -- and each slide's speaker notes name the file.

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

from deck_part1 import SLIDES as PART1
from deck_part2 import SLIDES as PART2
from deck_part3 import SLIDES as PART3
from deck_part4 import SLIDES as PART4

CHAPTER = "Pravaha · Continuous SQL"
TITLE = "Pravaha — A Continuous SQL Engine: Design and Evidence"
SUBJECT = (
    "What a continuous query is, how Pravaha keeps its answer correct and current on one node, "
    "and what has and has not been measured"
)

OPENING: list[dict[str, Any]] = [
    {
        "kind": "title",
        "kicker": "PRAVAHA · प्रवाह · CONTINUOUS, UNINTERRUPTED FLOW",
        "title": ["A continuous SQL engine:", "design and evidence"],
        "sub": "Ask once. Answer always.",
        "date": "September 2026",
        "version": "Pravaha 0.2.0 (2026-09-27) and later · Java 21",
        "agenda": [
            "Why ask once, answer always",
            "The vocabulary, from nothing",
            "A query's life",
            "Correctness: exactly once, retractions, late data",
            "Scale on one node",
            "Changing a running query",
            "Connectors",
            "Security and identity",
            "Operating it",
            "Thirteen worked systems",
            "What is measured, what is not built, where to start",
        ],
        "source": "Source: README.md (name, slogan, Java 21); brand/README.md (the name and slogan); "
        "docs/RELEASE_NOTES.md '0.2.0 — QA, 2026-09-27' and 'Unreleased'.",
    },
]

SLIDES = OPENING + PART1 + PART2 + PART3 + PART4
