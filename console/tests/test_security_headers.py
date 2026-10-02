"""The console's response headers: no framing, no sniffing, a CSP its own pages live within.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

CONSOLEHDR-1. No response carried Content-Security-Policy, X-Frame-Options or frame-ancestors,
nosniff or a Referrer-Policy, so a signed-in operator could be shown the console inside another
site's frame. These tests pin the headers; the browser suites (journeys, states, visual) are the
proof that every page still renders and works under the policy.
"""
from __future__ import annotations

import pathlib
import re
import sys

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE_ROOT))

from fake_engine import FakeEngine
from fake_identity import sign_in

from core.config.properties_configurator import PropertiesConfigurator
from run_pravaha_web import create_app


def _client(base_url: str = "http://testserver"):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.session_secret", "headers-test-secret")
    config.set("console.secure_cookies", "false")
    return fastapi_testclient.TestClient(create_app(config, engine=FakeEngine()), base_url=base_url)


def _csp(response) -> dict[str, str]:
    policy = response.headers["content-security-policy"]
    return {part.split(" ", 1)[0]: part.split(" ", 1)[1] if " " in part else ""
            for part in (p.strip() for p in policy.split(";")) if part}


@pytest.mark.parametrize("path", ["/", "/login", "/help", "/static/js/api.js", "/no-such-page"])
def test_every_response_refuses_framing_and_sniffing(path):
    response = _client().get(path)
    assert response.headers["x-frame-options"] == "DENY"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["referrer-policy"] == "same-origin"
    assert "camera=()" in response.headers["permissions-policy"]
    directives = _csp(response)
    assert directives["frame-ancestors"] == "'none'"
    assert directives["object-src"] == "'none'"
    assert "unsafe-inline" not in directives["script-src"] and "unsafe-eval" not in directives["script-src"]
    assert "strict-transport-security" not in response.headers, "only over https"


def test_over_https_the_console_asks_for_https_from_then_on():
    response = _client("https://testserver").get("/")
    assert response.headers["strict-transport-security"].startswith("max-age=")


def test_every_inline_script_carries_this_responses_nonce():
    client = _client()
    sign_in(client)
    for path in ("/", "/home", "/views", "/overview", "/workbench", "/queries"):
        response = client.get(path)
        assert response.status_code == 200, path
        nonce = re.search(r"'nonce-([^']+)'", _csp(response)["script-src"]).group(1)
        for tag in re.findall(r"<script\b[^>]*>", response.text):
            if "src=" in tag or 'type="application/json"' in tag:
                continue
            assert f'nonce="{nonce}"' in tag, (path, tag)
    # A nonce is per response, never reused.
    first, second = (_csp(client.get("/"))["script-src"] for _ in range(2))
    assert first != second


def test_no_page_or_script_relies_on_an_inline_event_handler():
    """The policy refuses onclick= and friends; nothing the console writes may use one."""
    handler = re.compile(r"""\son[a-z]+\s*=\s*["']""")
    web = CONSOLE_ROOT / "web"
    offenders = []
    for path in list((web / "templates").rglob("*.html")) + list((web / "static" / "js").rglob("*.js")) \
            + list((web / "static" / "app").rglob("*.js")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if handler.search(line) and "addEventListener" not in line:
                offenders.append(f"{path.relative_to(CONSOLE_ROOT)}:{number}: {line.strip()[:100]}")
    assert offenders == []


def test_fastapis_own_documentation_still_loads_its_assets():
    response = _client().get("/api/docs")
    directives = _csp(response)
    assert "script-src" not in directives and directives["frame-ancestors"] == "'none'"
