"""The curated model catalogue and the presets of the provider types after ``openai`` and ``anthropic``,
read from ``llm_catalog.json`` -- an identical copy of the engine's (``pravaha-ai``, ``ai/llm_catalog.json``);
a test checks they are equal. Prices in the file are USD per million tokens; the layer's own unit is per
1,000 tokens.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import functools
import json
import re
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional

_NAME_BAD = re.compile(r"[^A-Za-z0-9_.-]")
CREDENTIAL_SETTINGS = ("aws-access-key-id", "aws-secret-access-key", "aws-session-token", "client-secret")


@functools.lru_cache(maxsize=1)
def _data() -> Dict[str, Any]:
    data: Dict[str, Any] = json.loads((Path(__file__).with_name("llm_catalog.json")).read_text(encoding="utf-8"))
    return data


def presets() -> Dict[str, Any]:
    p: Dict[str, Any] = _data()["presets"]
    return p


def preset(type_name: Optional[str]) -> Optional[Dict[str, Any]]:
    p: Optional[Dict[str, Any]] = presets().get((type_name or "").lower())
    return p


def logical_name(model_id: str) -> str:
    """A model id as a logical name (letters, digits, ``.``, ``_``, ``-``; at most 64 characters)."""
    n = _NAME_BAD.sub("_", model_id)[:64]
    return n if n and n[0].isalnum() else "m" + n[:63]


def catalog_models(type_name: str) -> List[Dict[str, Any]]:
    out = []
    for e in _data()["catalog"].get((type_name or "").lower(), []):
        emb = e["kind"] == "embedding"
        caps = ("embeddings",) if emb else ("chat",) + (("tool-calls",) if e["tools"] else ()) + (("json-mode",) if e["json"] else ())
        out.append({"name": logical_name(e["id"]), "id": e["id"], "kind": e["kind"], "max_tokens": None, "temperature": None,
                    "capabilities": caps, "cost_per_1k_input": e["input-per-mtok"] / 1000,
                    "cost_per_1k_output": e["output-per-mtok"] / 1000})
    return out


def default_model(type_name: str, kind: str = "chat") -> str:
    d = _data()["defaults"].get((type_name or "").lower()) or {}
    return logical_name(d[kind]) if d.get(kind) else ""


def first_env(names: Optional[Iterable[str]], env: Callable[[str], Optional[str]]) -> str:
    for n in names or []:
        v = env(n)
        if v and v.strip():
            return v.strip()
    return ""


__all__ = ["CREDENTIAL_SETTINGS", "catalog_models", "default_model", "first_env", "logical_name", "preset", "presets"]
