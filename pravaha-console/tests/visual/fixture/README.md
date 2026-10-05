# Visual-regression content fixture

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see LICENSE at the repository root.

ABOUTBASE-1. A few photographed pages render prose the repository keeps changing: the release
notes, the case-study index and a study, the tutorials index, the "Getting started" topic. Their
baselines went stale on every prose edit, and a baseline that breaks on a sentence teaches people to
retake without looking.

`tests/test_browser_visual.py` therefore runs its console over a copy of the repository's content
with these files laid over it (`browser_harness.visual_content`). `tree/` mirrors the repository:
a file here replaces the file at the same path, and a directory named in `REPLACED` there replaces
the whole directory. Everything else -- every other topic, the guides, the codes -- is the real text,
so the help cards on each screen are the real ones.

The text here is fixed and says nothing true about the product; only its shape matters. Edit it only
to change what a photographed page is made to show, and retake that page's baselines after looking.
Every other test reads the real content.
