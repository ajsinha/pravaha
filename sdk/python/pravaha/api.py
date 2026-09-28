"""The engine's published HTTP API as typed calls, with no Flight and no pyarrow.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

    from pravaha.api import EngineApi

    api = EngineApi("https://engine.example.com:18080", token=token)
    print(api.status()["engineState"])
    for query in api.describe_queries():
        print(query["name"], query["lane"])

Every call here is one endpoint of ``api/openapi.lock.json`` (plus the node's actuator health and
Prometheus endpoints), named in its docstring. :class:`pravaha.client.Client` answers the same
calls by delegating to one of these, so there is one implementation of each; this class exists
on its own because a caller that only administers -- signs in, lists users, rebalances lanes,
reads the audit trail -- should not have to install pyarrow or name a Flight port to do it.
The ``pravaha`` command line is built on it.

Answers are the engine's JSON, decoded: ``dict`` and ``list`` in the API's own field names, so
the OpenAPI document is their reference and a field the engine adds reaches the caller without
a release of this SDK. A refusal is :class:`pravaha.rest.ApiError` carrying the engine's
``PRV-nnnn`` code; an engine that did not answer is the same error with ``status == 0``.

Standard library only, as :mod:`pravaha.rest` is.
"""

from __future__ import annotations

import urllib.parse
from typing import Any, Optional, Sequence

from pravaha.rest import ApiError, RestClient
from pravaha.tls import TlsOptions


def _segment(name: str) -> str:
    """A name as one URL path segment, so a name cannot address a different endpoint."""
    return urllib.parse.quote(str(name), safe="")


def _items(answer: Any, name: str) -> "list[dict[str, Any]]":
    """A list the engine answers wrapped as ``{"<name>": [...]}``."""
    if isinstance(answer, dict):
        answer = answer.get(name, [])
    return [dict(item) for item in (answer or []) if isinstance(item, dict)]


class EngineApi:
    """One engine's HTTP surface: catalogue, queries, lanes, identity, audit and health.

    Construct it from the engine's HTTP URL (``http://host:18080`` by default -- a separate
    port from Flight's) or wrap a :class:`RestClient` you already hold. A token over plaintext
    ``http://`` is refused unless ``allow_insecure_token=True``, as everywhere in this SDK.
    """

    def __init__(
        self,
        base_url: "str | RestClient",
        *,
        token: Optional[str] = None,
        timeout_seconds: float = 30.0,
        tls: Optional[TlsOptions] = None,
        allow_insecure_token: bool = False,
    ) -> None:
        if isinstance(base_url, RestClient):
            self._rest = base_url
        else:
            self._rest = RestClient(
                base_url,
                token=token,
                timeout_seconds=timeout_seconds,
                tls=tls,
                allow_insecure_token=allow_insecure_token,
            )

    @property
    def rest(self) -> RestClient:
        """The underlying JSON client, for an endpoint this class does not name yet."""
        return self._rest

    @property
    def base_url(self) -> str:
        return self._rest.base_url

    # ------------------------------------------------------------------ the node

    def status(self) -> dict[str, Any]:
        """The node: ``instanceId``, ``version``, ``engineState``, ``uptimeSeconds``,
        ``registeredQueries``, ``streams``, ``stoppedFeeds``, ``flight`` and each plugin's
        ``health``. ``GET /api/v1/status``."""
        return dict(self._rest.get("/api/v1/status") or {})

    def health(self) -> dict[str, Any]:
        """The node's aggregate health as its orchestrator sees it: ``status`` (``UP``,
        ``DEGRADED``, ``OUT_OF_SERVICE`` or ``DOWN``) and, when the node shows them, its
        ``components``. Answered, not raised, when the node says it is not healthy -- the
        probe answers ``503`` with the same document, and that document is what was asked
        for. ``GET /actuator/health``."""
        try:
            return dict(self._rest.get("/actuator/health") or {})
        except ApiError as refused:
            if refused.status == 503 and isinstance(refused.body, dict) and "status" in refused.body:
                return dict(refused.body)
            raise

    def metrics_text(self) -> str:
        """The node's Prometheus exposition, unparsed. ``GET /actuator/prometheus``."""
        return self._rest.text("/actuator/prometheus")

    def plugins(self) -> "list[dict[str, Any]]":
        """Every plugin the node can load: ``name``, ``version``, ``requiredApiVersion``,
        ``compatible``, ``loaded``, ``kinds``, ``capabilities``, ``settings`` (names only),
        ``health`` and the ``bindings`` this principal may see. Never a binding's options.
        ``GET /api/v1/plugins``."""
        return list(self._rest.get("/api/v1/plugins") or [])

    def sinks(self) -> "list[dict[str, Any]]":
        """The sinks this node binds that this principal may see: ``plugin``, ``fields``,
        ``keyColumns``, ``emitModes``, ``acceptsRetractions``, ``guarantee`` and the visible
        ``writers``. Never a binding's options. ``GET /api/v1/sinks``."""
        return list(self._rest.get("/api/v1/sinks") or [])

    # ------------------------------------------------------------------ the catalogue

    def streams(self) -> "list[dict[str, Any]]":
        """Every stream this principal may read: ``name``, ``version``, ``fields``,
        ``eventTime``, ``outOfOrderness`` (ISO-8601) and ``source`` (the plugin that feeds
        it, if bound). ``GET /api/v1/streams``."""
        return list(self._rest.get("/api/v1/streams") or [])

    def stream(self, name: str) -> dict[str, Any]:
        """One stream. ``GET /api/v1/streams/{name}``."""
        return dict(self._rest.get("/api/v1/streams/" + _segment(name)) or {})

    def declare_stream(
        self,
        name: str,
        schema: str,
        *,
        event_time: Optional[str] = None,
        out_of_orderness: Optional[str] = None,
    ) -> dict[str, Any]:
        """Declares a stream from ``name:TYPE,...``, with its event-time column and how late
        its rows may be (ISO-8601, such as ``"PT10S"``). An administrative act; the server
        refuses it to a principal who may not change what it serves. ``POST /api/v1/streams``."""
        body: dict[str, Any] = {"name": name, "schema": schema}
        if event_time:
            body["eventTime"] = event_time
        if out_of_orderness:
            body["outOfOrderness"] = out_of_orderness
        return dict(self._rest.post("/api/v1/streams", body) or {})

    def describe_view(self, name: str) -> dict[str, Any]:
        """A view's ``schema``, ``keyColumns``, ``retention``, ``sink`` and ``fingerprint``,
        without reading it. ``GET /api/v1/views/{name}``."""
        return dict(self._rest.get("/api/v1/views/" + _segment(name)) or {})

    # ------------------------------------------------------------------ queries

    def validate(self, sql: str) -> dict[str, Any]:
        """Plans ``sql`` without running it: ``valid``, ``diagnostics`` (each with ``code``,
        ``message``, ``helpUrl`` and, when the parser knew it, ``range``), ``outputFields``
        and ``elapsedMicros``. An invalid query is an answer, not an error.
        ``POST /api/v1/queries/validate``."""
        return dict(self._rest.post("/api/v1/queries/validate", {"sql": sql}) or {})

    def explain(self, sql: str, level: str = "physical", *, graph: bool = False) -> dict[str, Any]:
        """The plan, as ``plan`` text at ``level`` (``physical``, ``logical`` or ``codegen``),
        and with ``graph=True`` also as ``graph``: ``nodes`` and ``edges``.
        ``POST /api/v1/queries/explain``."""
        query = {"level": level, "format": "graph" if graph else "text"}
        return dict(self._rest.post("/api/v1/queries/explain", {"sql": sql}, query) or {})

    def describe_queries(self) -> "list[dict[str, Any]]":
        """Every registered query this principal may see, described in full: keys, retention,
        sink, rows in, the names sharing the computation, ``feed``, and where it runs --
        ``lane`` (``dedicated``, ``own`` or ``shared``) and ``sharedLane``.
        ``GET /api/v1/queries``."""
        return list(self._rest.get("/api/v1/queries") or [])

    def describe_query(self, name: str) -> dict[str, Any]:
        """One registered query, as :meth:`describe_queries` describes it.
        ``GET /api/v1/queries/{name}``."""
        return dict(self._rest.get("/api/v1/queries/" + _segment(name)) or {})

    def query_plan(self, name: str) -> dict[str, Any]:
        """The plan a registered query is running, as ``nodes`` and ``edges``, with the
        query-level numbers under ``query`` and, when the node measures them,
        ``operatorMetrics`` and the ``bottleneck``; ``metricsNote`` says why they are absent.
        ``GET /api/v1/queries/{name}/plan``."""
        return dict(self._rest.get("/api/v1/queries/" + _segment(name) + "/plan") or {})

    def dead_letters(self, name: str, *, offset: int = 0, limit: int = 50) -> dict[str, Any]:
        """A page of a query's dead letters, newest first, with the queue's totals.
        ``GET /api/v1/queries/{name}/dead-letters``."""
        return dict(
            self._rest.get(
                "/api/v1/queries/" + _segment(name) + "/dead-letters",
                {"offset": offset, "limit": limit},
            )
            or {}
        )

    def dead_letter_count(self, name: str) -> dict[str, Any]:
        """How deep a query's queue is, and what retention has taken, without the records.
        ``GET /api/v1/queries/{name}/dead-letters/count``."""
        return dict(self._rest.get("/api/v1/queries/" + _segment(name) + "/dead-letters/count") or {})

    def replay_dead_letters(self, name: str, ids: Sequence[str]) -> dict[str, Any]:
        """Feeds chosen dead letters back through the query: a new row at its current frontier,
        not a rewind, and not idempotent. ``POST /api/v1/queries/{name}/dead-letters/replay``."""
        return dict(
            self._rest.post(
                "/api/v1/queries/" + _segment(name) + "/dead-letters/replay", {"ids": list(ids)}
            )
            or {}
        )

    def replacement(self, name: str) -> dict[str, Any]:
        """The replacement of ``name``, with its ``history``. Refused with ``PRV-4017`` when
        the name is not being replaced. ``GET /api/v1/queries/{name}/replacement``."""
        return dict(self._rest.get("/api/v1/queries/" + _segment(name) + "/replacement") or {})

    def replacements(self) -> "list[dict[str, Any]]":
        """Every replacement this node knows about, in flight or finished.
        ``GET /api/v1/replacements``."""
        answer = self._rest.get("/api/v1/replacements")
        if isinstance(answer, dict):
            return _items(answer, "replacements")
        return list(answer or [])

    # ------------------------------------------------------------------ lanes

    def lanes(self) -> dict[str, Any]:
        """How the node places queries on execution lanes: ``mode``, ``autoFrom``,
        ``maxQueriesPerLane``, ``sharedLanes`` (each ``lane`` and its ``queries``),
        ``ownLaneQueries``, ``dedicatedQueries`` and ``hosted``. Which lane each query is on is
        :meth:`describe_queries`'s ``lane`` and ``sharedLane``. ``GET /api/v1/lanes``."""
        return dict(self._rest.get("/api/v1/lanes") or {})

    def lane_rebalance(self) -> dict[str, Any]:
        """The last or running rebalance: ``mode``, ``room``, ``running``, ``startedAt``,
        ``finishedAt``, ``startedBy`` and its ``moves`` (``name``, ``fromSharedLane``,
        ``status``, ``detail``). Administrator only. ``GET /api/v1/lanes/rebalance``."""
        return dict(self._rest.get("/api/v1/lanes/rebalance") or {})

    def rebalance_lanes(self, *, dry_run: bool = True) -> dict[str, Any]:
        """Moves queries off shared lanes onto lanes of their own while there is room, one at a
        time -- or, with ``dry_run=True`` (the default, because moving a query is not undone by
        asking again), says which it would move and moves nothing. Never automatic: this call is
        the only thing that starts one. Administrator only. ``POST /api/v1/lanes/rebalance``."""
        query = {"dryRun": "true"} if dry_run else None
        return dict(self._rest.post("/api/v1/lanes/rebalance", {}, query) or {})

    # ------------------------------------------------------------------ what a principal may do

    def permissions(self) -> dict[str, Any]:
        """What the node's policy lets this principal do: ``register``, ``readAudit``, and for
        each view and stream it may see how it may ``read`` it and whether it may
        ``administer`` it. ``GET /api/v1/me/permissions``."""
        return dict(self._rest.get("/api/v1/me/permissions") or {})

    def tenants(self) -> dict[str, Any]:
        """The admission quotas in force and each tenant's use against them: ``scope``,
        ``defaults`` and ``tenants``. ``GET /api/v1/tenants``."""
        return dict(self._rest.get("/api/v1/tenants") or {})

    def audit(
        self,
        *,
        since: Optional[str] = None,
        until: Optional[str] = None,
        principal: Optional[str] = None,
        view: Optional[str] = None,
        action: Optional[str] = None,
        decision: Optional[str] = None,
        limit: Optional[int] = None,
        cursor: Optional[str] = None,
    ) -> dict[str, Any]:
        """One page of the node's recorded authorization decisions, newest first: ``events``,
        ``nextCursor`` (pass back as ``cursor``; ``None`` on the last page), and what the
        readable window holds. ``since``/``until`` are ISO-8601 instants; ``decision`` is
        ``"allow"`` or ``"deny"``. A permission of its own: 403 otherwise.
        ``GET /api/v1/audit``."""
        query = {
            name: value
            for name, value in (
                ("since", since),
                ("until", until),
                ("principal", principal),
                ("view", view),
                ("action", action),
                ("decision", decision),
                ("limit", limit),
                ("cursor", cursor),
            )
            if value not in (None, "")
        }
        return dict(self._rest.get("/api/v1/audit", query or None) or {})

    # ------------------------------------------------------------------ identity (ADR-052)

    def login(self, username: str, password: str) -> dict[str, Any]:
        """Signs in: ``token`` (a session token, shown once), ``expiresAt``,
        ``mustChangePassword`` and ``mfa``. Send the token as the bearer token of later calls.
        ``POST /api/v1/auth/login``."""
        return dict(
            self._rest.post("/api/v1/auth/login", {"username": username, "password": password})
            or {}
        )

    def logout(self) -> None:
        """Ends the calling session: the token this client sends stops working.
        ``POST /api/v1/auth/logout``."""
        self._rest.post("/api/v1/auth/logout", {})

    def me(self) -> dict[str, Any]:
        """Who the caller is: ``username``, ``principal``, ``tenant``, ``roles``, ``via``
        (``session``, ``key`` or ``token``) and, for an account, its own fields.
        ``GET /api/v1/auth/me``."""
        return dict(self._rest.get("/api/v1/auth/me") or {})

    def change_password(self, current: str, new: str) -> None:
        """Changes the caller's own password; every other session of theirs ends.
        ``POST /api/v1/auth/password``."""
        self._rest.post("/api/v1/auth/password", {"current": current, "new": new})

    def redeem_reset(self, token: str, password: str) -> None:
        """Sets a password with a single-use reset token an administrator issued.
        ``POST /api/v1/auth/reset/redeem``."""
        self._rest.post("/api/v1/auth/reset/redeem", {"token": token, "password": password})

    def users(self) -> "list[dict[str, Any]]":
        """Every user (administrator). ``GET /api/v1/users``."""
        return _items(self._rest.get("/api/v1/users"), "users")

    def create_user(
        self,
        username: str,
        *,
        roles: Sequence[str],
        password: str,
        tenant: Optional[str] = None,
        email: Optional[str] = None,
        display_name: Optional[str] = None,
        service: bool = False,
    ) -> dict[str, Any]:
        """Creates a user (administrator). ``POST /api/v1/users``."""
        body: dict[str, Any] = {"username": username, "roles": list(roles), "password": password}
        if tenant:
            body["tenant"] = tenant
        if email:
            body["email"] = email
        if display_name:
            body["displayName"] = display_name
        body["service"] = bool(service)
        return dict(self._rest.post("/api/v1/users", body) or {})

    def update_user(
        self,
        username: str,
        *,
        status: Optional[str] = None,
        tenant: Optional[str] = None,
        email: Optional[str] = None,
        display_name: Optional[str] = None,
    ) -> dict[str, Any]:
        """Changes a user's ``status`` (``active`` or ``disabled``; disabling ends their
        sessions), tenant, email or display name (administrator).
        ``PATCH /api/v1/users/{username}``."""
        body = {
            key: value
            for key, value in (
                ("status", status),
                ("tenant", tenant),
                ("email", email),
                ("displayName", display_name),
            )
            if value is not None
        }
        return dict(self._rest.patch("/api/v1/users/" + _segment(username), body) or {})

    def set_roles(self, username: str, roles: Sequence[str]) -> dict[str, Any]:
        """Replaces a user's roles (administrator). ``PUT /api/v1/users/{username}/roles``."""
        return dict(
            self._rest.put("/api/v1/users/" + _segment(username) + "/roles", {"roles": list(roles)})
            or {}
        )

    def issue_password_reset(self, username: str) -> dict[str, Any]:
        """A single-use reset token for a user, shown once: ``resetToken`` and ``expiresAt``
        (administrator). ``POST /api/v1/users/{username}/password-reset``."""
        return dict(
            self._rest.post("/api/v1/users/" + _segment(username) + "/password-reset", {}) or {}
        )

    def keys(self, *, all_keys: bool = False) -> "list[dict[str, Any]]":
        """The caller's API keys, or with ``all_keys=True`` every key (administrator). Never a
        secret. ``GET /api/v1/keys``."""
        return _items(self._rest.get("/api/v1/keys", {"all": "true"} if all_keys else None), "keys")

    def create_key(
        self,
        name: str,
        *,
        roles: Optional[Sequence[str]] = None,
        expires_days: Optional[int] = None,
        for_user: Optional[str] = None,
    ) -> dict[str, Any]:
        """Issues an API key: ``key`` (shown once), ``keyId`` and ``expiresAt``. ``roles`` is a
        subset of the holder's; ``for_user`` issues one for a service account (administrator).
        ``POST /api/v1/keys``."""
        body: dict[str, Any] = {"name": name}
        if roles is not None:
            body["roles"] = list(roles)
        if expires_days is not None:
            body["expiresDays"] = int(expires_days)
        if for_user:
            body["forUser"] = for_user
        return dict(self._rest.post("/api/v1/keys", body) or {})

    def rotate_key(self, key_id: str) -> dict[str, Any]:
        """A successor with the same scope; the old key works until ``oldExpiresAt``.
        ``POST /api/v1/keys/{keyId}/rotate``."""
        return dict(self._rest.post("/api/v1/keys/" + _segment(key_id) + "/rotate", {}) or {})

    def revoke_key(self, key_id: str) -> None:
        """Revokes a key, at once. ``DELETE /api/v1/keys/{keyId}``."""
        self._rest.delete("/api/v1/keys/" + _segment(key_id))

    def key_report(self) -> dict[str, Any]:
        """Keys never used, expiring within 14 days, or superseded: ``unused``, ``expiring``,
        ``superseded`` (administrator). ``GET /api/v1/keys/report``."""
        return dict(self._rest.get("/api/v1/keys/report") or {})

    def sessions(self, *, all_sessions: bool = False) -> "list[dict[str, Any]]":
        """The caller's sessions, or everyone's with ``all_sessions=True`` (administrator); the
        caller's own is marked ``current``. ``GET /api/v1/sessions``."""
        return _items(
            self._rest.get("/api/v1/sessions", {"all": "true"} if all_sessions else None),
            "sessions",
        )

    def end_session(self, session_id: str) -> None:
        """Ends a session: the caller's own, or anybody's (administrator).
        ``DELETE /api/v1/sessions/{id}``."""
        self._rest.delete("/api/v1/sessions/" + _segment(session_id))

    # ------------------------------------------------------------------ the catalogue (ADR-059)

    def catalog_objects(
        self,
        *,
        namespace: Optional[str] = None,
        kind: Optional[str] = None,
        search: Optional[str] = None,
    ) -> "list[dict[str, Any]]":
        """The governed objects this principal may see: ``name`` (``tenant.namespace.object``),
        ``kind``, ``engineName``, ``namespace``, ``owner`` (``type``, ``name``), ``description``,
        ``tags``, ``version`` and who changed it when. ``search`` matches names, descriptions,
        owners and tags; a search never shows what the caller may not ``USE``.
        ``GET /api/v1/catalog/objects``."""
        query = {
            key: value
            for key, value in (("namespace", namespace), ("kind", kind), ("q", search))
            if value
        }
        return _items(self._rest.get("/api/v1/catalog/objects", query or None), "items")

    def catalog_search(self, text: str) -> "list[dict[str, Any]]":
        """Objects whose name, description, owner or a tag matches ``text``.
        ``GET /api/v1/catalog/objects?q=``."""
        return self.catalog_objects(search=text)

    def catalog_object(self, name: str) -> dict[str, Any]:
        """One object as ``object``, the ``grants`` on it this principal may see, and ``access``
        -- what this principal may do to it, privilege by privilege. ``name`` is a full name or
        as typed (``sales.revenue``, ``revenue``). ``GET /api/v1/catalog/objects/{name}``."""
        return dict(self._rest.get("/api/v1/catalog/objects/" + _segment(name)) or {})

    def catalog_namespaces(self) -> "list[dict[str, Any]]":
        """The namespaces this principal may use. ``GET /api/v1/catalog/namespaces``."""
        return _items(self._rest.get("/api/v1/catalog/namespaces"), "items")

    def create_namespace(
        self, name: str, *, description: str = "", if_not_exists: bool = False
    ) -> dict[str, Any]:
        """Creates a namespace, owned by the caller; needs ``CREATE`` on the tenant.
        ``POST /api/v1/catalog/namespaces``."""
        body = {"name": name, "description": description, "ifNotExists": if_not_exists}
        return dict(self._rest.post("/api/v1/catalog/namespaces", body) or {})

    def change_catalog_object(
        self,
        name: str,
        *,
        description: Optional[str] = None,
        set_tags: "Optional[dict[str, str]]" = None,
        unset_tags: Optional[Sequence[str]] = None,
        owner: "Optional[tuple[str, str]]" = None,
        namespace: Optional[str] = None,
    ) -> dict[str, Any]:
        """Changes an object's description or tags (``MANAGE``), its owner (``(type, name)``;
        ownership), or moves a view into ``namespace``. ``PATCH /api/v1/catalog/objects/{name}``."""
        body: dict[str, Any] = {}
        if description is not None:
            body["description"] = description
        if set_tags:
            body["setTags"] = dict(set_tags)
        if unset_tags:
            body["unsetTags"] = list(unset_tags)
        if owner is not None:
            body["owner"] = {"type": owner[0], "name": owner[1]}
        if namespace:
            body["namespace"] = namespace
        return dict(self._rest.patch("/api/v1/catalog/objects/" + _segment(name), body) or {})

    def grants(
        self,
        *,
        on: Optional[str] = None,
        grantee_type: Optional[str] = None,
        grantee: Optional[str] = None,
    ) -> "list[dict[str, Any]]":
        """Grants on an object (``on``), or to a role or user: ``object``, ``privilege``,
        ``granteeType``, ``grantee``, ``grantedBy``, ``grantedAt``. ``GET /api/v1/catalog/grants``."""
        query: dict[str, Any] = {}
        if on:
            query["object"] = on
        if grantee:
            query["granteeType"] = grantee_type or "USER"
            query["grantee"] = grantee
        return _items(self._rest.get("/api/v1/catalog/grants", query or None), "items")

    def grant(
        self, on: str, privileges: Sequence[str], grantee_type: str, grantee: str
    ) -> "list[dict[str, Any]]":
        """Grants ``privileges`` (``ALL`` or none for every one that applies) on ``on`` to a
        ``ROLE`` or ``USER``; needs ``MANAGE``. ``POST /api/v1/catalog/grants``."""
        body = {
            "object": on,
            "privileges": list(privileges),
            "granteeType": grantee_type,
            "grantee": grantee,
        }
        return _items(self._rest.post("/api/v1/catalog/grants", body), "items")

    def revoke(self, on: str, privileges: Sequence[str], grantee_type: str, grantee: str) -> None:
        """Revokes ``privileges`` on ``on`` from a ``ROLE`` or ``USER``; needs ``MANAGE``.
        ``DELETE /api/v1/catalog/grants``."""
        query = {
            "object": on,
            "privileges": ",".join(privileges),
            "granteeType": grantee_type,
            "grantee": grantee,
        }
        self._rest.delete("/api/v1/catalog/grants", query)

    def access(self, user: str, on: str) -> dict[str, Any]:
        """What ``user`` may do to ``on``, privilege by privilege: ``allowed``, and ``via`` --
        which grant, through which role, namespace or ownership -- or the ``refusal``. Asked for
        oneself, or by a manager of the object. ``GET /api/v1/catalog/access``."""
        return dict(self._rest.get("/api/v1/catalog/access", {"user": user, "object": on}) or {})

    # ------------------------------------------------------------------ alerts (ADR-057)

    def alerts(self) -> "list[dict[str, Any]]":
        """The alerts this principal may see: ``name``, ``view``, ``state`` (ACTIVE, PAUSED,
        SNOOZED), ``following``, ``condition``, ``channels``, ``severity``, ``options``,
        ``firing`` and ``pending`` key counts, ``lastNotificationAt`` and any ``deliveryError``.
        ``GET /api/v1/alerts``."""
        return _items(self._rest.get("/api/v1/alerts"), "items")

    def alert(self, name: str) -> dict[str, Any]:
        """One alert as ``alert``, every key it holds as ``keys`` (``state`` PENDING, FIRING,
        CLEARING or CLEARED, ``episode``, what was last ``notified`` and what is ``owed``), and its
        recent ``notifications``, newest first. ``GET /api/v1/alerts/{name}``."""
        return dict(self._rest.get("/api/v1/alerts/" + _segment(name)) or {})

    def alert_channels(self) -> "list[dict[str, Any]]":
        """The notifier channels the node binds (``pravaha.notifiers.*``): ``name`` and ``plugin``.
        ``GET /api/v1/alerts/channels``."""
        return _items(self._rest.get("/api/v1/alerts/channels"), "items")

    def pause_alert(self, name: str) -> dict[str, Any]:
        """Pauses an alert: it keeps following its view and says nothing until resumed (MODIFY).
        ``POST /api/v1/alerts/{name}/pause``."""
        return dict(self._rest.post("/api/v1/alerts/" + _segment(name) + "/pause", None) or {})

    def resume_alert(self, name: str) -> dict[str, Any]:
        """Resumes an alert, ending a pause or a snooze; what changed meanwhile is sent (MODIFY).
        ``POST /api/v1/alerts/{name}/resume``."""
        return dict(self._rest.post("/api/v1/alerts/" + _segment(name) + "/resume", None) or {})

    def snooze_alert(self, name: str, duration: str) -> dict[str, Any]:
        """Snoozes an alert for ``duration`` (``30m``, ``2h``, ``PT2H``); what changed meanwhile is
        sent when it ends (MODIFY). ``POST /api/v1/alerts/{name}/snooze``."""
        return dict(
            self._rest.post("/api/v1/alerts/" + _segment(name) + "/snooze", {"duration": duration}) or {}
        )

    def ack_alert(self, name: str, key: Optional[str] = None) -> dict[str, Any]:
        """Acknowledges an alert's firing keys, or the one ``key`` names as the alert shows it
        (``sku=sku-100, warehouse=LDN``); an acknowledged key is not reminded about until it fires
        again (MODIFY). Answers ``acknowledged``, how many. ``POST /api/v1/alerts/{name}/ack``."""
        body = {"key": key} if key else {}
        return dict(self._rest.post("/api/v1/alerts/" + _segment(name) + "/ack", body) or {})


__all__ = ["EngineApi"]
