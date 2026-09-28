"""The assistant's configuration: an immutable, validated snapshot of providers, models,
profiles, budgets and which models are enabled.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

**The format is JSON.** It is written by programs (the console's administration screen, and
``pravaha assist use|enable|disable``) as well as by people, so it has to round-trip exactly, and
the one format the standard library both reads and writes on every Python this SDK supports is
JSON. YAML would need PyYAML, which the SDK does not depend on; TOML's reader (``tomllib``) is
3.11+ and it has no writer at all. A ``.yaml``/``.yml``/``.toml`` file is refused with directions
rather than half-read. ::

    {
      "default_profile": "explain",
      "providers": [
        {"id": "anthropic", "type": "anthropic", "api_key_env": "ANTHROPIC_API_KEY"},
        {"id": "local", "type": "ollama", "endpoint": "http://localhost:11434"}
      ],
      "models": [
        {"id": "claude-main", "provider": "anthropic", "model": "claude-opus-5"},
        {"id": "local-llama", "provider": "local", "model": "llama3.1:70b"}
      ],
      "profiles": {"explain": ["local-llama", "claude-main"], "draft": ["claude-main"]},
      "budgets": {"per_user_daily_tokens": 200000, "per_request_max_tokens": 8000}
    }

**No secret in configuration.** A key is named by ``api_key_env`` (an environment variable) or
``api_key_file`` (a file only its owner can read), never written in the file: a field called
``api_key``, ``token``, ``secret``, ``password``... or a value shaped like a key (``sk-...``) is
refused, whoever wrote it.

Validation is in two steps. :meth:`AssistConfig.from_dict` checks what the document itself can
say -- shapes, unique ids, providers that exist, chains that name enabled models.
:meth:`AssistConfig.problems` with an environment adds what only a running process knows: that
each enabled model's key variable is set, its secret file readable. The router applies a snapshot
only when both pass.
"""

from __future__ import annotations

import dataclasses
import datetime
import os
import pathlib
import re
import stat
import types
from typing import Any, Iterable, Mapping, Optional, Sequence

from pravaha.assist.errors import AssistConfigError

#: Field names that mean "the secret itself is here". Compared lower-cased without ``-``/``_``.
_SECRET_NAMES = frozenset(
    {
        "apikey",
        "key",
        "token",
        "accesstoken",
        "secret",
        "secretkey",
        "clientsecret",
        "privatekey",
        "password",
        "passwd",
        "authorization",
        "bearer",
        "credentials",
    }
)
#: Values shaped like a provider key or a bearer header.
_SECRET_VALUE = re.compile(
    r"^(sk-|sk_|rk-|xai-|gsk_|hf_|ghp_|github_pat_|xox[abp]-|AIza|AKIA|ASIA|Bearer\s)"
)

_TOP = frozenset(
    {"version", "changed_at", "changed_by", "default_profile", "providers", "models", "profiles",
     "budgets", "debug", "$comment"}
)
_PROVIDER_FIELDS = frozenset(
    {"id", "type", "endpoint", "api_key_env", "api_key_file", "timeout_s", "options"}
)
_MODEL_FIELDS = frozenset({"id", "provider", "model", "enabled", "timeout_s", "options"})
_BUDGET_FIELDS = frozenset({"per_user_daily_tokens", "per_request_max_tokens"})
_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")


def _normalised(name: str) -> str:
    return name.lower().replace("_", "").replace("-", "")


def refuse_secrets(document: Any, path: str = "$") -> None:
    """Raises :class:`AssistConfigError` if ``document`` holds anything that looks like a key."""
    if isinstance(document, Mapping):
        for name, value in document.items():
            where = f"{path}.{name}"
            if isinstance(name, str) and _normalised(name) in _SECRET_NAMES:
                raise AssistConfigError(
                    f"{where}: configuration never holds a secret. Name the environment variable "
                    f"that holds it with api_key_env, or a file only you can read with api_key_file"
                )
            refuse_secrets(value, where)
    elif isinstance(document, (list, tuple)):
        for i, value in enumerate(document):
            refuse_secrets(value, f"{path}[{i}]")
    elif isinstance(document, str) and _SECRET_VALUE.match(document.strip()):
        raise AssistConfigError(
            f"{path}: this value looks like a key, and configuration never holds a secret. Put it "
            f"in an environment variable named by api_key_env, or a file named by api_key_file"
        )


def _freeze(value: Any) -> Any:
    if isinstance(value, Mapping):
        return types.MappingProxyType({str(k): _freeze(v) for k, v in value.items()})
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(v) for v in value)
    return value


def _thaw(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {k: _thaw(v) for k, v in value.items()}
    if isinstance(value, tuple):
        return [_thaw(v) for v in value]
    return value


def _empty() -> Mapping[str, Any]:
    return types.MappingProxyType({})


@dataclasses.dataclass(frozen=True)
class ProviderConfig:
    """One configured provider: which implementation (``type``), where, and how its key is
    found. Several may share a type -- two OpenAI-compatible gateways, two Anthropic accounts."""

    id: str
    type: str
    endpoint: Optional[str] = None
    api_key_env: Optional[str] = None
    api_key_file: Optional[str] = None
    timeout_s: float = 60.0
    options: Mapping[str, Any] = dataclasses.field(default_factory=_empty)

    def key_problem(self, environ: Mapping[str, str]) -> Optional[str]:
        """Why this provider's key cannot be read in ``environ``, or ``None``."""
        if self.api_key_env and not environ.get(self.api_key_env):
            return (
                f"provider {self.id!r}: environment variable {self.api_key_env} (api_key_env) is "
                f"not set in this process"
            )
        if self.api_key_file:
            path = pathlib.Path(self.api_key_file).expanduser()
            try:
                mode = path.stat().st_mode
                path.read_text(encoding="utf-8")
            except OSError as exc:
                return f"provider {self.id!r}: cannot read api_key_file {path}: {exc.strerror or exc}"
            if os.name == "posix" and mode & (stat.S_IRWXG | stat.S_IRWXO):
                return (
                    f"provider {self.id!r}: api_key_file {path} may be read by others; "
                    f"chmod 600 it"
                )
        return None

    def resolve_key(self, environ: Mapping[str, str]) -> Optional[str]:
        """The key, read now from the variable or the file; ``None`` when none is configured."""
        problem = self.key_problem(environ)
        if problem:
            raise AssistConfigError(problem)
        if self.api_key_env:
            return environ[self.api_key_env].strip()
        if self.api_key_file:
            text = pathlib.Path(self.api_key_file).expanduser().read_text(encoding="utf-8")
            return text.strip() or None
        return None

    def to_dict(self) -> dict[str, Any]:
        body: dict[str, Any] = {"id": self.id, "type": self.type}
        for name in ("endpoint", "api_key_env", "api_key_file"):
            if getattr(self, name):
                body[name] = getattr(self, name)
        if self.timeout_s != 60.0:
            body["timeout_s"] = self.timeout_s
        if self.options:
            body["options"] = _thaw(self.options)
        return body


@dataclasses.dataclass(frozen=True)
class ModelConfig:
    """One model the assistant may use: its id in chains, its provider, the provider's own model
    name, whether it is enabled, and options (capability overrides, a fake's script...)."""

    id: str
    provider: str
    model: str
    enabled: bool = True
    timeout_s: Optional[float] = None
    options: Mapping[str, Any] = dataclasses.field(default_factory=_empty)

    def to_dict(self) -> dict[str, Any]:
        body: dict[str, Any] = {"id": self.id, "provider": self.provider, "model": self.model}
        if not self.enabled:
            body["enabled"] = False
        if self.timeout_s is not None:
            body["timeout_s"] = self.timeout_s
        if self.options:
            body["options"] = _thaw(self.options)
        return body


@dataclasses.dataclass(frozen=True)
class Budgets:
    """Token budgets. ``None`` is no limit."""

    per_user_daily_tokens: Optional[int] = None
    per_request_max_tokens: Optional[int] = None

    def to_dict(self) -> dict[str, Any]:
        return {k: v for k, v in dataclasses.asdict(self).items() if v is not None}


@dataclasses.dataclass(frozen=True)
class AssistConfig:
    """One immutable snapshot. Change it with :func:`dataclasses.replace` (or through
    :class:`pravaha.assist.admin.AssistAdmin`) and have it validated again."""

    providers: "tuple[ProviderConfig, ...]" = ()
    models: "tuple[ModelConfig, ...]" = ()
    profiles: Mapping[str, "tuple[str, ...]"] = dataclasses.field(default_factory=_empty)
    default_profile: Optional[str] = None
    budgets: Budgets = Budgets()
    debug: bool = False
    #: Incremented by every save; 0 for a configuration never saved by a store.
    version: int = 0
    changed_at: Optional[str] = None
    changed_by: Optional[str] = None

    # ------------------------------------------------------------------ lookups

    def provider(self, provider_id: str) -> ProviderConfig:
        for entry in self.providers:
            if entry.id == provider_id:
                return entry
        raise AssistConfigError(f"no provider {provider_id!r} is configured")

    def model(self, model_id: str) -> ModelConfig:
        for entry in self.models:
            if entry.id == model_id:
                return entry
        known = ", ".join(m.id for m in self.models) or "none"
        raise AssistConfigError(f"no model {model_id!r} is configured (configured: {known})")

    def chain(self, profile: Optional[str] = None) -> "tuple[str, ...]":
        """The fallback chain for ``profile``: its own, else the default profile's."""
        if profile and profile in self.profiles:
            return self.profiles[profile]
        if self.default_profile and self.default_profile in self.profiles:
            return self.profiles[self.default_profile]
        if not self.models:
            raise AssistConfigError(
                "no model is configured for the assistant; see docs/ASSIST.md for the file "
                "(~/.config/pravaha/assist.json, or $PRAVAHA_ASSIST_CONFIG)"
            )
        raise AssistConfigError(
            f"no profile {profile!r} and no default_profile: set one with "
            f"`pravaha assist use {profile or 'default'} <model-id>` or in the file"
        )

    def profiles_of(self, model_id: str) -> "list[tuple[str, int]]":
        """``(profile, position)`` for every chain naming ``model_id``, position from 1."""
        return [
            (name, chain.index(model_id) + 1)
            for name, chain in sorted(self.profiles.items())
            if model_id in chain
        ]

    # ------------------------------------------------------------------ validation

    def problems(
        self, environ: Optional[Mapping[str, str]] = None, *, prebuilt: Iterable[str] = ()
    ) -> "list[str]":
        """Everything wrong with this snapshot; with ``environ``, also each enabled model's key
        (except the ``prebuilt`` models, whose provider the caller built itself)."""
        skip = set(prebuilt)
        from pravaha.assist.registry import load_factory, provider_names

        found: list[str] = []
        names = set(provider_names())
        provider_ids: set[str] = set()
        for entry in self.providers:
            if not _ID.match(entry.id):
                found.append(f"provider id {entry.id!r} is not a valid id (letters, digits, ._:-)")
            if entry.id in provider_ids:
                found.append(f"provider id {entry.id!r} is used twice")
            provider_ids.add(entry.id)
            if entry.type not in names:
                found.append(
                    f"provider {entry.id!r}: no provider type {entry.type!r} "
                    f"(known: {', '.join(sorted(names))})"
                )
            if entry.api_key_env and entry.api_key_file:
                found.append(f"provider {entry.id!r}: api_key_env or api_key_file, not both")
            if entry.timeout_s <= 0:
                found.append(f"provider {entry.id!r}: timeout_s must be positive")
        model_ids: set[str] = set()
        enabled: set[str] = set()
        for model in self.models:
            if not _ID.match(model.id):
                found.append(f"model id {model.id!r} is not a valid id (letters, digits, ._:-)")
            if model.id in model_ids:
                found.append(f"model id {model.id!r} is used twice")
            model_ids.add(model.id)
            if model.provider not in provider_ids:
                found.append(f"model {model.id!r}: no provider {model.provider!r} is configured")
            if not model.model:
                found.append(f"model {model.id!r}: names no model")
            if model.enabled:
                enabled.add(model.id)
        for name, chain in sorted(self.profiles.items()):
            if not chain:
                found.append(f"profile {name!r}: an empty chain")
            if len(set(chain)) != len(chain):
                found.append(f"profile {name!r}: names a model twice")
            for model_id in chain:
                if model_id not in model_ids:
                    found.append(f"profile {name!r}: no model {model_id!r} is configured")
                elif model_id not in enabled:
                    found.append(f"profile {name!r}: model {model_id!r} is disabled")
        if self.default_profile is not None and self.default_profile not in self.profiles:
            found.append(f"default_profile {self.default_profile!r} is not a profile")
        for label, value in dataclasses.asdict(self.budgets).items():
            if value is not None and (not isinstance(value, int) or value <= 0):
                found.append(f"budgets.{label} must be a positive whole number of tokens")
        if environ is not None and not found:
            for model in self.models:
                if not model.enabled or model.id in skip:
                    continue
                provider = self.provider(model.provider)
                problem = provider.key_problem(environ)
                if problem is None and not (provider.api_key_env or provider.api_key_file):
                    try:
                        needs = bool(getattr(load_factory(provider.type), "requires_key", False))
                    except AssistConfigError as exc:
                        problem = str(exc)
                    else:
                        if needs:
                            problem = (
                                f"provider {provider.id!r} ({provider.type}) needs a key: set "
                                f"api_key_env or api_key_file"
                            )
                if problem and f"model {model.id!r}: {problem}" not in found:
                    found.append(f"model {model.id!r}: {problem}")
        return found

    def validated(self, environ: Optional[Mapping[str, str]] = None) -> "AssistConfig":
        """This snapshot, or :class:`AssistConfigError` listing every problem."""
        found = self.problems(environ)
        if found:
            raise AssistConfigError("the assistant configuration is not valid: " + "; ".join(found))
        return self

    # ------------------------------------------------------------------ JSON

    def to_dict(self) -> dict[str, Any]:
        body: dict[str, Any] = {"version": self.version}
        if self.changed_at:
            body["changed_at"] = self.changed_at
        if self.changed_by:
            body["changed_by"] = self.changed_by
        if self.default_profile:
            body["default_profile"] = self.default_profile
        body["providers"] = [p.to_dict() for p in self.providers]
        body["models"] = [m.to_dict() for m in self.models]
        body["profiles"] = {k: list(v) for k, v in sorted(self.profiles.items())}
        budgets = self.budgets.to_dict()
        if budgets:
            body["budgets"] = budgets
        if self.debug:
            body["debug"] = True
        return body

    @classmethod
    def from_dict(cls, document: Mapping[str, Any]) -> "AssistConfig":
        """Parses and statically validates a document. Refuses secrets and unknown fields."""
        if not isinstance(document, Mapping):
            raise AssistConfigError("the assistant configuration must be a JSON object")
        refuse_secrets(document)
        _only(document, _TOP, "the configuration")
        providers = tuple(_provider(p, i) for i, p in enumerate(_list(document, "providers")))
        models = tuple(_model(m, i) for i, m in enumerate(_list(document, "models")))
        raw_profiles = document.get("profiles") or {}
        if not isinstance(raw_profiles, Mapping):
            raise AssistConfigError("profiles must be an object of profile -> [model ids]")
        profiles: dict[str, tuple[str, ...]] = {}
        for name, chain in raw_profiles.items():
            if isinstance(chain, Mapping) and "chain" in chain:
                chain = chain["chain"]  # ADR-058's own shape, {"chain": [...]}
            if not isinstance(chain, list) or not all(isinstance(c, str) for c in chain):
                raise AssistConfigError(f"profiles.{name} must be a list of model ids")
            profiles[str(name)] = tuple(chain)
        raw_budgets = document.get("budgets") or {}
        if not isinstance(raw_budgets, Mapping):
            raise AssistConfigError("budgets must be an object")
        _only(raw_budgets, _BUDGET_FIELDS, "budgets")
        config = cls(
            providers=providers,
            models=models,
            profiles=types.MappingProxyType(profiles),
            default_profile=_text(document, "default_profile", "the configuration"),
            budgets=Budgets(
                raw_budgets.get("per_user_daily_tokens"), raw_budgets.get("per_request_max_tokens")
            ),
            debug=bool(document.get("debug", False)),
            version=int(document.get("version") or 0),
            changed_at=_text(document, "changed_at", "the configuration"),
            changed_by=_text(document, "changed_by", "the configuration"),
        )
        return config.validated()

    # ------------------------------------------------------------------ building a change

    def replace(self, **changes: Any) -> "AssistConfig":
        """A copy with ``changes``; lists and dicts are frozen. Not validated -- that is the
        caller's next step."""
        if "providers" in changes:
            changes["providers"] = tuple(changes["providers"])
        if "models" in changes:
            changes["models"] = tuple(changes["models"])
        if "profiles" in changes:
            changes["profiles"] = types.MappingProxyType(
                {k: tuple(v) for k, v in changes["profiles"].items()}
            )
        return dataclasses.replace(self, **changes)

    def stamped(self, version: int, by: Optional[str]) -> "AssistConfig":
        now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
        return dataclasses.replace(
            self, version=version, changed_at=now.isoformat().replace("+00:00", "Z"), changed_by=by
        )


# ---------------------------------------------------------------------------------- parsing


def _only(entry: Mapping[str, Any], allowed: Iterable[str], where: str) -> None:
    unknown = sorted(set(entry) - set(allowed))
    if unknown:
        raise AssistConfigError(
            f"{where}: unknown field{'s' if len(unknown) > 1 else ''} {', '.join(unknown)} "
            f"(allowed: {', '.join(sorted(allowed))})"
        )


def _list(document: Mapping[str, Any], name: str) -> Sequence[Any]:
    value = document.get(name) or []
    if not isinstance(value, list):
        raise AssistConfigError(f"{name} must be a list of objects, each with an id")
    return value


def _text(entry: Mapping[str, Any], name: str, where: str) -> Optional[str]:
    value = entry.get(name)
    if value is None:
        return None
    if not isinstance(value, str):
        raise AssistConfigError(f"{where}: {name} must be text")
    return value


def _number(entry: Mapping[str, Any], name: str, where: str) -> Optional[float]:
    value = entry.get(name)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise AssistConfigError(f"{where}: {name} must be a number")
    return float(value)


def _provider(entry: Any, index: int) -> ProviderConfig:
    where = f"providers[{index}]"
    if not isinstance(entry, Mapping):
        raise AssistConfigError(f"{where} must be an object")
    _only(entry, _PROVIDER_FIELDS, where)
    options = entry.get("options") or {}
    if not isinstance(options, Mapping):
        raise AssistConfigError(f"{where}.options must be an object")
    return ProviderConfig(
        id=str(entry.get("id") or ""),
        type=str(entry.get("type") or entry.get("id") or ""),
        endpoint=_text(entry, "endpoint", where),
        api_key_env=_text(entry, "api_key_env", where),
        api_key_file=_text(entry, "api_key_file", where),
        timeout_s=_number(entry, "timeout_s", where) or 60.0,
        options=_freeze(options),
    )


def _model(entry: Any, index: int) -> ModelConfig:
    where = f"models[{index}]"
    if not isinstance(entry, Mapping):
        raise AssistConfigError(f"{where} must be an object")
    _only(entry, _MODEL_FIELDS, where)
    options = entry.get("options") or {}
    if not isinstance(options, Mapping):
        raise AssistConfigError(f"{where}.options must be an object")
    enabled = entry.get("enabled", True)
    if not isinstance(enabled, bool):
        raise AssistConfigError(f"{where}.enabled must be true or false")
    return ModelConfig(
        id=str(entry.get("id") or ""),
        provider=str(entry.get("provider") or ""),
        model=str(entry.get("model") or ""),
        enabled=enabled,
        timeout_s=_number(entry, "timeout_s", where),
        options=_freeze(options),
    )


__all__ = [
    "AssistConfig",
    "Budgets",
    "ModelConfig",
    "ProviderConfig",
    "refuse_secrets",
]
