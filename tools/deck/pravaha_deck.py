# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The one Pravaha deck, as data: the title slide and the 2.x release slide here, and the
twelve parts in order in ``deck_part1`` to ``deck_part4``.

It is written for the people who have to trust the engine's answers -- an
architect, an SRE, a data-platform lead -- and it answers their questions in the
order they ask them: why ask once and answer always, the vocabulary, a query's
life, correctness, scale on one node, changing a running query, answers built on
answers, connectors, security, identity and governance, operating it, thirteen
worked systems, and the evidence, what 2.x promises and where to start.

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
        "date": "2 October 2026",
        "version": "Pravaha 2.x · one node · Java 21 or later",
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
            "Evidence, 2.x, and where to start",
        ],
        "source": "Source: README.md (name, slogan, Java 21 or later); brand/README.md (the name and slogan); "
        "docs/project/RELEASE_NOTES.md '2.0.0 — 2026-10-01' and '2.3.0 — 2026-10-04'; docs/operations/COMPATIBILITY.md "
        "(2.0 is a one-node release); docs/design/adr/062-java-21-or-later.md.",
    },
    {
        "kind": "stats",
        "kicker": "Release 2.0.0 · 1 October 2026 · Java 21 or later again from 2.3.0",
        "title": "Java 21 or later; everything else 1.x promised, kept",
        "stats": [
            ("Java 21+", "Every module, the API and the Java SDKs included, is Java 21 class files; tested on 21 and 25"),
            ("Boot 3.4+", "The Spring Boot starter: 3.4 and 3.5 are the tested lines"),
            ("2 breaks", "2.0's Java baseline (since relaxed), and legacy-read removed as 1.0 announced"),
            ("1 open", "553 findings: 533 fixed, 10 by design, 9 superseded"),
        ],
        "items": [
            ("Who it breaks",
             "2.0.0 asked embedders, plugins and Java SDK clients for Java 25; from 2.3.0 any JVM from 21 runs "
             "them, and launchers on an older JVM stop at once naming Java 21. A node still setting legacy-read refuses "
             "to start (PRV-7004): grant MODIFY or MANAGE instead."),
            ("What stays",
             "The SQL, the wire protocols, the HTTP API, the Python SDK, every other key and the state "
             "on disk. One engine image, on eclipse-temurin:21-jre, and no per-Java tag."),
            ("Since 2.0.0",
             "An adversarial QA round, 46 defects and 4 design notes, all fixed in three waves; some "
             "answers change, and the release notes say which."),
        ],
        "size": 14,
        "source": "Source: docs/project/RELEASE_NOTES.md '2.0.0 — 2026-10-01' (who it breaks, Docker, proved "
        "on 25) and 'Unreleased' (register line: 553 findings — 533 fixed, 1 open); "
        "docs/operations/COMPATIBILITY.md 'Java 21 or later'; docs/design/adr/062-java-21-or-later.md; "
        "docs/design/adr/061-jdk-25-is-the-baseline-from-2-0.md (Consequences: Boot 3.4 and 3.5 pass 41 tests on 25); docs/project/qa/FINDINGS.md header "
        "(10 BY DESIGN, 9 SUPERSEDED); docs/project/qa/SUMMARY.md 'Outcome'.",
    },
]

SLIDES = OPENING + PART1 + PART2 + PART3 + PART4
