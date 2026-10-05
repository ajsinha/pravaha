"""Live views: one engine subscription per view, fanned out to every browser watching.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Split out of ``core.services`` (CONSOLESIZE-1), which re-exports every name here.
Why it is built this way is in that module's docstring.
"""
from __future__ import annotations

import queue
import threading
from collections.abc import Callable, Iterator

from core import credential
from core.engine import Engine


class Broadcaster:
    """One engine subscription per view, fanned out to every browser watching it.

    This is the piece that makes the UI's load independent of how many people have it
    open. Twenty analysts on one dashboard are twenty browser connections here and *one*
    subscription to the engine. Without it the UI would multiply engine load by the number
    of open tabs, which would contradict the product's own claim that one question costs
    one computation however many people ask it.

    Ref-counted, like the registry's own sharing: the upstream subscription starts with
    the first subscriber and stops when the last one leaves. Stopping on the first
    departure would take the feed away from everyone else, who have no idea the first
    subscriber existed.

    **Every browser starts from the view, not from a read beside the stream (SUB-1).** The
    live page used to read the view and then open the stream, and a commit landing between
    the two reached it by neither path. The engine subscription is now a snapshot one, the
    feed keeps the view it describes (the snapshot plus every commit, as a Z-set), and a
    browser attaching is handed that state and then every change after it, in one step
    under the feed's lock -- the same handoff the engine makes, one level up. The cost is
    one copy of each watched view in this process, bounded by the view's own ceiling.
    """

    #: Rows buffered per browser before the oldest are dropped.
    BUFFER = 256

    def __init__(self, engine: Engine, snapshot_rows: int = 500) -> None:
        #: At most this many of the view's rows are sent to a browser as its starting point;
        #: the page says when it was shown the first N rather than all of them.
        self.snapshot_rows = snapshot_rows
        self._engine = engine
        self._lock = threading.Lock()
        self._feeds: dict[str, _Feed] = {}

    def subscribe(self, view: str, filters: dict | None = None) -> Subscriber:
        key = view if not filters else view + "?" + "&".join(f"{k}={v}" for k, v in sorted(filters.items()))
        # One feed per person as well as per view (ADR-052): the engine filters what each
        # principal may read of a view, so a subscription opened with one person's session is
        # never handed to another. Two tabs of the same person still share one.
        held = credential.current()
        key = (held.scope() if held is not None else "-") + "|" + key
        with self._lock:
            feed = self._feeds.get(key)
            if feed is None:
                feed = _Feed(self._engine, view, filters or {}, lambda: self._release(key),
                             owner=credential.Credential(held.token) if held is not None else None)
                self._feeds[key] = feed
            return feed.attach()

    def _release(self, key: str) -> None:
        with self._lock:
            self._feeds.pop(key, None)

    def live_feeds(self) -> int:
        """How many engine subscriptions are open. For the overview, and for a test."""
        with self._lock:
            return len(self._feeds)


class Subscriber:
    """One browser's view of a feed, with a bounded buffer."""

    def __init__(self, feed: _Feed) -> None:
        self._feed = feed
        self._queue: queue.Queue = queue.Queue(maxsize=Broadcaster.BUFFER)
        self.dropped = 0
        self.closed = False
        self._snapshot: tuple[list, int | None] | None = None

    def start_from(self, rows: list, frontier: int | None) -> None:
        """The view this browser starts from. Set under the feed's lock, before any row after it."""
        self._snapshot = (rows, frontier)

    def take_snapshot(self) -> tuple[list, int | None] | None:
        """The starting view once it is known, handed over once; None until then.

        A caller sends it before it drains a single row: the rows in the queue are the
        changes after it, and only after it.
        """
        taken, self._snapshot = self._snapshot, None
        return taken

    def failure(self) -> str | None:
        """Why the feed behind this subscriber stopped, if it did -- before or after its snapshot."""
        return self._feed.error

    def offer(self, row: dict) -> None:
        try:
            self._queue.put_nowait(row)
        except queue.Full:
            # Drop the oldest, keep the newest. A live view is about what is true now, so
            # the row the reader has not caught up to matters less than the one that just
            # arrived -- and blocking here would push back on the engine's own subscriber.
            try:
                self._queue.get_nowait()
                self._queue.put_nowait(row)
            except (queue.Empty, queue.Full):
                pass
            self.dropped += 1

    def rows(self, timeout: float = 0.5) -> Iterator[dict]:
        """Yields buffered rows, returning when there are none rather than blocking forever."""
        while not self.closed:
            try:
                yield self._queue.get(timeout=timeout)
            except queue.Empty:
                return

    def drain(self, limit: int = 256) -> list:
        """Takes whatever is buffered right now, without waiting for more.

        The non-blocking form exists because the SSE endpoint must stay able to notice that
        the browser has gone. A generator blocked on a queue cannot check for that, and a
        synchronous one running in a thread pool cannot be cancelled into noticing either --
        so it would keep its engine subscription open for a tab that closed an hour ago.
        """
        rows: list = []
        while len(rows) < limit:
            try:
                rows.append(self._queue.get_nowait())
            except queue.Empty:
                break
        return rows

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            self._feed.detach(self)


class _Feed:
    """The engine-side half: one snapshot subscription, the view it describes, many subscribers.

    ``_state`` is the view as the engine's snapshot and every commit since leave it, a
    Z-set of rows. Applying a commit to it and offering that commit to the subscribers is
    one step under ``_lock``, and so is a subscriber attaching and being handed a copy of
    it: whatever the copy lacks, the subscriber is offered, and nothing twice.
    """

    def __init__(self, engine: Engine, view: str, filters: dict, on_empty: Callable[[], None],
                 owner: credential.Credential | None = None) -> None:
        self._engine = engine
        #: Whose session the upstream subscription is opened with. The pump runs on a thread of
        #: its own, which does not inherit the request's context, so it carries the credential.
        self._owner = owner
        self._view = view
        self._filters = filters
        self._on_empty = on_empty
        self._lock = threading.Lock()
        self._subscribers: list[Subscriber] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.error: str | None = None
        self._state: dict = {}                   # identity -> [row without _weight, weight]
        self._frontier: int | None = None
        self._ready = False                      # the engine's snapshot has arrived

    def attach(self) -> Subscriber:
        subscriber = Subscriber(self)
        with self._lock:
            self._subscribers.append(subscriber)
            first = len(self._subscribers) == 1
            if self._ready:
                subscriber.start_from(self._rows(), self._frontier)
        if first:
            self._thread = threading.Thread(target=self._pump, name=f"feed-{self._view}", daemon=True)
            self._thread.start()
        return subscriber

    def detach(self, subscriber: Subscriber) -> None:
        with self._lock:
            if subscriber in self._subscribers:
                self._subscribers.remove(subscriber)
            last = not self._subscribers
        if last:
            # The upstream subscription is released when the last browser goes, which is
            # what stops a closed tab leaving a subscription open on the engine for ever.
            self._stop.set()
            self._on_empty()

    @staticmethod
    def _identity(row: dict) -> tuple:
        return tuple((name, repr(value)) for name, value in row.items() if name != "_weight")

    def _apply(self, rows: list) -> None:
        """Adds rows to the state by weight. Under the lock."""
        for row in rows:
            key = self._identity(row)
            entry = self._state.get(key)
            weight = int(row.get("_weight", 1))
            if entry is None:
                entry = self._state[key] = [{k: v for k, v in row.items() if k != "_weight"}, 0]
            entry[1] += weight
            if entry[1] == 0:
                del self._state[key]

    def _rows(self) -> list:
        """The state as rows, each with its weight. Under the lock."""
        return [dict(row, _weight=weight) for row, weight in self._state.values()]

    def _pump(self) -> None:
        with credential.bound(self._owner):
            self._pump_as_owner()

    def _pump_as_owner(self) -> None:
        try:
            for kind, rows, frontier in self._engine.mirror(self._view, self._filters):
                if self._stop.is_set():
                    return
                with self._lock:
                    if kind == "snapshot":
                        self._state = {}
                        self._apply(rows)
                        self._frontier = frontier
                        self._ready = True
                        starting = self._rows()
                        for subscriber in self._subscribers:
                            subscriber.start_from(list(starting), frontier)
                        continue
                    self._apply(rows)
                    self._frontier = frontier
                    # Offered under the lock that attach() takes, so a browser attaching now
                    # either has this commit in its starting view or is offered it -- never
                    # both, never neither. offer() does not block.
                    for subscriber in self._subscribers:
                        for row in rows:
                            subscriber.offer(row)
        except Exception as exc:  # noqa: BLE001
            # Recorded and delivered to the browsers rather than dying silently: a live
            # tail that simply stops looks exactly like a stream with nothing in it.
            self.error = str(exc)
            with self._lock:
                targets = list(self._subscribers)
            for subscriber in targets:
                subscriber.offer({"_error": self.error})
