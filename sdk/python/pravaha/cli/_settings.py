"""Where the ``pravaha`` command finds the engine, and what it presents to it.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Precedence, the same for every setting: **a flag, then an environment variable, then the selected
context, then the default** -- and for the token only, with no context selected, the file
``pravaha login --save`` wrote, after the environment. The context is the one ``--context`` names,
else ``PRAVAHA_CONTEXT``, else the file's ``current-context``; contexts live in ``contexts.json``
beside the token file (:class:`ContextStore`). With no contexts file, or none selected, every
command behaves exactly as it did before contexts existed. Nothing here decides whether a token may cross plaintext; :class:`pravaha.options.ClientOptions` and
:class:`pravaha.rest.RestClient` already refuse that unless ``--insecure-token`` says otherwise,
and the CLI does not get a second, looser rule.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Mapping, Optional

from pravaha.api import EngineApi
from pravaha.tls import TlsOptions

if TYPE_CHECKING:  # the Flight client needs pyarrow, imported only by a command that uses it
    from pravaha.client import Client

DEFAULT_URL = "grpc://localhost:19090"
DEFAULT_HTTP = "http://localhost:18080"
APPLICATION_NAME = "pravaha-cli"

_TRUE = ("1", "true", "yes", "on")


def config_dir(environ: Optional[Mapping[str, str]] = None) -> pathlib.Path:
    """``$PRAVAHA_CONFIG_DIR``, else ``$XDG_CONFIG_HOME/pravaha``, else ``~/.config/pravaha``."""
    env = os.environ if environ is None else environ
    if env.get("PRAVAHA_CONFIG_DIR"):
        return pathlib.Path(env["PRAVAHA_CONFIG_DIR"])
    base = env.get("XDG_CONFIG_HOME") or str(pathlib.Path.home() / ".config")
    return pathlib.Path(base) / "pravaha"


def token_file(environ: Optional[Mapping[str, str]] = None) -> pathlib.Path:
    return config_dir(environ) / "token"


def read_saved_token(environ: Optional[Mapping[str, str]] = None) -> Optional[str]:
    path = token_file(environ)
    try:
        text = path.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    return text or None


def save_token(token: str, environ: Optional[Mapping[str, str]] = None) -> pathlib.Path:
    """Writes the token readable by its owner only (0600), in a directory only they may list.

    Created with its mode rather than chmod-ed afterwards, so there is no moment at which the
    file exists with the umask's looser permissions.
    """
    path = token_file(environ)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.exists():
        path.unlink()
    descriptor = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(token + "\n")
    os.chmod(path, 0o600)
    return path


def forget_token(environ: Optional[Mapping[str, str]] = None) -> bool:
    path = token_file(environ)
    try:
        path.unlink()
        return True
    except FileNotFoundError:
        return False


# ---------------------------------------------------------------------------------- contexts

#: What a context may hold, by the flag's name without its dashes. Nothing else is accepted, so a
#: typing mistake in a hand-edited file is refused by name rather than ignored.
CONTEXT_KEYS = (
    "url", "http", "token", "insecure-token", "timeout",
    "tls-ca", "tls-cert", "tls-key",
    "tls-trust-store", "tls-trust-store-password", "tls-trust-store-type",
    "tls-key-store", "tls-key-store-password", "tls-key-store-type",
    "tls-override-hostname", "tls-no-verify",
)
#: The keys whose values are switches: true, or absent.
CONTEXT_SWITCHES = ("insecure-token", "tls-no-verify")
#: The keys never printed: ``context show`` says only that one is set.
CONTEXT_SECRETS = ("token", "tls-trust-store-password", "tls-key-store-password")


class ContextError(ValueError):
    """A context that does not exist, or a contexts file that cannot be read. Exit 2: nothing
    was sent."""


def contexts_file(environ: Optional[Mapping[str, str]] = None) -> pathlib.Path:
    return config_dir(environ) / "contexts.json"


def _write_private(path: pathlib.Path, text: str) -> None:
    """``text`` into ``path``, mode 0600, atomically: a temporary file beside it, created with its
    mode, then renamed over it. A reader sees the old file or the new one, never half of one."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    if temporary.exists():
        temporary.unlink()
    descriptor = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    except BaseException:
        if temporary.exists():
            temporary.unlink()
        raise


class ContextStore:
    """Named connections, kubectl-style, in ``contexts.json`` beside the token file::

        {"current-context": "prod",
         "contexts": {"prod": {"url": "grpc+tls://node-1:19090", "http": "https://node-1:18080",
                               "tls-ca": "/etc/pravaha/ca.pem", "token": "..."}}}

    JSON, like ``assist.json`` in the same directory, written 0600 atomically. A missing file is no
    contexts at all, which is how every command behaved before there were any."""

    def __init__(self, environ: Optional[Mapping[str, str]] = None) -> None:
        self.path = contexts_file(environ)
        self.current: Optional[str] = None
        self.contexts: "dict[str, dict[str, Any]]" = {}
        try:
            text = self.path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return
        except OSError as exc:
            raise ContextError(f"cannot read {self.path}: {exc.strerror or exc}") from None
        try:
            document = json.loads(text) if text.strip() else {}
        except ValueError as exc:
            raise ContextError(f"{self.path} is not valid JSON: {exc}") from None
        if not isinstance(document, dict):
            raise ContextError(f"{self.path}: expected a JSON object")
        unknown = sorted(set(document) - {"current-context", "contexts"})
        if unknown:
            raise ContextError(f"{self.path}: unknown field {unknown[0]!r} "
                               "(the file holds current-context and contexts)")
        contexts = document.get("contexts") or {}
        if not isinstance(contexts, dict):
            raise ContextError(f"{self.path}: contexts must be an object of name -> settings")
        for name, entry in contexts.items():
            if not isinstance(entry, dict):
                raise ContextError(f"{self.path}: context {name!r} must be an object")
            wrong = sorted(set(entry) - set(CONTEXT_KEYS))
            if wrong:
                raise ContextError(f"{self.path}: context {name!r} has an unknown setting "
                                   f"{wrong[0]!r}; a context holds {', '.join(CONTEXT_KEYS)}")
            self.contexts[str(name)] = dict(entry)
        current = document.get("current-context")
        self.current = str(current) if current else None

    def get(self, name: str) -> "dict[str, Any]":
        if name not in self.contexts:
            known = ", ".join(sorted(self.contexts)) or "none yet"
            raise ContextError(f"no context named {name!r} (contexts: {known}; "
                               f"`pravaha context add {name} --url ... --http ...` makes one)")
        return self.contexts[name]

    def save(self) -> pathlib.Path:
        document: "dict[str, Any]" = {}
        if self.current:
            document["current-context"] = self.current
        document["contexts"] = {name: self.contexts[name] for name in sorted(self.contexts)}
        _write_private(self.path, json.dumps(document, indent=2) + "\n")
        return self.path


def selected_context(
    args: Optional[argparse.Namespace], environ: Optional[Mapping[str, str]] = None
) -> "tuple[Optional[str], Optional[str]]":
    """The context in use and what chose it -- ``("prod", "flag" | "env" | "current")`` -- or
    ``(None, None)``. ``--context`` beats ``PRAVAHA_CONTEXT``, which beats ``current-context``."""
    env = os.environ if environ is None else environ
    named = getattr(args, "context", None) if args is not None else None
    if named:
        return str(named), "flag"
    if env.get("PRAVAHA_CONTEXT"):
        return env["PRAVAHA_CONTEXT"], "env"
    current = ContextStore(env).current
    return (current, "current") if current else (None, None)


def mask(key: str, value: Any) -> Any:
    """What ``context show`` prints for a setting: a secret only as ``<set>``."""
    return "<set>" if key in CONTEXT_SECRETS and value else value


def save_context_token(name: str, token: str, environ: Optional[Mapping[str, str]] = None) -> str:
    """``login --save`` with a context in use: the token goes into that context, not the file."""
    store = ContextStore(environ)
    store.get(name)["token"] = token
    return str(store.save()) + f" (context {name})"


def forget_context_token(name: str, environ: Optional[Mapping[str, str]] = None) -> bool:
    store = ContextStore(environ)
    if store.get(name).pop("token", None) is None:
        return False
    store.save()
    return True


@dataclass
class Settings:
    """Everything a command needs to reach the engine, resolved once."""

    url: str = DEFAULT_URL
    http: str = DEFAULT_HTTP
    token: Optional[str] = field(default=None, repr=False)
    #: Where the token came from: ``flag``, ``env``, ``context``, ``file`` or ``None``.
    token_source: Optional[str] = None
    insecure_token: bool = False
    timeout: float = 30.0
    tls: TlsOptions = field(default_factory=TlsOptions)
    #: The context in use, and what chose it: ``flag`` (--context), ``env`` (PRAVAHA_CONTEXT) or
    #: ``current`` (``pravaha context use``); both ``None`` when there is none.
    context: Optional[str] = None
    context_source: Optional[str] = None

    @staticmethod
    def resolve(args: argparse.Namespace, environ: Optional[Mapping[str, str]] = None) -> "Settings":
        env = os.environ if environ is None else environ
        context, context_source = selected_context(args, env)
        saved: "dict[str, Any]" = ContextStore(env).get(context) if context else {}

        def pick(name: str, *variables: str) -> Optional[str]:
            value = getattr(args, name, None)
            if value not in (None, ""):
                return str(value)
            for variable in variables:
                if env.get(variable):
                    return env[variable]
            stored = saved.get(name.replace("_", "-"))
            if stored not in (None, ""):
                return str(stored)
            return None

        token: Optional[str] = getattr(args, "token", None) or None
        source: Optional[str] = "flag" if token else None
        if token is None and env.get("PRAVAHA_TOKEN"):
            token, source = env["PRAVAHA_TOKEN"], "env"
        if token is None and saved.get("token"):
            token, source = str(saved["token"]), "context"
        if token is None and context is None:
            # The file login --save wrote with no context in use. Not read under a context: that
            # token was issued by whichever node was the default, and a context names another.
            token = read_saved_token(env)
            source = "file" if token else None

        insecure = (
            bool(getattr(args, "insecure_token", False))
            or env.get("PRAVAHA_INSECURE_TOKEN", "").strip().lower() in _TRUE
            or bool(saved.get("insecure-token"))
        )
        timeout_text = pick("timeout", "PRAVAHA_TIMEOUT")
        tls = TlsOptions.create(
            ca_certificate=pick("tls_ca", "PRAVAHA_TLS_CA"),
            client_certificate=pick("tls_cert", "PRAVAHA_TLS_CERT"),
            client_key=pick("tls_key", "PRAVAHA_TLS_KEY"),
            trust_store=pick("tls_trust_store"),
            trust_store_password=pick("tls_trust_store_password", "PRAVAHA_TLS_TRUST_STORE_PASSWORD"),
            trust_store_type=pick("tls_trust_store_type") or "JKS",
            key_store=pick("tls_key_store"),
            key_store_password=pick("tls_key_store_password", "PRAVAHA_TLS_KEY_STORE_PASSWORD"),
            key_store_type=pick("tls_key_store_type") or "JKS",
            disable_hostname_verification=bool(getattr(args, "tls_no_verify", False))
            or bool(saved.get("tls-no-verify")),
            override_hostname=pick("tls_override_hostname"),
        )
        return Settings(
            url=pick("url", "PRAVAHA_URL") or DEFAULT_URL,
            http=(pick("http", "PRAVAHA_HTTP", "PRAVAHA_ENGINE_HTTP") or DEFAULT_HTTP).rstrip("/"),
            token=token,
            token_source=source,
            insecure_token=insecure,
            timeout=float(timeout_text) if timeout_text else 30.0,
            tls=tls,
            context=context,
            context_source=context_source,
        )

    def api(self, *, token: Any = ...) -> EngineApi:
        """The engine's HTTP API. ``token=None`` sends none (signing in needs none)."""
        chosen = self.token if token is ... else token
        return EngineApi(
            self.http,
            token=chosen,
            timeout_seconds=self.timeout,
            tls=self.tls if self.http.startswith("https://") else None,
            allow_insecure_token=self.insecure_token,
        )

    def client(self) -> "Client":
        """A Flight client. Imports pyarrow, which only the Flight commands need."""
        from pravaha.client import Client
        from pravaha.endpoint import Endpoint
        from pravaha.options import ClientOptions

        endpoint = Endpoint.parse(self.url)
        options = ClientOptions(
            endpoint=endpoint,
            token=self.token,
            request_timeout_seconds=self.timeout,
            application_name=APPLICATION_NAME,
            allow_insecure_token=self.insecure_token,
            # TLS material applies to whichever connection is encrypted; handing it to a
            # plaintext grpc:// endpoint is refused by ClientOptions, rightly, so it is not.
            tls=self.tls if endpoint.tls else TlsOptions(),
        )
        return Client(options)
