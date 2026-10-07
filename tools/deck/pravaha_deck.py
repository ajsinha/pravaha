# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The one Pravaha deck, as data: the title slide and the executive summary here, and the
five acts in order in ``deck_part1`` to ``deck_part4``.

It tells its story the way a product showcase does: the category before the product,
then the product screen by screen, security drawn as flows, the evidence with a number
in every title, and where it is going. In order:

1. **The idea** -- continuous SQL and incremental view maintenance, before Pravaha is
   named: the problem, ten principles, what the field offers, the primitives, the
   architecture, how an application uses it, the benefits (``deck_part1``).
2. **Pravaha 2.4, the product** -- overview, what makes it different, the console
   screen by screen, the CLI, connectors, SDKs (``deck_part2``).
3. **Security as flows** -- sign-in, keys, the authorization check, the audit trail,
   identity, policies, transport (``deck_part3``).
4. **Evidence, by the numbers** and 5. **Where it is going** (``deck_part4``).

Every figure comes from the repository, and each slide's speaker notes begin
``Source:`` and name the file; ``tools/deck/FACTS.md`` lists every figure beside its
source. The talk track follows the source in the notes.

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

CHAPTER = "Pravaha • Ashutosh Sinha"
TITLE = "Pravaha — Continuous SQL where your data already lives"
SUBJECT = (
    "What continuous SQL is, what Pravaha 2.4 does with it on one node, how it is secured, "
    "what has been measured and found, and where it is going"
)

OPENING: list[dict[str, Any]] = [
    {
        "kind": "title",
        "kicker": "PRAVAHA · प्रवाह · CONTINUOUS, UNINTERRUPTED FLOW",
        "title": ["Pravaha", "Continuous SQL where your data already lives"],
        "sub": "Ask once. Answer always.",
        "date": "October 2026",
        "version": "Release 2.4 · one node · Java 21 or later, tested on 21 and 25",
        "agenda": [
            "The idea: continuous SQL",
            "Pravaha 2.4: the product",
            "Security, drawn as flows",
            "Evidence, by the numbers",
            "Where it is going",
        ],
        "source": "Source: README.md (name, slogan, Java 21 or later, tested on 21 and 25); brand/README.md "
        "(the slogan and 'Continuous SQL where your data already lives'); docs/project/RELEASE_NOTES.md "
        "'2.4.1 — 2026-10-06'; docs/operations/COMPATIBILITY.md (2.x is a one-node line).",
        "talk": "Pravaha is Sanskrit for continuous, uninterrupted flow. The promise is on the slide: you ask "
        "a question once, as SQL, and the engine keeps the answer current for as long as you need it — "
        "over the databases you already run. Five acts: the idea, the product, security, the evidence, "
        "and where it is going.",
    },
    {
        "kind": "qa",
        "kicker": "TL;DR",
        "title": "Executive summary",
        "rows": [
            ("What is continuous SQL?",
             "A SQL question registered once whose answer the engine keeps current as data changes — "
             "incremental view maintenance: each change is applied as a +1 or −1, never a recompute, "
             "so reading the answer is a lookup by key, not a scan."),
            ("What is Pravaha?",
             "An embeddable, store-native engine that runs continuous SQL over Kafka, PostgreSQL and "
             "MySQL change logs, any JDBC database, Aerospike, Cassandra, files and Delta tables — and "
             "serves each answer back itself, so it needs no second database."),
            ("Why does it matter?",
             "A batch answer is as old as its last run, and polling re-reads everything to stay fresh. "
             "A maintained answer is current when someone acts on it, and is governed where it lives."),
            ("What can it do today?",
             "Release 2.4 on one node, Java 21 or later: windows, joins, late-data corrections, "
             "exactly-once sinks, blue/green replacement, a governed catalogue, a console, a CLI and "
             "Java and Python SDKs. 582 findings recorded, 0 open."),
            ("Where is it going?",
             "Feature-complete for one node. Cluster mode is deferred; what is "
             "left is the eight-lane scaling target, more Nexmark SQL and the first versions' follow-ups."),
        ],
        "q_w": 2.9,
        "size": 14.5,
        "source": "Source: docs/guides/CONCEPTS.md §1 and §4 (computation not request; weights); README.md "
        "'What it is', 'Sources', 'What works', 'What is not built'; docs/project/RELEASE_NOTES.md "
        "'Unreleased' register line (582 findings — 563 fixed, 0 open); docs/development/REMAINING.md "
        "'What is genuinely left'; examples/case-studies/*/README.md (batch and polling).",
        "talk": "If you remember one slide, make it this one. Five questions, five short answers. The rest "
        "of the deck earns each answer: the idea first, because the category is less familiar than the "
        "product, then the product, then the evidence.",
    },
]

SLIDES = OPENING + PART1 + PART2 + PART3 + PART4
