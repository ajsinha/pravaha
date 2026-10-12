"""
Pravaha console — the examples gallery.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Drishti's examples (drishti-console ``core/examples.py``), for SQL: each example under
``content/examples`` is a triple -- ``<name>.sql`` (a continuous query, an alert or a snapshot read),
its sample data (``<name>.json``, or ``<name>.csv``) and a note, ``<name>.md``, whose first line is
the title. ``/help/examples`` lists them and the Workbench opens one as a copy
(``/workbench?example=<name>``), registering nothing. A name is served only if its files are
present in the directory when asked, so a request can never name a path.

pravaha-it's ``HelpExamplesSqlTest`` plans every ``.sql`` here against ``streams.properties`` (the
streams every help example is planned against), and ``tests/test_help_examples.py`` holds each
example's data to the columns of the streams it names, so neither half can rot.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

_NAME = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
#: The licence line every example opens with; the Workbench copy starts after it.
_LICENCE = re.compile(r"\A(?:--[^\n]*Copyright[^\n]*\n)+")


@dataclass(frozen=True)
class Example:
    name: str
    title: str
    sql: str
    data: str
    data_kind: str        # "json" | "csv"
    readme: str

    @property
    def workbench_sql(self) -> str:
        """The SQL as the Workbench opens it: without the licence line."""
        return _LICENCE.sub("", self.sql).lstrip("\n")


class Examples:
    """Reads the examples directory on demand (the files are small); safe for concurrent reads."""

    def __init__(self, directory: Path) -> None:
        self.directory = Path(directory)

    def _data(self, name: str) -> Path | None:
        for ext in ("json", "csv"):
            path = self.directory / f"{name}.{ext}"
            if path.is_file():
                return path
        return None

    def names(self) -> list[str]:
        """Every example with all three files, alphabetically."""
        if not self.directory.is_dir():
            return []
        return sorted(p.stem for p in self.directory.glob("*.sql")
                      if _NAME.match(p.stem) and (self.directory / f"{p.stem}.md").is_file()
                      and self._data(p.stem) is not None)

    def get(self, name: str | None) -> Example | None:
        """The named example, or None: names are checked against the files present, never joined
        into a path first."""
        if not name or not _NAME.match(name) or name not in self.names():
            return None
        readme = (self.directory / f"{name}.md").read_text(encoding="utf-8")
        title = next((ln[2:].strip() for ln in readme.splitlines() if ln.startswith("# ")), name)
        data = self._data(name)
        assert data is not None
        return Example(name, title, (self.directory / f"{name}.sql").read_text(encoding="utf-8"),
                       data.read_text(encoding="utf-8"), data.suffix[1:], readme)

    def all(self) -> list[Example]:
        return [e for e in (self.get(n) for n in self.names()) if e]
