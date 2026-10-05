"""The service layer: everything the browser can ask for, as typed calls.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

ADR-033. The browser talks to versioned JSON services; the services talk to the engine
through the SDK. Nothing above this layer knows the SDK exists, and nothing below it
knows a browser does.

Three things follow from that split, and they are the reasons for it.

**The services hold no per-browser state, so the UI scales sideways.** Any instance can
answer any request, and instances go behind a load balancer without coordination. A page
that kept an engine connection per browser tab could not be scaled that way, because the
connection *is* the state.

**One engine subscription serves every browser watching the same view.** See
:class:`Broadcaster`. Without it, twenty analysts on one dashboard would be twenty
subscriptions to the engine -- and the product's claim is that ten analysts asking one
question cost one computation. A UI that quietly multiplied the load would be
contradicting the thing it exists to demonstrate.

**A slow browser cannot slow the engine.** Each subscriber has a bounded buffer and loses
its oldest rows when it falls behind, which is the same choice the engine's own
subscriptions make and for the same reason: a live view is about what is true now, so the
newest row matters more than the one the reader has not caught up to.
"""
from __future__ import annotations

# CONSOLESIZE-1: the services live in modules of their own, by what they serve; every name
# stays importable from here, which is where the routes and the tests have always found them.
from core.authoring_services import AuthoringService, CatalogService, ViewService, _metrics_state
from core.engine import Engine
from core.feeds import Broadcaster, Subscriber, _Feed
from core.ops_services import PLUGINS_NOT_EXPOSED, DeadLetterService, DebugService, OpsService
from core.ops_services import PluginService, ReplacementService
from core.query_services import AdHocService, Health, HealthService, Page, Query, QueryService
from core.service_base import QUOTA_CODES, ServiceError, _code_in, _refusal, jsonable

__all__ = [
    "PLUGINS_NOT_EXPOSED", "QUOTA_CODES", "AdHocService", "AuthoringService", "Broadcaster",
    "CatalogService", "DeadLetterService", "DebugService", "Health", "HealthService", "OpsService",
    "Page", "PluginService", "Query", "QueryService", "ReplacementService", "ServiceError",
    "Services", "Subscriber", "ViewService", "_Feed", "_code_in", "_metrics_state", "_refusal",
    "jsonable",
]


class Services:
    """Everything the API layer needs, constructed once."""

    def __init__(self, engine: Engine, *, row_limit: int = 500,
                 lag_warn_seconds: float = 300.0) -> None:
        self.engine = engine
        self.health = HealthService(engine)
        self.queries = QueryService(engine)
        self.dead_letters = DeadLetterService(engine)
        self.replacements = ReplacementService(engine)
        self.debug = DebugService(engine)
        self.adhoc = AdHocService(engine)
        self.feeds = Broadcaster(engine, snapshot_rows=row_limit)
        self.catalog = CatalogService(engine)
        self.authoring = AuthoringService(engine, self.catalog)
        self.views = ViewService(engine, self.queries, self.authoring, row_limit)
        self.ops = OpsService(engine, self.queries, self.feeds,
                              lag_warn_seconds).with_authoring(self.authoring)
        self.plugins = PluginService(engine, self.catalog)
        from core.accounts import AccountService
        from core.admin import AdminService

        self.admin = AdminService(engine)
        self.accounts = AccountService(engine)
