"""CONSOLETLS-1: ``engine.tls.ca`` (``PRAVAHA_ENGINE_CA``) reaches the console's engine client.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

A grpc+tls engine with a private CA was reachable only through GRPC_DEFAULT_SSL_ROOTS_FILE_PATH. The
setting is read from the configuration, handed to ``Engine``, and given to the SDK's client as its
CA -- which the SDK also uses for an https engine HTTP surface.
"""
from __future__ import annotations

import contextlib
import pathlib
import sys

import pytest

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE_ROOT))

from core import engine as engine_module
from core.config.properties_configurator import PropertiesConfigurator
from core.engine import Engine


def test_the_setting_is_read_from_the_environment(monkeypatch, tmp_path):
    ca = tmp_path / "ca.pem"
    monkeypatch.setenv("PRAVAHA_ENGINE_CA", str(ca))
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    assert config.get("engine.tls.ca") == str(ca)


def test_unset_it_is_empty(monkeypatch):
    monkeypatch.delenv("PRAVAHA_ENGINE_CA", raising=False)
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    assert not config.get("engine.tls.ca")


def test_create_app_hands_it_to_the_engine_and_the_engine_to_the_client(monkeypatch, tmp_path):
    from run_pravaha_web import create_app

    ca = tmp_path / "ca.pem"
    ca.write_text("-----BEGIN CERTIFICATE-----\n")
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("engine.url", "grpc+tls://engine.internal:19090")
    config.set("engine.http_url", "https://engine.internal:18080")
    config.set("engine.tls.ca", str(ca))
    config.set("console.session_secret", "engine-tls-test-secret")
    app = create_app(config)

    seen = []

    @contextlib.contextmanager
    def opened():
        yield None

    def connect(url=None, options=None):
        seen.append(options)
        return opened()

    monkeypatch.setattr(engine_module, "connect", connect)
    with app.state.services.engine._client():
        pass
    (options,) = seen
    assert options.tls.ca_certificate == ca
    assert options.http_url == "https://engine.internal:18080"


def test_a_ca_for_a_plaintext_engine_is_refused_at_start(tmp_path):
    with pytest.raises(ValueError, match="engine.tls.ca"):
        Engine("grpc://localhost:19090", tls_ca=str(tmp_path / "ca.pem"))
