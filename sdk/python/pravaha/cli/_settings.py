"""Where the ``pravaha`` command finds the engine, and what it presents to it.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Precedence, the same for every setting: **a flag, then an environment variable, then the
default** -- and for the token only, the file ``pravaha login --save`` wrote, after both. Nothing
here decides whether a token may cross plaintext; :class:`pravaha.options.ClientOptions` and
:class:`pravaha.rest.RestClient` already refuse that unless ``--insecure-token`` says otherwise,
and the CLI does not get a second, looser rule.
"""

from __future__ import annotations

import argparse
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


@dataclass
class Settings:
    """Everything a command needs to reach the engine, resolved once."""

    url: str = DEFAULT_URL
    http: str = DEFAULT_HTTP
    token: Optional[str] = field(default=None, repr=False)
    #: Where the token came from: ``flag``, ``env``, ``file`` or ``None``.
    token_source: Optional[str] = None
    insecure_token: bool = False
    timeout: float = 30.0
    tls: TlsOptions = field(default_factory=TlsOptions)

    @staticmethod
    def resolve(args: argparse.Namespace, environ: Optional[Mapping[str, str]] = None) -> "Settings":
        env = os.environ if environ is None else environ

        def pick(name: str, *variables: str) -> Optional[str]:
            value = getattr(args, name, None)
            if value not in (None, ""):
                return str(value)
            for variable in variables:
                if env.get(variable):
                    return env[variable]
            return None

        token: Optional[str] = getattr(args, "token", None) or None
        source: Optional[str] = "flag" if token else None
        if token is None and env.get("PRAVAHA_TOKEN"):
            token, source = env["PRAVAHA_TOKEN"], "env"
        if token is None:
            token = read_saved_token(env)
            source = "file" if token else None

        insecure = bool(getattr(args, "insecure_token", False)) or (
            env.get("PRAVAHA_INSECURE_TOKEN", "").strip().lower() in _TRUE
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
            disable_hostname_verification=bool(getattr(args, "tls_no_verify", False)),
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
