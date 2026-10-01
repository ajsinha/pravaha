# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The one Pravaha deck, as data: the title slide and the 1.0.0 slide here, and the
twelve parts in order in ``deck_part1`` to ``deck_part4``.

It is written for the people who have to trust the engine's answers -- an
architect, an SRE, a data-platform lead -- and it answers their questions in the
order they ask them: why ask once and answer always, the vocabulary, a query's
life, correctness, scale on one node, changing a running query, answers built on
answers, connectors, security, identity and governance, operating it, thirteen
worked systems, and the evidence, what 1.0 promises and where to start.

Every figure comes from the repository -- the code, README.md, docs/**/*.md, the
ADRs in docs/design/adr, docs/project/RELEASE_NOTES.md, the gate packs and the case-study
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
        "date": "30 September 2026",
        "version": "Pravaha 1.0.0 · one node · Java 21",
        "agenda": [
            "Why ask once, answer always",
            "The vocabulary, from nothing",
            "A query's life",
            "Correctness: exactly once, retractions, late data",
            "Scale on one node",
            "Changing a running query",
            "Answers built on answers: chains and alerts",
            "Connectors",
            "Security, identity and governance",
            "Operating it",
            "Thirteen worked systems",
            "Evidence, 1.0, and where to start",
        ],
        "source": "Source: README.md (name, slogan, Java 21, release badge 1.0.0); brand/README.md (the "
        "name and slogan); docs/project/RELEASE_NOTES.md '1.0.0 — 2026-09-30'; docs/operations/COMPATIBILITY.md (one-node "
        "release).",
    },
    {
        "kind": "stats",
        "kicker": "Release 1.0.0 · 30 September 2026",
        "title": "The first release with a compatibility promise",
        "stats": [
            ("1.0.0", "Semantic versioning from here: 1.x adds, never breaks what is marked stable"),
            ("1 node", "Cluster mode (wave 11) is on hold and not in 1.0; a node refuses PARTITIONED"),
            ("0 open", "Findings in the register: 482 recorded, 464 fixed, 9 by design, 9 superseded"),
            ("60 ADRs", "Every decision recorded, including the ones later reversed"),
        ],
        "items": [
            ("New since 0.2.0",
             "Queries on queries, alerts that fire and clear, a governed catalogue with grants, row "
             "filters and masks, per-tenant names, ownership-based administration, BI tools over the "
             "PostgreSQL protocol, observability, one /opt/pravaha layout in and out of Docker, and "
             "SDKs that ship on their own."),
            ("Experimental in 1.0",
             "The assistant — plain English to continuous SQL through any model, with the engine as "
             "the judge — works and is tested, and may still change in a minor release."),
        ],
        "size": 15,
        "source": "Source: docs/project/RELEASE_NOTES.md '1.0.0 — 2026-09-30' (opening, upgrade notes, register "
        "line); docs/operations/COMPATIBILITY.md; docs/project/qa/FINDINGS.md (482 findings — 464 FIXED, 0 OPEN, 9 BY "
        "DESIGN, 9 SUPERSEDED); docs/development/HANDOVER.md §1 (ADRs 60; wave 11 on hold).",
    },
]

SLIDES = OPENING + PART1 + PART2 + PART3 + PART4
