"""``AssistAdmin``: what an administration screen needs, as plain functions over a store and a
router.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Every change goes the same way, so no screen can skip a step:

1. read the stored snapshot, and build the changed one from it;
2. validate it completely, with this process's environment (a model whose key variable is not
   set here is refused) -- on any problem, :class:`AssistConfigError` and nothing is stored;
3. save it against the version it was built from (a concurrent change by another administrator
   is :class:`~pravaha.assist.store.ConfigConflict`, not a silent overwrite);
4. apply it to the router, when there is one, through :meth:`ModelRouter.reconfigure`;
5. answer an :class:`AuditRecord` -- who, what, the element before and after, the version -- for
   the caller to log. It never holds a secret, because the configuration never does.

The console's admin UI (ADR-058 phase 3) and ``pravaha assist use|enable|disable`` are both
callers of this class.
"""

from __future__ import annotations

import dataclasses
import datetime
import os
from typing import Any, Callable, Mapping, Optional, Sequence

from pravaha.assist.config import AssistConfig, Budgets, ModelConfig, _freeze, refuse_secrets
from pravaha.assist.errors import AssistConfigError
from pravaha.assist.registry import ProviderInfo, providers
from pravaha.assist.router import CheckResult, ModelRouter
from pravaha.assist.store import AssistConfigStore

_UNSET: Any = object()


@dataclasses.dataclass(frozen=True)
class AuditRecord:
    """One configuration change: who made it, what it was, and the element before and after."""

    actor: Optional[str]
    action: str
    target: str
    before: Any
    after: Any
    version: int
    at: str

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)


class AssistAdmin:
    """Administration of the assistant's configuration: providers, models, profiles, budgets.

    ``actor`` is recorded as ``changed_by`` and in each :class:`AuditRecord`; ``environ`` is the
    environment keys are checked against (this process's by default).
    """

    def __init__(
        self,
        store: AssistConfigStore,
        router: Optional[ModelRouter] = None,
        *,
        actor: Optional[str] = None,
        environ: Optional[Mapping[str, str]] = None,
        dry_run: bool = False,
    ) -> None:
        self.store = store
        self.router = router
        self.actor = actor
        #: Validate each change and answer its record, but store and apply nothing.
        self.dry_run = dry_run
        self._environ = os.environ if environ is None else environ

    # ------------------------------------------------------------------ reading

    def config(self) -> AssistConfig:
        """The stored snapshot."""
        return self.store.load()

    def providers(self) -> "list[ProviderInfo]":
        """Every provider type this process can build -- built-in and discovered by entry
        point -- with its default capabilities."""
        return providers()

    def models(self) -> "list[dict[str, Any]]":
        """Each configured model, its provider, whether it is enabled, and the profiles whose
        chains name it (with its position: 1 answers first)."""
        config = self.config()
        rows: list[dict[str, Any]] = []
        for model in config.models:
            provider = config.provider(model.provider)
            rows.append(
                {
                    "id": model.id,
                    "provider": provider.id,
                    "type": provider.type,
                    "model": model.model,
                    "enabled": model.enabled,
                    "endpoint": provider.endpoint,
                    "key": provider.api_key_env or provider.api_key_file,
                    "keySet": provider.key_problem(self._environ) is None
                    if (provider.api_key_env or provider.api_key_file) else None,
                    "profiles": [
                        {"profile": name, "position": position,
                         "default": name == config.default_profile}
                        for name, position in config.profiles_of(model.id)
                    ],
                }
            )
        return rows

    # ------------------------------------------------------------------ the one way to change

    def _change(
        self,
        action: str,
        target: str,
        build: Callable[[AssistConfig], AssistConfig],
        view: Callable[[AssistConfig], Any],
    ) -> AuditRecord:
        current = self.store.load()
        changed = build(current)
        refuse_secrets(changed.to_dict())
        found = changed.problems(self._environ)
        if found:
            raise AssistConfigError(f"{action} {target}: refused, nothing changed: " + "; ".join(found))
        if self.dry_run:
            return AuditRecord(self.actor, action, target, view(current), view(changed),
                               current.version, _now())
        stored = self.store.save(changed, expected_version=current.version, by=self.actor)
        if self.router is not None:
            self.router.reconfigure(stored)
        return AuditRecord(
            actor=self.actor,
            action=action,
            target=target,
            before=view(current),
            after=view(stored),
            version=stored.version,
            at=stored.changed_at or _now(),
        )

    @staticmethod
    def _model_view(model_id: str) -> Callable[[AssistConfig], Any]:
        def view(config: AssistConfig) -> Any:
            found = [m for m in config.models if m.id == model_id]
            return found[0].to_dict() if found else None

        return view

    @staticmethod
    def _provider_view(provider_id: str) -> Callable[[AssistConfig], Any]:
        def view(config: AssistConfig) -> Any:
            found = [p for p in config.providers if p.id == provider_id]
            return found[0].to_dict() if found else None

        return view

    # ------------------------------------------------------------------ providers

    def add_provider(
        self,
        provider_id: str,
        type: str,
        *,
        endpoint: Optional[str] = None,
        api_key_env: Optional[str] = None,
        api_key_file: Optional[str] = None,
        timeout_s: float = 60.0,
        options: Optional[Mapping[str, Any]] = None,
    ) -> AuditRecord:
        """Configures a provider. Its key is named, never given: ``api_key_env`` or
        ``api_key_file``."""
        entry = AssistConfig.from_dict(
            {"providers": [{"id": provider_id, "type": type, "endpoint": endpoint,
                            "api_key_env": api_key_env, "api_key_file": api_key_file,
                            "timeout_s": timeout_s, "options": dict(options or {})}]}
        ).providers[0]

        def build(config: AssistConfig) -> AssistConfig:
            if any(p.id == provider_id for p in config.providers):
                raise AssistConfigError(f"provider {provider_id!r} already exists")
            return config.replace(providers=[*config.providers, entry])

        return self._change("add-provider", provider_id, build, self._provider_view(provider_id))

    def update_provider(self, provider_id: str, **changes: Any) -> AuditRecord:
        """Changes a provider's ``endpoint``, ``api_key_env``, ``api_key_file``, ``timeout_s``
        or ``options``."""
        allowed = {"endpoint", "api_key_env", "api_key_file", "timeout_s", "options"}
        unknown = sorted(set(changes) - allowed)
        if unknown:
            raise AssistConfigError(f"a provider has no field {', '.join(unknown)} to change")

        def build(config: AssistConfig) -> AssistConfig:
            old = config.provider(provider_id)
            merged = {**old.to_dict(), **{k: v for k, v in changes.items()}}
            entry = AssistConfig.from_dict({"providers": [merged]}).providers[0]
            return config.replace(providers=[entry if p.id == provider_id else p
                                             for p in config.providers])

        return self._change("update-provider", provider_id, build,
                            self._provider_view(provider_id))

    def remove_provider(self, provider_id: str) -> AuditRecord:
        """Removes a provider no model uses."""

        def build(config: AssistConfig) -> AssistConfig:
            config.provider(provider_id)
            users = [m.id for m in config.models if m.provider == provider_id]
            if users:
                raise AssistConfigError(
                    f"provider {provider_id!r} is used by {', '.join(users)}; remove them first"
                )
            return config.replace(providers=[p for p in config.providers if p.id != provider_id])

        return self._change("remove-provider", provider_id, build,
                            self._provider_view(provider_id))

    # ------------------------------------------------------------------ models

    def add_model(
        self,
        model_id: str,
        provider: str,
        model: str,
        *,
        enabled: bool = True,
        timeout_s: Optional[float] = None,
        options: Optional[Mapping[str, Any]] = None,
    ) -> AuditRecord:
        """Configures a model on a configured provider."""
        entry = ModelConfig(model_id, provider, model, enabled, timeout_s, _frozen(options))

        def build(config: AssistConfig) -> AssistConfig:
            if any(m.id == model_id for m in config.models):
                raise AssistConfigError(f"model {model_id!r} already exists")
            return config.replace(models=[*config.models, entry])

        return self._change("add-model", model_id, build, self._model_view(model_id))

    def update_model(self, model_id: str, **changes: Any) -> AuditRecord:
        """Changes a model's ``provider``, ``model``, ``enabled``, ``timeout_s`` or ``options``."""
        allowed = {"provider", "model", "enabled", "timeout_s", "options"}
        unknown = sorted(set(changes) - allowed)
        if unknown:
            raise AssistConfigError(f"a model has no field {', '.join(unknown)} to change")
        if "options" in changes:
            changes["options"] = _frozen(changes["options"])

        def build(config: AssistConfig) -> AssistConfig:
            old = config.model(model_id)
            entry = dataclasses.replace(old, **changes)
            return config.replace(models=[entry if m.id == model_id else m for m in config.models])

        return self._change("update-model", model_id, build, self._model_view(model_id))

    def remove_model(self, model_id: str) -> AuditRecord:
        """Removes a model no profile's chain names."""

        def build(config: AssistConfig) -> AssistConfig:
            config.model(model_id)
            return config.replace(models=[m for m in config.models if m.id != model_id])

        return self._change("remove-model", model_id, build, self._model_view(model_id))

    def enable_model(self, model_id: str) -> AuditRecord:
        return self._set_enabled(model_id, True)

    def disable_model(self, model_id: str) -> AuditRecord:
        """Disables a model. Refused while a profile's chain names it: change the chain first,
        so what answers is always a decision someone made, not what was left over."""
        return self._set_enabled(model_id, False)

    def _set_enabled(self, model_id: str, enabled: bool) -> AuditRecord:
        def build(config: AssistConfig) -> AssistConfig:
            old = config.model(model_id)
            entry = dataclasses.replace(old, enabled=enabled)
            return config.replace(models=[entry if m.id == model_id else m for m in config.models])

        return self._change("enable-model" if enabled else "disable-model", model_id, build,
                            self._model_view(model_id))

    # ------------------------------------------------------------------ profiles and budgets

    def set_chain(self, profile: str, chain: Sequence[str], *, default: bool = False) -> AuditRecord:
        """Sets ``profile``'s fallback chain: the order is the order models are tried in. With
        ``default=True`` it also becomes the default profile."""
        ordered = [c.strip() for c in chain if c and c.strip()]
        if not profile or not profile.strip():
            raise AssistConfigError("a profile needs a name")

        def build(config: AssistConfig) -> AssistConfig:
            profiles = {k: list(v) for k, v in config.profiles.items()}
            profiles[profile] = ordered
            changed = config.replace(profiles=profiles)
            if default or config.default_profile is None:
                changed = changed.replace(default_profile=profile)
            return changed

        def view(config: AssistConfig) -> Any:
            chain_now = config.profiles.get(profile)
            return {"chain": list(chain_now) if chain_now is not None else None,
                    "default": config.default_profile == profile}

        return self._change("set-chain", profile, build, view)

    def remove_profile(self, profile: str) -> AuditRecord:
        def build(config: AssistConfig) -> AssistConfig:
            if profile not in config.profiles:
                raise AssistConfigError(f"no profile {profile!r}")
            profiles = {k: list(v) for k, v in config.profiles.items() if k != profile}
            default = config.default_profile if config.default_profile != profile else None
            return config.replace(profiles=profiles, default_profile=default)

        def view(config: AssistConfig) -> Any:
            chain_now = config.profiles.get(profile)
            return list(chain_now) if chain_now is not None else None

        return self._change("remove-profile", profile, build, view)

    def set_default_profile(self, profile: str) -> AuditRecord:
        """The profile a task uses when its own is not configured."""

        def build(config: AssistConfig) -> AssistConfig:
            return config.replace(default_profile=profile)

        return self._change("set-default-profile", profile, build,
                            lambda config: config.default_profile)

    def set_budgets(
        self,
        *,
        per_user_daily_tokens: Optional[int] = _UNSET,
        per_request_max_tokens: Optional[int] = _UNSET,
    ) -> AuditRecord:
        """Sets either budget; ``None`` removes it."""

        def build(config: AssistConfig) -> AssistConfig:
            current = config.budgets
            return config.replace(budgets=Budgets(
                current.per_user_daily_tokens if per_user_daily_tokens is _UNSET
                else per_user_daily_tokens,
                current.per_request_max_tokens if per_request_max_tokens is _UNSET
                else per_request_max_tokens,
            ))

        return self._change("set-budgets", "budgets", build, lambda c: c.budgets.to_dict())

    # ------------------------------------------------------------------ testing

    def test_model(self, model_id: str) -> CheckResult:
        """Pings one configured model through its provider, as cheaply as the provider allows:
        the latency, or the normalised error. Uses the router's snapshot when there is a router,
        else the stored one."""
        router = self.router
        if router is None or router.config.version != self.store.load().version:
            router = ModelRouter(self.store.load(), environ=self._environ)
        return router.check([model_id])[0]


def _now() -> str:
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    return now.isoformat().replace("+00:00", "Z")


def _frozen(options: Optional[Mapping[str, Any]]) -> Mapping[str, Any]:
    frozen: Mapping[str, Any] = _freeze(dict(options or {}))
    return frozen


__all__ = ["AssistAdmin", "AuditRecord"]
