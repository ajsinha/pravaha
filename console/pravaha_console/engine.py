"""The console's only way of reaching the engine: the published SDK.

Every call in here goes through ``pravaha``, the same client an integrator uses. That is
the point of ADR-024 rather than an incidental choice -- a console that reached into the
engine would be a console whose API boundary is enforced by a test, and a test can be
waived by whoever is under deadline pressure that week. A separate process simply cannot.

The second benefit is that the console is the SDK's first real consumer: an awkward
corner of the client API becomes an awkward corner of the console, where somebody
notices, instead of being discovered by an integrator.
"""
from __future__ import annotations

import dataclasses
import threading
from typing import Iterator, Optional, Sequence

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


class Engine:
    """A connection to one Pravaha server.

    Not a connection pool. The console is an operator tool with one server in view, and a
    pool would add a failure mode nobody is watching for the sake of concurrency nobody
    needs at this scale.
    """

    def __init__(self, url: str, token: Optional[str] = None) -> None:
        self._url = url
        self._token = token
        self._lock = threading.Lock()

    @property
    def url(self) -> str:
        return self._url

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
        except Exception as exc:
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

    def register(self, name: str, sql: str, keys: Sequence[int]) -> QueryRow:
        with self._client() as client:
            registered = client.register(name, sql, list(keys))
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

    def query(self, sql: str, parameters: Optional[Sequence[object]] = None) -> tuple[list[str], list[list]]:
        with self._client() as client:
            result = client.query(sql, list(parameters) if parameters else None)
            rows = [[row[name] for name in result.columns] for row in result]
            return list(result.columns), rows

    def tail(self, view: str, filters: Optional[dict] = None) -> Iterator[dict]:
        """Yields one dict per changed row, for as long as the caller keeps reading.

        Held open deliberately: a console that polled would show an operator a number that
        is always a little out of date, and the whole product claim is that it does not
        have to be.
        """
        with self._client() as client:
            for batch in client.subscribe(view, filters or {}):
                for row in batch:
                    yield {name: row[name] for name in row.columns}
