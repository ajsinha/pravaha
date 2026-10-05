#!/usr/bin/env python3
"""
Pravaha console
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Application entry point. Wires configuration, the engine adapter, the services
and the routes, then serves.

    python run_pravaha_web.py
    python run_pravaha_web.py --server.port=8099 --engine.url=grpc://host:19090

The console reaches the engine only through the published Python SDK (ADR-024),
and ships as one artefact configured by an engine URL (ADR-033). It starts
whether or not the engine answers: an operator opening a console during an
incident needs it to load and say what is wrong, which is exactly the moment a
console that refuses to start is least useful.
"""
from __future__ import annotations

import argparse
import logging
import secrets
import sys
from pathlib import Path

import uvicorn
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from itsdangerous import URLSafeSerializer
from starlette.middleware.gzip import GZipMiddleware

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from core.config.properties_configurator import PropertiesConfigurator
from core.content.library import ContentLibrary
from core.engine import Engine
from core import page_help
from core.help_catalog import HelpCatalog
from core.i18n import Messages
from core.observability import RequestContext, configure_logging
from core.services import Services
from routes import ALL_ROUTES
from routes.base import use_messages
from routes.security_headers import SecurityHeaders
from routes.session_vault import SessionSecrets, TokenVault
from routes.web_security import SESSION_SECONDS, IdentityMiddleware, SchemeSessions, csrf_protect

logger = logging.getLogger("pravaha.console")


def _thousands(value) -> str:
    """`{{ n | thousands }}`. Every count on every screen is a row count, and
    seven digits unseparated is a number nobody reads at a glance."""
    try:
        return f"{int(value):,}"
    except (TypeError, ValueError):
        return str(value)


def _truncate(value, length: int = 64) -> str:
    """SQL in a table cell. The ellipsis is a character, not three dots, because
    three dots wrap and one does not."""
    text = str(value or "")
    return text if len(text) <= length else text[:length] + "…"


def create_app(config: PropertiesConfigurator, engine: Engine | None = None) -> FastAPI:
    """Build the application from configuration.

    Separate from ``main`` so a test can build one without binding a port, which
    is how the console's own tests run it. ``engine`` lets a test substitute the one
    adapter that talks to the engine -- everything above it is the real console.
    """
    if engine is None:
        # No token: the console holds no engine credential of its own (ADR-052). Each call
        # carries the signed-in person's session token, bound to the request that makes it.
        engine = Engine(config.get("engine.url", "grpc://localhost:19090"),
                        http_url=config.get("engine.http_url") or None)
    services = Services(engine,
                        row_limit=config.get_int("ui.query_row_limit", 500),
                        lag_warn_seconds=config.get_float("ui.lag_warn_seconds", 300.0))

    app = FastAPI(title=config.get("app.name", "Pravaha") + " console",
                  version=config.get("app.version", "0.1.0"),
                  docs_url="/api/docs",
                  # CSRF on every route, registered or yet to be: a POST nobody remembered to
                  # protect is the one an attacker finds (routes.web_security.csrf_protect).
                  dependencies=[Depends(csrf_protect)])

    # The session the sign-in writes into. A generated secret when none is configured: it
    # means sessions do not survive a restart, which is the right default for one instance
    # and a worse one for several -- so it is configurable rather than assumed.
    # The services the routes close over, reachable from the application for a test that has to
    # put one of them into a particular state -- an empty catalogue cache, say -- before driving
    # a browser at it. Nothing in the console reads it back out.
    app.state.services = services

    secret = config.get("console.session_secret") or secrets.token_urlsafe(32)
    # Innermost first: the request's credential is read from the session, so the identity
    # middleware sits inside the session middleware, which decodes the cookie before it runs.
    app.add_middleware(IdentityMiddleware)
    # The engine's session token and any secret the engine has just issued are kept here, in this
    # process, under an opaque id; the cookie carries only the id (COOKIETOKEN-1).
    app.add_middleware(TokenVault, store=SessionSecrets(SESSION_SECONDS))
    # The session cookie: signed, HttpOnly, SameSite=Lax, and Secure whenever the console is served
    # over https (or always, with console.secure_cookies). It holds no credential.
    app.add_middleware(SchemeSessions, secret_key=secret,
                       secure=config.get_bool("console.secure_cookies", False))

    # Compressed, because the console is opened during incidents over whatever link the
    # operator has: the shell's scripts are 122 kB as files and 41 kB gzipped (design 23.15
    # states its budget gzipped). Only on a Starlette that knows to leave server-sent events
    # alone -- an older one buffers a compressed stream, and a live view that arrives in
    # bursts is a live view that is wrong between them.
    try:
        from starlette.middleware.gzip import (
            DEFAULT_EXCLUDED_CONTENT_TYPES,  # noqa: F401
        )
    except ImportError:
        logger.info("responses are not compressed: this Starlette would buffer event streams")
    else:
        app.add_middleware(GZipMiddleware, minimum_size=1024)
    # Every response's security headers -- CSP with this request's script nonce, no framing,
    # nosniff, the referrer policy (CONSOLEHDR-1, routes.security_headers).
    app.add_middleware(SecurityHeaders)
    # Outermost: every request gets its correlation id (the one api.js sent, else a new one) for the
    # log lines written while serving it, and a traceparent it arrived with is carried onto the
    # engine calls made for it (core.observability).
    app.add_middleware(RequestContext)

    # Vendored assets only: the console renders with no external network. A
    # streaming engine is deployed inside networks that do not reach the
    # internet far more often than not.
    app.mount("/static", StaticFiles(directory=str(ROOT / "web" / "static")), name="static")
    templates = Jinja2Templates(directory=str(ROOT / "web" / "templates"))
    templates.env.filters["thousands"] = _thousands
    templates.env.filters["truncate_sql"] = _truncate
    # UI strings by key from web/i18n/<language>.json; one language today, a file per language later.
    messages = Messages(config.get("ui.language", "en"))
    templates.env.globals["t"] = messages
    use_messages(messages)
    # The help, the tutorials and About read from the content/ shipped with the console and from the
    # repository it sits in (README, docs/, examples/case-studies/). Both are settable, and nothing
    # but the visual tests sets them: they point them at a fixed copy so prose edits leave their
    # screenshots alone (ABOUTBASE-1).
    content = ContentLibrary(Path(config.get("content.root") or ROOT / "content"),
                             include_root=Path(config.get("content.repository"))
                             if config.get("content.repository") else None)
    help_catalog = HelpCatalog(content)
    ctx = {
        "config": config,
        # Signs the per-person landing preference (routes.auth_routes); a preference, not a secret.
        "signer": URLSafeSerializer(secret, salt="pravaha-landing"),
        "engine": engine,
        "services": services,
        "content": content,
        "help": help_catalog,
    }
    # Contextual help: a screen asks the catalog which topics answer the question it provokes,
    # so the cards and the screen's "?" link cannot name a page the help does not have.
    templates.env.globals["help_for"] = help_catalog.for_screen
    # "About this page": each screen's tiles, drawn once by base.html (core/page_help.py).
    templates.env.globals["page_tiles"] = page_help.tiles

    @app.exception_handler(HTTPException)
    async def problem(_request: Request, exc: HTTPException):
        """One error shape at the TOP level, not nested under `detail`.

        A client that has to parse two error formats will handle one of them
        badly, whichever route raised it.
        """
        body = exc.detail if isinstance(exc.detail, dict) else {
            "error": str(exc.detail), "status": exc.status_code}
        return JSONResponse(body, status_code=exc.status_code, headers=exc.headers)

    for routes in ALL_ROUTES:
        routes(app, ctx, templates)

    logger.info("%s console %s ready — engine at %s",
                config.get("app.name"), config.get("app.version"),
                config.get("engine.url"))
    return app


def config_path() -> str:
    """Which configuration file to start from.

    ``--config``, then ``PRAVAHA_CONFIG_FILE``, then the one in the repository.
    A hard-coded path would mean a second instance — a demonstration console, a
    copy pointed at staging — could only be started by editing a tracked file.
    """
    for index, argument in enumerate(sys.argv):
        if argument == "--config" and index + 1 < len(sys.argv):
            return sys.argv[index + 1]
        if argument.startswith("--config="):
            return argument.split("=", 1)[1]
    return str(ROOT / "config" / "application.yaml")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="The Pravaha operator console.",
        epilog="Any configuration key can be overridden: --server.port=8099")
    parser.add_argument("--config", help="configuration file to start from")
    parser.parse_known_args()

    config = PropertiesConfigurator(config_path())
    # text (the default) or json: one JSON object per line for Loki or Elasticsearch. Anything else
    # refuses the start rather than writing what a log pipeline did not ask for.
    configure_logging(config.get("logging.format", "text"), config.get("logging.level", "INFO"),
                      file=config.get("logging.file") or None,
                      max_bytes=config.get_int("logging.max_bytes", 50 * 1024 * 1024),
                      backups=config.get_int("logging.backups", 10))

    host = config.get("server.host", "127.0.0.1")
    port = config.get_int("server.port", 17070)
    app = create_app(config)

    # Said at startup rather than left to be discovered: the two ports are
    # different things and confusing them is the commonest way a first run
    # fails.
    logger.info("console on http://%s:%d — engine expected at %s",
                host, port, config.get("engine.url"))
    uvicorn.run(app, host=host, port=port,
                reload=config.get_bool("server.reload", False))


if __name__ == "__main__":
    main()
