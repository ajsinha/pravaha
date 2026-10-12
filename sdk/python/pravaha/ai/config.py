"""Reads the provider files (one provider per file) with the engine's rules: a bad file is reported and
skipped; a key, or any other secret, must be an ``${ENV_NAME}`` placeholder -- a literal is refused.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The SDK has no dependencies, so a file is read as JSON (``<name>.json``) always and as YAML
(``<name>.yaml``) when PyYAML is installed (the console has it); a YAML file without PyYAML is
reported, not half-read. ``*.yaml.example`` is never read.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

from pravaha.ai.providers import catalog
from pravaha.ai.types import CAPABILITIES, LlmError, ProviderConfig

Env = Callable[[str], Optional[str]]

_ENV = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?\}$")
_EMBEDDED = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?\}")
_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
_PROVIDER_KEYS = {"name", "type", "enabled", "base-url", "api-key", "timeout", "retries", "default-model", "models"}
_CREDENTIAL = re.compile(r"(?i).*(auth|key|token|secret|cookie).*")
_SECRETS = ("proxy.password", "tls.truststore.password", "tls.keystore.password")


def env_getter() -> Env:
    return os.environ.get


def _resolve(raw: str, env: Env) -> str:
    m = _ENV.match(raw.strip())
    if not m:
        return ""
    v = env(m.group(1))
    return v.strip() if v and v.strip() else (m.group(2) or "")


def resolve_key(raw: str, env: Env) -> str:
    if not raw:
        return ""
    if not _ENV.match(raw.strip()):
        raise LlmError("config", "api-key must be an ${ENV_NAME} placeholder; keys are never written into a file")
    return _resolve(raw, env)


def resolve_header(name: str, raw: str, env: Env) -> str:
    if "${" not in raw:
        if _CREDENTIAL.match(name) and raw.strip():
            raise LlmError("config", f"header {name} must use an ${{ENV_NAME}} placeholder; "
                           f"credentials are never written into a file")
        return raw
    return _EMBEDDED.sub(lambda m: (env(m.group(1)) or "").strip() or (m.group(2) or ""), raw)


def duration(v: Any, default: float) -> float:
    if v is None or str(v).strip() == "":
        return default
    t = str(v).strip().lower()
    try:
        if t.endswith("ms"):
            return int(t[:-2]) / 1000
        if t.endswith("m"):
            return int(t[:-1]) * 60
        return float(t[:-1] if t.endswith("s") else t)
    except ValueError:
        raise LlmError("config", f"timeout '{v}' is not a duration such as 30s") from None


def flatten(prefix: str, v: Any, out: Dict[str, str]) -> None:
    if isinstance(v, dict):
        for k, x in v.items():
            flatten(f"{prefix}.{k}", x, out)
    elif isinstance(v, list):
        for i, x in enumerate(v):
            flatten(f"{prefix}.{i}", x, out)
    elif v is not None:
        out[prefix] = str(v).lower() if isinstance(v, bool) else str(v)


def _read(path: Path) -> Dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    data: Any
    if path.suffix == ".json":
        try:
            data = json.loads(text)
        except ValueError:
            raise LlmError("config", "not readable JSON") from None
    else:
        try:
            import yaml  # type: ignore[import-untyped]
        except ImportError:
            raise LlmError("config", "reading YAML needs PyYAML (pip install pyyaml); or write the file as "
                           f"{path.stem}.json") from None
        try:
            data = yaml.safe_load(text)
        except yaml.YAMLError:
            raise LlmError("config", "not readable YAML") from None
    if not isinstance(data, dict):
        raise LlmError("config", "the file is empty or not a mapping")
    return data


def is_provider_file(p: Path) -> bool:
    return p.is_file() and p.suffix in (".yaml", ".yml", ".json") and not p.name.startswith(".")


def _files(directory: Union[Path, str, None]) -> List[Path]:
    d = Path(directory) if directory else None
    if d is None or not d.is_dir():
        return []
    return sorted(p for p in d.iterdir() if is_provider_file(p))


def parse_provider(path: Path, env: Env) -> ProviderConfig:
    return provider_from_dict(_read(path), path.stem, env)


def provider_from_dict(data: Dict[str, Any], default_name: str, env: Env) -> ProviderConfig:
    """One provider from its parsed block: a file's content, or what ``ProviderBase.from_settings`` is given."""
    name = str(data.get("name") or default_name).strip()
    if not _NAME.match(name):
        raise LlmError("config", f"name '{name}' must be letters, digits, '.', '_' or '-'")
    typ = str(data.get("type") or "").strip()
    if not typ:
        raise LlmError("config", "type is missing (openai, anthropic, server, mock, ...)")
    settings: Dict[str, str] = {}
    for k, v in data.items():
        if k not in _PROVIDER_KEYS:
            flatten(k, v, settings)
    for k in [k for k in settings if k.startswith("header.")]:
        settings[k] = resolve_header(k[7:], settings[k], env)
    models: List[Dict[str, Any]] = []
    seen = set()
    for m in data.get("models") or []:
        m = m or {}
        mn = str(m.get("name") or "").strip()
        if not _NAME.match(mn):
            raise LlmError("config", "a model needs a logical name (letters, digits, '.', '_', '-')")
        if mn in seen:
            raise LlmError("config", f"model '{mn}' is listed twice")
        seen.add(mn)
        caps = tuple(str(c) for c in m.get("capabilities") or [])
        for c in caps:
            if c not in CAPABILITIES:
                raise LlmError("config", f"model '{mn}': unknown capability '{c}'")
        models.append({"name": mn, "id": str(m.get("id") or mn), "kind": str(m.get("kind") or "chat").lower(),
                       "max_tokens": m.get("max-tokens"), "temperature": m.get("temperature"), "capabilities": caps,
                       "cost_per_1k_input": float(m.get("cost-per-1k-input") or 0),
                       "cost_per_1k_output": float(m.get("cost-per-1k-output") or 0)})
    base_url = str(data.get("base-url") or "").strip()
    api_key = resolve_key(str(data.get("api-key") or ""), env)
    default_model = str(data.get("default-model") or "").strip()
    for k in (*catalog.CREDENTIAL_SETTINGS, *_SECRETS):
        raw = settings.get(k, "")
        if raw:
            if not _ENV.match(raw.strip()):
                raise LlmError("config", f"{k} must be an ${{ENV_NAME}} placeholder; secrets are never written into a file")
            settings[k] = _resolve(raw, env)
    pre = catalog.preset(typ)
    if pre:
        base_url, api_key, models, default_model = _preset_defaults(typ, pre, settings, models, base_url, api_key,
                                                                    default_model, env)
    return ProviderConfig(name=name, type=typ, enabled=bool(data.get("enabled", True)), base_url=base_url,
                          api_key=api_key, timeout=duration(data.get("timeout"), 60.0),
                          retries=int(data.get("retries") or 0), default_model=default_model, settings=settings,
                          models=tuple(models))


def _preset_defaults(typ: str, pre: Dict[str, Any], settings: Dict[str, str], models: List[Dict[str, Any]],
                     base_url: str, api_key: str, default_model: str,
                     env: Env) -> Tuple[str, str, List[Dict[str, Any]], str]:
    """What a preset type fills in when the file leaves it out: the base URL (or ``base-url-env``), the key
    from the vendor's variable, the catalogue models (a file model of the same name wins; ``catalogue: false``
    keeps only the file's), settings from the vendor's environment, and the default model."""
    base_url = base_url or catalog.first_env(pre.get("base-url-env"), env) or str(pre.get("base-url") or "")
    api_key = api_key or catalog.first_env(pre.get("key-env"), env)
    for k, names in (pre.get("settings-env") or {}).items():
        if not settings.get(k) and catalog.first_env(names, env):
            settings[k] = catalog.first_env(names, env)
    have = {m["name"] for m in models}
    if settings.get("catalogue", "true").lower() != "false":
        models = list(models) + [m for m in catalog.catalog_models(typ) if m["name"] not in have]
    if typ == "azure_openai":  # deployments.<model id>: <deployment name> are this resource's models
        have = {m["name"] for m in models}
        for k in sorted(k for k in settings if k.startswith("deployments.")):
            mid = k[len("deployments."):]
            if catalog.logical_name(mid) not in have:
                emb = "embed" in mid
                models.append({"name": catalog.logical_name(mid), "id": mid, "kind": "embedding" if emb else "chat",
                               "max_tokens": None, "temperature": None,
                               "capabilities": ("embeddings",) if emb else ("chat", "tool-calls", "json-mode"),
                               "cost_per_1k_input": 0.0, "cost_per_1k_output": 0.0})
    default_model = default_model or catalog.default_model(typ)
    if default_model and default_model not in {m["name"] for m in models}:
        default_model = ""
    return base_url, api_key, models, default_model


def load_providers(directory: Union[Path, str, None],
                   env: Optional[Env] = None) -> Tuple[List[ProviderConfig], Dict[str, str]]:
    """Every provider file in ``directory``: ``(configs, file name -> why it could not be used)``."""
    getenv = env or env_getter()
    ok: List[ProviderConfig] = []
    errors: Dict[str, str] = {}
    seen = set()
    for f in _files(directory):
        try:
            c = parse_provider(f, getenv)
        except LlmError as e:
            errors[f.name] = e.message
            continue
        if c.name in seen:
            errors[f.name] = f"another file already defines provider '{c.name}'"
        else:
            seen.add(c.name)
            ok.append(c)
    return ok, errors


def fingerprint(directory: Union[Path, str, None]) -> Tuple[Tuple[str, int, int], ...]:
    """Names, sizes and modification times of the provider files: what a reload would read."""
    try:
        return tuple((f.name, f.stat().st_size, f.stat().st_mtime_ns) for f in _files(directory))
    except OSError:
        return (("unreadable", -1, 0),)


__all__ = ["duration", "env_getter", "fingerprint", "flatten", "is_provider_file", "load_providers",
           "parse_provider", "provider_from_dict", "resolve_header", "resolve_key"]
