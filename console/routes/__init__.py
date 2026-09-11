"""
Pravaha console — route modules.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Each subclasses Routes, which owns the scaffolding they all share: the services,
the brand context, the refusal mapping, and the page renderer. Routers hold no
engine logic.
"""
from routes.api_routes import ApiRoutes
from routes.base import API, Routes
from routes.public_routes import PublicRoutes
from routes.ui_routes import UIRoutes

# Order matters: `/queries/{name}` would swallow a literal path registered after
# it, so the modules with the more specific paths register first.
ALL_ROUTES = (PublicRoutes, ApiRoutes, UIRoutes)

__all__ = ["ALL_ROUTES", "API", "ApiRoutes", "PublicRoutes", "Routes", "UIRoutes"]
