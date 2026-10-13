"""
Pravaha console — route modules.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Each subclasses Routes, which owns the scaffolding they all share: the services,
the brand context, the refusal mapping, and the page renderer. Routers hold no
engine logic.
"""
from routes.admin_routes import AdminRoutes
from routes.ai_routes import AiRoutes
from routes.alert_routes import AlertRoutes
from routes.api_routes import ApiRoutes
from routes.assist_routes import AssistRoutes
from routes.auth_routes import AuthRoutes
from routes.base import API, Routes
from routes.catalog_routes import CatalogRoutes
from routes.declaration_routes import DeclarationRoutes
from routes.product_routes import ProductRoutes
from routes.public_routes import PublicRoutes
from routes.ui_routes import UIRoutes

# Order matters: `/queries/{name}` would swallow a literal path registered after
# it, so the modules with the more specific paths register first. CatalogRoutes before
# ProductRoutes, which reads the governance service it puts in the context; DeclarationRoutes
# before it too, so /catalog/streams/new is not read as a stream called "new".
ALL_ROUTES = (AuthRoutes, PublicRoutes, ApiRoutes, CatalogRoutes, DeclarationRoutes, AlertRoutes, ProductRoutes,
              AssistRoutes,
              AiRoutes, AdminRoutes, UIRoutes)

__all__ = ["ALL_ROUTES", "API", "AdminRoutes", "AiRoutes", "AlertRoutes", "ApiRoutes", "AssistRoutes", "AuthRoutes",
           "CatalogRoutes", "DeclarationRoutes", "ProductRoutes",
           "PublicRoutes", "Routes", "UIRoutes"]
