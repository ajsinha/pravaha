"""The console's only way of reaching the engine: the published SDK and the public REST API.

Every Flight call in here goes through ``pravaha``, the same client an integrator uses. That is
the point of ADR-024 rather than an incidental choice -- a console that reached into the
engine would be a console whose API boundary is enforced by a test, and a test can be
waived by whoever is under deadline pressure that week. A separate process simply cannot.

The second benefit is that the console is the SDK's first real consumer: an awkward
corner of the client API becomes an awkward corner of the console, where somebody
notices, instead of being discovered by an integrator.

The handful of HTTP calls at the bottom are to the engine's *published* REST surface --
``/api/v1/streams``, ``/api/v1/queries/validate``, ``/api/v1/queries/explain``,
``/api/v1/status`` and the Prometheus endpoint -- the same documented, versioned endpoints a
third party calls (design 23.2a). The Python SDK speaks Flight only, so they live here, in
the one adapter that would be replaced when the SDK grows them.
"""
from __future__ import annotations

import dataclasses
import json
import threading
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterator, Sequence

from pravaha import connect
from pravaha.client import QueryError


@dataclasses.dataclass(frozen=True)
class QueryRow:
    """One registered continuous query, as the console shows it."""

    name: str
    state: str
    sql: str
    fingerprint: str
    rows_in: int

    @property
    def shared(self) -> bool:
        """Whether some other name shares this computation.

        Filled in by :meth:`Engine.queries`, which can see the whole list. Worth surfacing
        because "ten analysts on one dashboard cost one query" is the claim, and an
        operator should be able to see it holding.
        """
        return getattr(self, "_shared", False)


class EngineHttpError(Exception):
    """The engine's HTTP API refused (``status`` is its HTTP status), or did not answer (0)."""

    def __init__(self, status: int, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code


class Engine:
    """A connection to one Pravaha server.

    Not a connection pool. The console is an operator tool with one server in view, and a
    pool would add a failure mode nobody is watching for the sake of concurrency nobody
    needs at this scale.
    """

    def __init__(self, url: str, token: str | None = None, http_url: str | None = None,
                 http_timeout: float = 5.0) -> None:
        self._url = url
        self._token = token
        self._lock = threading.Lock()
        # The engine's HTTP surface is a separate port from Flight -- 8080 against 9090 -- and
        # conflating the two is the commonest way a first run fails, so it is its own setting.
        self._http = (http_url or "").rstrip("/")
        self._http_timeout = http_timeout

    @property
    def url(self) -> str:
        return self._url

    @property
    def http_url(self) -> str:
        return self._http

    def _client(self):
        if self._token:
            from pravaha.options import ClientOptions

            return connect(
                options=ClientOptions.create(
                    self._url, token=self._token, allow_insecure_token=True
                )
            )
        return connect(self._url)

    def health(self) -> dict:
        """Whether the engine answers at all, and what it is running."""
        try:
            with self._client() as client:
                queries = client.queries()
            return {"reachable": True, "url": self._url, "queries": len(queries)}
        except Exception as exc:  # noqa: BLE001 -- deliberate: see below
            # Reported rather than raised: a console whose own page 500s when the engine is
            # down is a console that cannot tell you the engine is down.
            return {"reachable": False, "url": self._url, "error": str(exc)}

    def queries(self) -> list[QueryRow]:
        with self._client() as client:
            registered = client.queries()

        counts: dict[str, int] = {}
        for query in registered:
            counts[query.fingerprint] = counts.get(query.fingerprint, 0) + 1

        rows = []
        for query in registered:
            row = QueryRow(
                name=query.name,
                state=query.state,
                sql=query.sql,
                fingerprint=query.fingerprint,
                rows_in=query.rows_in,
            )
            # Two names on one fingerprint are one computation with one copy of the state.
            object.__setattr__(row, "_shared", counts[query.fingerprint] > 1)
            rows.append(row)
        return rows

    def register(self, name: str, sql: str, keys: Sequence[int],
                 sink: str | None = None) -> QueryRow:
        with self._client() as client:
            registered = client.register(name, sql, list(keys), sink=sink or None)
        return QueryRow(
            name=registered.name,
            state=registered.state,
            sql=sql,
            fingerprint=registered.fingerprint,
            rows_in=0,
        )

    def lifecycle(self, action: str, name: str) -> None:
        with self._client() as client:
            if action == "pause":
                client.pause(name)
            elif action == "resume":
                client.resume(name)
            elif action == "drop":
                client.drop(name)
            else:
                raise QueryError(f"unknown action '{action}'")

    def query(self, sql: str, parameters: Sequence[object] | None = None) -> tuple[list[str], list[list]]:
        columns, rows, _types = self.query_typed(sql, parameters)
        return columns, rows

    def query_typed(self, sql: str, parameters: Sequence[object] | None = None
                    ) -> tuple[list[str], list[list], list[str]]:
        """The answer, plus each column's Arrow type -- which the result grid aligns by."""
        with self._client() as client:
            result = client.query(sql, list(parameters) if parameters else None)
            types = [str(field.type) for field in result.schema]
            rows = [[row[name] for name in result.columns] for row in result]
            return list(result.columns), rows, types

    def tail(self, view: str, filters: dict | None = None) -> Iterator[dict]:
        """Yields one dict per changed row, for as long as the caller keeps reading.

        Held open deliberately: a console that polled would show an operator a number that
        is always a little out of date, and the whole product claim is that it does not
        have to be.

        Each row carries ``_weight``: +1 appearing, -1 withdrawn. A retraction without its
        sign reads as a second copy of the row it withdraws, which is the one mistake a live
        view must not invite.
        """
        with self._client() as client:
            for batch in client.subscribe(view, filters or {}):
                for row in batch:
                    values = {name: row[name] for name in row.columns}
                    values["_weight"] = row.weight
                    yield values

    # ------------------------------------------------------------------ public REST

    def _http_call(self, method: str, path: str, body: object | None = None,
                   accept: str = "application/json") -> bytes:
        if not self._http:
            raise EngineHttpError(0, "no engine HTTP URL is configured (engine.http_url)")
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self._http + path, data=data, method=method)
        request.add_header("Accept", accept)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self._token:
            # Sent to the engine, never to a browser: the token is the console's service
            # credential and stays on this side of the process boundary.
            request.add_header("Authorization", "Bearer " + self._token)
        try:
            with urllib.request.urlopen(request, timeout=self._http_timeout) as response:
                return response.read()
        except urllib.error.HTTPError as exc:
            payload = exc.read()
            raise EngineHttpError(exc.code, _message_of(payload) or str(exc),
                                  _code_of(payload)) from exc
        except (urllib.error.URLError, OSError) as exc:
            reason = getattr(exc, "reason", exc)
            raise EngineHttpError(
                0, f"the engine's HTTP API at {self._http} did not answer: {reason}") from exc

    def _json(self, method: str, path: str, body: object | None = None):
        payload = self._http_call(method, path, body)
        return json.loads(payload.decode("utf-8") or "null")

    def streams(self) -> list[dict]:
        """``GET /api/v1/streams``: every stream this principal may read, with its fields."""
        return list(self._json("GET", "/api/v1/streams") or [])

    def declare_stream(self, name: str, schema: str) -> dict:
        """``POST /api/v1/streams``: a schema in the ``name:TYPE,...`` form configuration uses."""
        return dict(self._json("POST", "/api/v1/streams", {"name": name, "schema": schema}) or {})

    def validate(self, sql: str) -> dict:
        """``POST /api/v1/queries/validate``: valid, diagnostics, output fields, elapsed."""
        return dict(self._json("POST", "/api/v1/queries/validate", {"sql": sql}) or {})

    def explain(self, sql: str, level: str = "physical") -> dict:
        """``POST /api/v1/queries/explain``: the plan as indented text, and its output schema."""
        query = urllib.parse.urlencode({"level": level})
        return dict(self._json("POST", f"/api/v1/queries/explain?{query}", {"sql": sql}) or {})

    def status(self) -> dict:
        """``GET /api/v1/status``: node, version, engine state, plugin health."""
        return dict(self._json("GET", "/api/v1/status") or {})

    def prometheus(self) -> str:
        """``GET /actuator/prometheus``: the text exposition format, unparsed."""
        return self._http_call("GET", "/actuator/prometheus", accept="text/plain").decode(
            "utf-8", errors="replace")


def _decoded(payload: bytes):
    try:
        return json.loads(payload.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None


def _message_of(payload: bytes) -> str:
    body = _decoded(payload)
    if isinstance(body, dict):
        return str(body.get("message") or body.get("error") or "")
    return payload.decode("utf-8", errors="replace")[:500]


def _code_of(payload: bytes) -> str | None:
    body = _decoded(payload)
    if isinstance(body, dict) and body.get("code"):
        return str(body["code"])
    return None
