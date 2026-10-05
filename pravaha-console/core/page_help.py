"""
Pravaha console — "About this page": the short help at the foot of every page.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

MAYA's panel, in Pravaha's shell. What a screen shows, and the idea that makes sense of it, is
easy to miss on a console this dense, so each page ends with a few lines: what the page is for,
two to four tiles (what you can do there, the concept behind it, the gotcha people hit), and the
help topics that say the rest. The words are short on purpose -- the topics are the full
account, and the panel links to them rather than repeating them.

How a page gets its panel: ``Routes.page()`` renders a template, and the template's name
resolves here to a *screen* (``TEMPLATES``). ``base.html`` draws the panel once for that screen:

* the one-line summary and each tile's heading and text are strings in ``web/i18n/en.json``,
  ``page.<screen>.what`` and ``page.<screen>.points.<n>.{heading,text}``, so a translation carries
  them;
* each tile's icon is here (``TILES``), and the number of icons is the number of tiles;
* "More in Help" is the screen's entry in ``core.help_catalog.SCREEN_HELP``, the same topics the
  "?" beside a heading opens.

Keyed by template rather than by route: one template is one page whatever route renders it --
a not-found page reached from a view's address is about the missing name, not about the view.
``tests/test_page_help.py`` walks every page route and fails on a page with no panel, a template
with no screen, a screen with no words, and words for a screen no page shows.
"""
from __future__ import annotations

#: Every page template, and the screen whose panel it ends with.
TEMPLATES: dict[str, str] = {
    "start.html": "start",
    "overview.html": "overview",
    "workbench.html": "workbench",
    "catalog.html": "catalog",
    "catalog_object.html": "catalog-object",
    "stream_detail.html": "stream",
    "views.html": "views",
    "view_detail.html": "view",
    "live.html": "live",
    "operations.html": "operations",
    "alerts.html": "alerts",
    "alert_detail.html": "alert",
    "queries.html": "queries",
    "query_detail.html": "query",
    "replacement.html": "replacement",
    "debug.html": "debug",
    "dead_letters.html": "dead-letters",
    "plugins.html": "plugins",
    "assist_result.html": "assistant",
    "components.html": "components",
    "admin_access.html": "admin-access",
    "admin_audit.html": "admin-audit",
    "admin_tenants.html": "admin-tenants",
    "admin_users.html": "admin-users",
    "admin_keys.html": "admin-keys",
    "admin_sessions.html": "admin-sessions",
    "admin_lanes.html": "admin-lanes",
    "admin_grants.html": "admin-grants",
    "admin_policies.html": "admin-policies",
    "admin_ai_models.html": "ai-models",
    "landing.html": "landing",
    "login.html": "login",
    "login_reset.html": "login-reset",
    "account.html": "account",
    "account_password.html": "account-password",
    "about.html": "about",
    "competitive.html": "competitive",
    "not_found.html": "not-found",
    "refused.html": "refused",
}

#: Templates that end without the panel, and why. Help is the full account already, and each
#: topic ends with its own footer (_help_footer.html): a second one would say the same twice.
EXEMPT: dict[str, str] = {
    "help.html": "the help and tutorial indexes: Help itself",
    "help_index.html": "the help index: Help itself",
    "help_search.html": "help search: Help itself",
    "help_guides.html": "the guides list: Help itself",
    "help_topic.html": "a guide or tutorial: Help itself, with its own footer",
    "help_topic_page.html": "a help topic: Help itself, with its own footer",
    "help_codes.html": "the error codes: Help itself",
    "help_code.html": "one error code: Help itself",
    "help_case_studies.html": "the case studies: Help itself",
    "help_case_study.html": "one case study: Help itself",
}

#: Each screen's tiles, as their icons (Bootstrap Icons names), in order: tile n is
#: page.<screen>.points.<n> in the string catalog.
TILES: dict[str, tuple[str, ...]] = {
    "start": ("list-ol", "code-square", "collection"),
    "overview": ("grid-1x2", "diagram-3", "plug"),
    "workbench": ("play-circle", "exclamation-diamond", "hourglass-split"),
    "catalog": ("collection", "diagram-3", "box-arrow-right"),
    "catalog-object": ("shield-check", "funnel", "question-diamond"),
    "stream": ("clock-history", "box-arrow-in-right", "diagram-3"),
    "views": ("table", "plug", "box-arrow-up-right"),
    "view": ("key", "check2-circle", "code-slash"),
    "live": ("plus-slash-minus", "funnel", "broadcast"),
    "operations": ("speedometer2", "signpost-split", "dash-circle"),
    "alerts": ("bell", "bell-slash", "terminal"),
    "alert": ("eye", "check2-square", "clock-history"),
    "queries": ("arrow-repeat", "diagram-3", "arrow-left-right"),
    "query": ("pause-circle", "fingerprint", "people"),
    "replacement": ("hourglass", "arrow-left-right", "arrow-counterclockwise"),
    "debug": ("bezier2", "skip-forward", "file-earmark-code"),
    "dead-letters": ("arrow-repeat", "archive", "slash-circle"),
    "plugins": ("plug", "exclamation-triangle", "shield-lock"),
    "assistant": ("shield-check", "stars"),
    "components": ("palette", "layers"),
    "admin-access": ("person-check", "eye-slash", "lock"),
    "admin-audit": ("link-45deg", "shield-lock", "window"),
    "admin-tenants": ("door-closed", "diagram-3", "gear"),
    "admin-users": ("person-x", "tags", "key"),
    "admin-keys": ("eye-slash", "person-badge"),
    "admin-sessions": ("box-arrow-right", "hourglass-split"),
    "admin-lanes": ("signpost-split", "arrow-left-right", "shield-lock"),
    "admin-grants": ("person-check", "tag", "toggle-off"),
    "admin-policies": ("funnel", "link", "code"),
    "ai-models": ("lightning", "link-45deg", "key"),
    "landing": ("arrow-repeat", "plus-slash-minus", "flag"),
    "login": ("shield-check", "key"),
    "login-reset": ("hourglass-split", "box-arrow-right", "shield-lock"),
    "account": ("signpost", "key", "pc-display"),
    "account-password": ("shield-lock", "exclamation-triangle"),
    "about": ("journal-check", "rulers", "book"),
    "competitive": ("star", "exclamation-circle", "hdd"),
    "not-found": ("type", "trash"),
    "refused": ("exclamation-diamond", "hash", "speedometer"),
}

#: The fewest and the most tiles a panel carries: one is a sentence, five is a page.
MIN_TILES, MAX_TILES = 2, 4


def screen_for(template: str) -> str | None:
    """The screen whose panel ``template`` ends with, or None for an exempt page."""
    return TEMPLATES.get(template)


def tiles(screen: str) -> list[tuple[int, str]]:
    """``(n, icon)`` for each of a screen's tiles: the template reads tile n's words by key."""
    return list(enumerate(TILES.get(screen, ()), start=1))
