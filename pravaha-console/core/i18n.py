"""The console's UI strings, looked up by key from a per-language catalog.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

One language today (``web/i18n/en.json``); the point is that a second is a file, not an
edit to every template. A template says ``{{ t('nav.catalog') }}``; an island reads the
``js.*`` keys the shell embeds as JSON and calls ``t()`` from ``lib.js``. Parameters are
named -- ``t('plugins.columns', n=4)`` against ``"{n} columns"`` -- because word order is the
first thing a translation changes, and positional arguments cannot follow it.

A missing key is an error in the tests and the key itself on a live page. A test that
renders every template with ``strict=True`` is what stops a typo from shipping as
``nav.catlog`` in a navigation bar.
"""
from __future__ import annotations

import json
import pathlib
import string
from typing import Any

CATALOG_DIR = pathlib.Path(__file__).resolve().parents[1] / "web" / "i18n"
DEFAULT_LANGUAGE = "en"


class MissingMessage(KeyError):
    """A key the catalog does not have, raised only in strict mode."""


class Messages:
    def __init__(self, language: str = DEFAULT_LANGUAGE, *, strict: bool = False,
                 directory: pathlib.Path = CATALOG_DIR) -> None:
        self.language = language
        self.strict = strict
        path = directory / f"{language}.json"
        if not path.exists():
            path = directory / f"{DEFAULT_LANGUAGE}.json"
        self.catalog: dict[str, str] = _flatten(json.loads(path.read_text(encoding="utf-8")))

    def __call__(self, key: str, **params: Any) -> str:
        template = self.catalog.get(key)
        if template is None:
            if self.strict:
                raise MissingMessage(key)
            return key
        if not params and "{" not in template:
            return template
        return template.format_map(_Params(params, key, self.strict))

    def for_script(self, prefix: str = "js.") -> dict[str, str]:
        """The keys an island needs, without the prefix, for embedding as JSON."""
        return {k[len(prefix):]: v for k, v in self.catalog.items() if k.startswith(prefix)}

    def placeholders(self, key: str) -> set[str]:
        return {name for _, name, _, _ in string.Formatter().parse(self.catalog[key]) if name}


class _Params(dict):
    def __init__(self, values: dict[str, Any], key: str, strict: bool) -> None:
        super().__init__(values)
        self._key, self._strict = key, strict

    def __missing__(self, name: str) -> str:
        if self._strict:
            raise MissingMessage(f"{self._key} needs the parameter '{name}'")
        return "{" + name + "}"


def _flatten(tree: dict, prefix: str = "") -> dict[str, str]:
    """Nested JSON objects to dotted keys: {"nav": {"catalog": "Catalog"}} -> nav.catalog."""
    flat: dict[str, str] = {}
    for name, value in tree.items():
        key = f"{prefix}{name}"
        if isinstance(value, dict):
            flat.update(_flatten(value, key + "."))
        else:
            flat[key] = str(value)
    return flat
