# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
Build the deck.

    tools/deck/.venv/bin/python tools/deck/build.py        # into docs/publications/
    tools/deck/.venv/bin/python tools/deck/audit.py \
        docs/publications/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx

The deck is a list of slide specs across ``pravaha_deck`` and its part modules;
``layouts`` draws them with the ``theme``. The document properties are set
explicitly: python-pptx's default template carries a comment naming the
library, and a deck's metadata should say who wrote it and nothing else.

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

import datetime as dt
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import layouts  # noqa: E402
import pravaha_deck  # noqa: E402
import theme  # noqa: E402

ROOT = HERE.parents[1]
DOCS = ROOT / "docs" / "publications"
NAME = "Pravaha-Continuous-SQL-Engine-Design-and-Evidence"
AUTHOR = "Ashutosh Sinha"


def build(out_dir: Path = DOCS) -> tuple[Path, int]:
    prs = theme.new_deck(pravaha_deck.CHAPTER)
    layouts.render(pravaha_deck.SLIDES)
    props = prs.core_properties
    props.title = pravaha_deck.TITLE
    props.subject = pravaha_deck.SUBJECT
    props.author = AUTHOR
    props.last_modified_by = AUTHOR
    props.comments = "Copyright (c) 2026 Ashutosh Sinha. All rights reserved. Proprietary and confidential."
    props.keywords = "Pravaha; continuous SQL; incremental view maintenance"
    props.category = ""
    props.revision = 1
    stamp = dt.datetime(2026, 10, 2, 12, 0, 0)
    props.created = stamp
    props.modified = stamp
    out = out_dir / f"{NAME}.pptx"
    prs.save(str(out))
    return out, len(prs.slides)


def main(argv: list[str]) -> int:
    out, n = build(Path(argv[1]) if len(argv) > 1 else DOCS)
    try:
        shown = out.relative_to(ROOT)
    except ValueError:
        shown = out
    print(f"{n:3d} slides -> {shown}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
