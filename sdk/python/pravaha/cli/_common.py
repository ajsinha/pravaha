"""What every ``pravaha`` command shares: its context, its exit codes, and its usage error.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import argparse
import pathlib
from typing import TYPE_CHECKING, Any, Callable, Optional

from pravaha.api import EngineApi
from pravaha.cli._output import Output
from pravaha.cli._settings import Settings

if TYPE_CHECKING:
    from pravaha.client import Client

#: The command did what was asked.
EXIT_OK = 0
#: The engine refused: the PRV code and its message are on stderr.
EXIT_REFUSED = 1
#: The command line was wrong, or a setting was: nothing was sent.
EXIT_USAGE = 2
#: The engine could not be reached: nothing answered at the address.
EXIT_UNREACHABLE = 3
#: Interrupted (Ctrl-C) before the command finished: 128 + SIGINT, as a shell reports it.
#: ``subscribe`` is the exception: a subscription is ended by Ctrl-C, so it exits 0.
EXIT_INTERRUPTED = 130


class UsageError(Exception):
    """A mistake on the command line. Exit 2, and nothing was sent to the engine."""


class Context:
    """One invocation: its parsed arguments, how to reach the engine, and where to print."""

    def __init__(self, args: argparse.Namespace, out: Output, settings: Settings) -> None:
        self.args = args
        self.out = out
        self.settings = settings
        self._client: Optional["Client"] = None
        self._api: Optional[EngineApi] = None

    def arg(self, name: str, default: Any = None) -> Any:
        value = getattr(self.args, name, None)
        return default if value is None else value

    def require(self, name: str, flag: Optional[str] = None) -> Any:
        value = getattr(self.args, name, None)
        if value is None or (isinstance(value, str) and not value.strip()):
            raise UsageError(f"{flag or '--' + name.replace('_', '-')} is required")
        return value

    @property
    def api(self) -> EngineApi:
        if self._api is None:
            self._api = self.settings.api()
        return self._api

    @property
    def client(self) -> "Client":
        if self._client is None:
            self._client = self.settings.client()
        return self._client

    def close(self) -> None:
        if self._client is not None:
            try:
                self._client.close()
            except Exception:  # pragma: no cover - closing is best effort
                pass

    def sql(self) -> str:
        """``--sql``, or the contents of ``--sql-file``; one of them is required."""
        path = getattr(self.args, "sql_file", None)
        if path:
            try:
                return pathlib.Path(path).read_text(encoding="utf-8").strip()
            except OSError as exc:
                raise UsageError(f"cannot read {path}: {exc.strerror or exc}") from exc
        sql = getattr(self.args, "sql", None)
        if not sql or not sql.strip():
            raise UsageError("--sql or --sql-file is required")
        return str(sql)

    def confirmed(self) -> bool:
        return bool(getattr(self.args, "yes", False))


Command = Callable[[Context], int]


def ints(text: str, flag: str) -> "list[int]":
    """``0,2`` as ``[0, 2]``, or a usage error naming the flag."""
    try:
        return [int(part.strip()) for part in text.split(",") if part.strip()]
    except ValueError:
        raise UsageError(f"{flag} takes comma-separated numbers, got {text!r}") from None


def csv(text: Optional[str]) -> "list[str]":
    return [part.strip() for part in (text or "").split(",") if part.strip()]
