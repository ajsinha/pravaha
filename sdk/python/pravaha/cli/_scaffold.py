"""What ``pravaha init`` and ``pravaha plugin new`` share: a list of files, each with the reason it
is there, written into a directory that is empty unless ``--force`` says otherwise.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Nothing here contacts a node or reads the user's configuration. ``--dry-run`` lists the files and
writes none; ``--json`` prints the same list as an object. A directory that holds anything is
refused (exit 2) without ``--force``, because a scaffold written over someone's work is a loss
nobody asked for; with ``--force`` the scaffold's own files are overwritten and nothing else is
touched.
"""

from __future__ import annotations

import pathlib
import re
from dataclasses import dataclass, field
from typing import Any

from pravaha.cli._common import EXIT_OK, Context, UsageError


@dataclass(frozen=True)
class File:
    """One file of a scaffold: where it goes (relative), what it holds, and why it is there."""

    path: str
    text: str
    why: str


@dataclass
class Scaffold:
    """A scaffold to write: its files, and what to say after."""

    what: str
    files: "list[File]" = field(default_factory=list)
    #: Commands to run next, in order.
    next: "list[str]" = field(default_factory=list)
    #: Remarks printed after the list: the choices the scaffold made, and how to make another.
    choices: "list[str]" = field(default_factory=list)
    extra: "dict[str, Any]" = field(default_factory=dict)

    def add(self, path: str, text: str, why: str) -> None:
        self.files.append(File(path, text, why))


def slug(text: str) -> str:
    """``My Store`` as ``my-store``: lower case, words joined by hyphens."""
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def emit(ctx: Context, target: pathlib.Path, scaffold: Scaffold) -> int:
    """Refuses a directory with something in it (unless ``--force``), writes the files (unless
    ``--dry-run``), and says what was made and why."""
    dry = bool(ctx.arg("scaffold_dry_run", False))
    force = bool(ctx.arg("force", False))
    if target.exists() and not target.is_dir():
        raise UsageError(f"{target} exists and is not a directory")
    if target.is_dir() and any(target.iterdir()) and not force:
        raise UsageError(f"{target} is not empty; choose an empty directory, or pass --force to "
                         "write the scaffold's files over any of the same name (nothing else is touched)")
    if not dry:
        for item in scaffold.files:
            path = target / item.path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(item.text, encoding="utf-8")
    out = ctx.out
    if out.json_mode:
        out.json({
            "directory": str(target),
            "dryRun": dry,
            "files": [{"path": f.path, "why": f.why} for f in scaffold.files],
            "next": scaffold.next,
            **scaffold.extra,
        })
        return EXIT_OK
    verb = "would create" if dry else "created"
    out.line(out.bold(f"{verb} {scaffold.what} in {target}/"))
    width = max(len(f.path) for f in scaffold.files)
    for item in scaffold.files:
        out.line(f"  {item.path.ljust(width)}  {out.dim(item.why)}")
    if scaffold.choices:
        out.line()
        out.line(out.bold("choices made for you:"))
        for choice in scaffold.choices:
            out.line(f"  - {choice}")
    if scaffold.next:
        out.line()
        out.line(out.bold("next:"))
        for command in scaffold.next:
            out.line(f"  {command}")
    if dry:
        out.line()
        out.line("--dry-run: nothing was written")
    return EXIT_OK
