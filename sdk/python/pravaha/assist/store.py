"""Where the assistant's configuration lives, and how a running process hears it changed.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

:class:`AssistConfigStore` is the protocol: ``load``, ``save`` (with the version the change was
made against, so two administrators cannot silently overwrite each other) and ``watch``.
:class:`FileConfigStore` is the implementation every process on one host can share: a JSON file,
written to a temporary file in the same directory with mode ``0600`` and renamed over the old one,
so a reader sees the old document or the new one and never half of either. ``watch`` polls the
file's modification time and size -- cheap, portable, and enough for a change an administrator
makes by hand or through the console.

The store holds no secret values: every document it reads or writes goes through
:func:`pravaha.assist.config.refuse_secrets` first.
"""

from __future__ import annotations

import json
import os
import pathlib
import tempfile
import threading
from typing import Any, Callable, Mapping, Optional, Protocol, runtime_checkable

from pravaha.assist.config import AssistConfig, refuse_secrets
from pravaha.assist.errors import AssistConfigError

#: Called with each new snapshot a watch sees.
Listener = Callable[[AssistConfig], None]
#: Called with a document a watch could not use; the old snapshot stays in force.
ErrorListener = Callable[[AssistConfigError], None]


class ConfigConflict(AssistConfigError):
    """A save made against a version that is no longer the stored one."""


def config_dir(environ: Optional[Mapping[str, str]] = None) -> pathlib.Path:
    """``$PRAVAHA_CONFIG_DIR``, else ``$XDG_CONFIG_HOME/pravaha``, else ``~/.config/pravaha`` --
    the directory the command line keeps its token in."""
    env = os.environ if environ is None else environ
    if env.get("PRAVAHA_CONFIG_DIR"):
        return pathlib.Path(env["PRAVAHA_CONFIG_DIR"])
    base = env.get("XDG_CONFIG_HOME")
    return (pathlib.Path(base) if base else pathlib.Path.home() / ".config") / "pravaha"


def default_config_path(environ: Optional[Mapping[str, str]] = None) -> pathlib.Path:
    """``$PRAVAHA_ASSIST_CONFIG``, else ``assist.json`` in :func:`config_dir`."""
    env = os.environ if environ is None else environ
    if env.get("PRAVAHA_ASSIST_CONFIG"):
        return pathlib.Path(env["PRAVAHA_ASSIST_CONFIG"]).expanduser()
    return config_dir(env) / "assist.json"


def _reject_other_formats(path: pathlib.Path) -> None:
    if path.suffix.lower() in (".yaml", ".yml", ".toml"):
        raise AssistConfigError(
            f"{path}: the assistant's configuration is JSON (see docs/guides/ASSIST.md -- YAML would need "
            f"a dependency the SDK does not have, and TOML cannot be written back by the standard "
            f"library). Write it as {path.with_suffix('.json').name}"
        )


def _no_duplicates(pairs: "list[tuple[str, Any]]") -> dict[str, Any]:
    seen: dict[str, Any] = {}
    for name, value in pairs:
        if name in seen:
            raise AssistConfigError(f"the field {name!r} appears twice in one object")
        seen[name] = value
    return seen


def parse_document(text: str, where: str = "the configuration") -> AssistConfig:
    """A JSON document as a statically validated snapshot."""
    try:
        document = json.loads(text, object_pairs_hook=_no_duplicates)
    except ValueError as exc:
        raise AssistConfigError(f"{where} is not valid JSON: {exc}") from None
    try:
        return AssistConfig.from_dict(document)
    except AssistConfigError as exc:
        raise AssistConfigError(f"{where}: {exc.message}") from None


@runtime_checkable
class AssistConfigStore(Protocol):
    """Somewhere the configuration is kept and shared."""

    def load(self) -> AssistConfig:
        """The stored snapshot; an empty one (version 0) when nothing is stored yet."""
        ...

    def save(self, config: AssistConfig, *, expected_version: int, by: Optional[str]) -> AssistConfig:
        """Stores ``config`` as version ``expected_version + 1``, stamped with ``by`` and now, or
        raises :class:`ConfigConflict` when the stored version is no longer ``expected_version``.
        Answers the snapshot as stored."""
        ...

    def watch(
        self,
        listener: Listener,
        *,
        interval_s: float = 1.0,
        on_error: Optional[ErrorListener] = None,
    ) -> "ConfigWatch":
        """Calls ``listener`` with every new snapshot another writer stores, until stopped."""
        ...


class ConfigWatch:
    """A polling watch on a store. :meth:`poll` checks once (tests call it directly);
    :meth:`start` runs it every ``interval_s`` on a daemon thread; :meth:`stop` ends it."""

    def __init__(
        self,
        changed: Callable[[], Optional[AssistConfig]],
        listener: Listener,
        *,
        interval_s: float = 1.0,
        on_error: Optional[ErrorListener] = None,
    ) -> None:
        self._changed = changed
        self._listener = listener
        self._on_error = on_error
        self._interval = max(0.05, float(interval_s))
        self._stopped = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def poll(self) -> Optional[AssistConfig]:
        """The new snapshot if the store changed since the last look (and tells the listener),
        else ``None``. A document that cannot be used goes to ``on_error``, not the listener."""
        try:
            fresh = self._changed()
        except AssistConfigError as exc:
            if self._on_error is not None:
                self._on_error(exc)
            return None
        if fresh is not None:
            self._listener(fresh)
        return fresh

    def start(self) -> "ConfigWatch":
        if self._thread is None:
            self._thread = threading.Thread(target=self._run, name="pravaha-assist-watch",
                                            daemon=True)
            self._thread.start()
        return self

    def _run(self) -> None:
        while not self._stopped.wait(self._interval):
            try:
                self.poll()
            except Exception:  # pragma: no cover - a listener's own failure must not end the watch
                pass

    def stop(self) -> None:
        self._stopped.set()
        if self._thread is not None:
            self._thread.join(timeout=5)


class FileConfigStore:
    """The configuration as one JSON file, shared by every process that names the same path."""

    def __init__(self, path: "str | os.PathLike[str] | None" = None) -> None:
        self.path = pathlib.Path(path) if path is not None else default_config_path()
        _reject_other_formats(self.path)
        self._lock = threading.Lock()

    def _stamp(self) -> "Optional[tuple[int, int, int]]":
        # The inode is in it because a save renames a new file into place: two saves inside the
        # file system's timestamp granularity, of the same size, still differ in their inode.
        try:
            info = self.path.stat()
        except FileNotFoundError:
            return None
        return (info.st_ino, info.st_mtime_ns, info.st_size)

    def load(self) -> AssistConfig:
        try:
            text = self.path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return AssistConfig()
        except OSError as exc:
            raise AssistConfigError(f"cannot read {self.path}: {exc.strerror or exc}") from None
        return parse_document(text, str(self.path))

    def save(self, config: AssistConfig, *, expected_version: int, by: Optional[str]) -> AssistConfig:
        with self._lock:
            current = self.load()
            if current.version != expected_version:
                raise ConfigConflict(
                    f"{self.path} is at version {current.version} (changed by "
                    f"{current.changed_by or 'someone'} at {current.changed_at or 'an unknown time'}), "
                    f"not {expected_version}: reload and make the change again"
                )
            stored = config.stamped(expected_version + 1, by).validated()
            document = stored.to_dict()
            refuse_secrets(document)
            self._write(json.dumps(document, indent=2, sort_keys=False) + "\n")
            return stored

    def _write(self, text: str) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(
            prefix="." + self.path.name + ".", suffix=".tmp", dir=str(self.path.parent)
        )
        try:
            # mkstemp creates the file 0600 already: there is no moment it is readable by others.
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                handle.write(text)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, self.path)
            os.chmod(self.path, 0o600)
        except BaseException:
            try:
                os.unlink(temporary)
            except OSError:
                pass
            raise

    def watch(
        self,
        listener: Listener,
        *,
        interval_s: float = 1.0,
        on_error: Optional[ErrorListener] = None,
    ) -> ConfigWatch:
        seen = [self._stamp()]

        def changed() -> Optional[AssistConfig]:
            stamp = self._stamp()
            if stamp == seen[0]:
                return None
            seen[0] = stamp
            return self.load()

        return ConfigWatch(changed, listener, interval_s=interval_s, on_error=on_error)


__all__ = [
    "AssistConfigStore",
    "ConfigConflict",
    "ConfigWatch",
    "FileConfigStore",
    "config_dir",
    "default_config_path",
    "parse_document",
]
