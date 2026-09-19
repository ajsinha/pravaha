#!/usr/bin/env python3
"""
Pravaha console
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Application entry point. Wires configuration, the engine adapter, the services
and the routes, then serves.

    python run_pravaha_web.py
    python run_pravaha_web.py --server.port=8099 --engine.url=grpc://host:9090

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
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.gzip import GZipMiddleware
from starlette.middleware.sessions import SessionMiddleware

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from core.config.properties_configurator import PropertiesConfigurator
from core.content.library import ContentLibrary
from core.engine import Engine
from core.help_catalog import HelpCatalog
from core.i18n import Messages
from core.services import Services
from routes import ALL_ROUTES

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
        engine = Engine(config.get("engine.url", "grpc://localhost:9090"),
                        config.get("engine.token") or None,
                        http_url=config.get("engine.http_url") or None)
    services = Services(engine,
                        row_limit=config.get_int("ui.query_row_limit", 500),
                        lag_warn_seconds=config.get_float("ui.lag_warn_seconds", 300.0))

    app = FastAPI(title=config.get("app.name", "Pravaha") + " console",
                  version=config.get("app.version", "0.1.0"),
                  docs_url="/api/docs")

    # The session the sign-in writes into. A generated secret when none is configured: it
    # means sessions do not survive a restart, which is the right default for one instance
    # and a worse one for several -- so it is configurable rather than assumed.
    secret = config.get("console.session_secret") or secrets.token_urlsafe(32)
    app.add_middleware(SessionMiddleware, secret_key=secret, same_site="lax", https_only=False)

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

    # Vendored assets only: the console renders with no external network. A
    # streaming engine is deployed inside networks that do not reach the
    # internet far more often than not.
    app.mount("/static", StaticFiles(directory=str(ROOT / "web" / "static")), name="static")
    templates = Jinja2Templates(directory=str(ROOT / "web" / "templates"))
    templates.env.filters["thousands"] = _thousands
    templates.env.filters["truncate_sql"] = _truncate
    # UI strings by key from web/i18n/<language>.json; one language today, a file per language later.
    templates.env.globals["t"] = Messages(config.get("ui.language", "en"))
    content = ContentLibrary(ROOT / "content")
    help_catalog = HelpCatalog(content)
    content = ContentLibrary(ROOT / "content")
    ctx = {
        "config": config,
        "engine": engine,
        "services": services,
        "content": content,
        "help": help_catalog,
    }
    # Contextual help: a screen asks the catalog which topics answer the question it provokes,
    # so the cards and the screen's "?" link cannot name a page the help does not have.
    templates.env.globals["help_for"] = help_catalog.for_screen

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
    logging.basicConfig(
        level=getattr(logging, config.get("logging.level", "INFO").upper(), logging.INFO),
        format="%(asctime)s %(levelname)-5s %(name)s — %(message)s")

    host = config.get("server.host", "127.0.0.1")
    port = config.get_int("server.port", 8090)
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
