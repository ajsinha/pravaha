"""
Pravaha console — route modules.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Each subclasses Routes, which owns the scaffolding they all share: the services,
the brand context, the refusal mapping, and the page renderer. Routers hold no
engine logic.
"""
from routes.admin_routes import AdminRoutes
from routes.api_routes import ApiRoutes
from routes.auth_routes import AuthRoutes
from routes.base import API, Routes
from routes.catalog_routes import CatalogRoutes
from routes.product_routes import ProductRoutes
from routes.public_routes import PublicRoutes
from routes.ui_routes import UIRoutes

# Order matters: `/queries/{name}` would swallow a literal path registered after
# it, so the modules with the more specific paths register first. CatalogRoutes before
# ProductRoutes, which reads the governance service it puts in the context.
ALL_ROUTES = (AuthRoutes, PublicRoutes, ApiRoutes, CatalogRoutes, ProductRoutes, AdminRoutes, UIRoutes)

__all__ = ["ALL_ROUTES", "API", "AdminRoutes", "ApiRoutes", "AuthRoutes", "CatalogRoutes", "ProductRoutes",
           "PublicRoutes", "Routes", "UIRoutes"]
