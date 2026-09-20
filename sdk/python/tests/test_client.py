"""The Python SDK against a real Pravaha server.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A Python fake would test the fake. So this starts the actual Java server -- the same
one an application connects to -- and drives it with the same client an application
would use. If the wire format or the Flight SQL command encoding is wrong, it fails
here rather than in somebody's notebook.

Skipped when the server's classes have not been built, so ``pytest`` on a fresh clone
does not fail for a reason that has nothing to do with Python.
"""

from __future__ import annotations

import os
import pathlib
import subprocess
import threading
import time

import pytest

pyarrow = pytest.importorskip("pyarrow", reason="the transport needs the 'flight' extra")

from pravaha import connect  # noqa: E402
from pravaha.client import QueryError, ReadError, _weighted_rows_of  # noqa: E402

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
FLIGHT_CLASSES = REPO_ROOT / "pravaha-flight" / "target" / "test-classes"


def _classpath() -> str:
    """The server's classpath, written by the Maven build."""
    entries = [str(FLIGHT_CLASSES), str(REPO_ROOT / "pravaha-flight" / "target" / "classes")]
    written = REPO_ROOT / "pravaha-flight" / "target" / "test-classpath.txt"
    if written.exists():
        entries.append(written.read_text().strip())
    return os.pathsep.join(entries)


@pytest.fixture(scope="module")
def server():
    if not FLIGHT_CLASSES.exists():
        pytest.skip("pravaha-flight is not built; run ./mvnw -pl pravaha-flight test-compile")
    java = os.environ.get("JAVA_HOME", "")
    java_bin = str(pathlib.Path(java) / "bin" / "java") if java else "java"

    process = subprocess.Popen(
        [
            java_bin,
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-cp",
            _classpath(),
            "com.ash.messaging.pravaha.flight.TestFlightServerMain",
            "0",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

    # Drained on a thread for the whole run, not read until the port appears and then abandoned.
    # A pipe nobody reads fills, and the next thing the server writes blocks it for ever -- which
    # looks exactly like a subscription that never delivers, and cost an afternoon to tell apart.
    found = {}
    drained = []

    def drain():
        # readline, not iteration. Iterating a pipe uses read-ahead buffering and blocks until the
        # buffer fills, so the port line sat unread for ninety seconds and every streaming test
        # skipped with "the server did not start" -- a green run that had tested nothing.
        while True:
            line = process.stdout.readline()
            if not line:
                return
            drained.append(line)
            if line.startswith("PRAVAHA_FLIGHT_PORT="):
                found["port"] = int(line.strip().split("=", 1)[1])
            elif line.startswith("PRAVAHA_FEED_FILE="):
                found["feed"] = line.strip().split("=", 1)[1]

    reader = threading.Thread(target=drain, daemon=True)
    reader.start()

    # Both markers, not just the port. Waiting for one and then reading the other races the
    # draining thread, and the loser is a feed fixture that skips -- so every streaming test
    # reported "skipped" and the run was green.
    deadline = time.time() + 90
    while time.time() < deadline and not ("port" in found and "feed" in found):
        time.sleep(0.05)
    port = found.get("port")
    feed_file = found.get("feed")
    if port is None:
        process.kill()
        pytest.skip("the Pravaha server did not start; is the module built?")

    yield port, feed_file
    process.kill()
    process.wait(timeout=30)


@pytest.fixture
def client(server):
    port, _ = server
    # "grpc://" is plaintext. Omitting the scheme means TLS, which is the right default
    # for a client and the reason this is spelled out.
    with connect(f"grpc://localhost:{port}") as connected:
        yield connected


@pytest.fixture
def feed(server):
    """Appends rows to the stream the fixture server is tailing.

    This is what makes a streaming test possible from Python at all. Without it a subscription
    could be opened and never delivered anything, so the tests asserted the shape of the call --
    that a ticket was built and the server accepted it -- and called that a subscription test.
    """
    _, feed_file = server
    if feed_file is None:
        pytest.skip("the server did not report a feed file; rebuild pravaha-flight's test classes")

    def append(trade_id, product_type, payload="{}", weight=1):
        with open(feed_file, "a", encoding="utf-8") as handle:
            handle.write(f"{trade_id},{product_type},{payload},{weight}\n")
            handle.flush()

    return append


def test_a_script_queries_and_iterates(client):
    rows = [(row["user_id"], row["total"]) for row in client.query(
        "SELECT user_id, total FROM user_volume WHERE total > 40"
    )]

    assert sorted(rows) == [("u1", 300), ("u2", 50)]


def test_columns_are_known_before_the_first_row(client):
    result = client.query("SELECT user_id, tier, total FROM user_volume")

    assert result.columns == ["user_id", "tier", "total"]


def test_a_null_column_is_none_rather_than_empty(client):
    # A tier that is absent must arrive absent. An empty string would be a value the
    # view does not hold, and every `is None` check downstream would be wrong.
    rows = list(client.query("SELECT tier FROM user_volume WHERE user_id = 'u3'"))

    assert len(rows) == 1
    assert rows[0]["tier"] is None
    assert rows[0].is_null("tier")


def test_a_row_is_indexable_by_name_and_position(client):
    row = next(iter(client.query("SELECT user_id, total FROM user_volume WHERE user_id = 'u1'")))

    assert row["user_id"] == row[0] == "u1"
    assert row["total"] == row[1] == 300
    assert row.to_dict() == {"user_id": "u1", "total": 300}


def test_an_unknown_column_names_what_is_there(client):
    row = next(iter(client.query("SELECT user_id FROM user_volume")))

    with pytest.raises(KeyError, match="user_id"):
        _ = row["nope"]


def test_an_aggregate_comes_back(client):
    rows = list(client.query("SELECT SUM(total) FROM user_volume"))

    assert rows[0][0] == 357


def test_an_empty_result_is_empty_rather_than_hanging(client):
    assert list(client.query("SELECT user_id FROM user_volume WHERE total > 99999")) == []


def test_a_refused_query_carries_the_servers_diagnosis(client):
    # "PRV-4023 ... this server serves ['user_volume']" is actionable; "query failed"
    # is not.
    with pytest.raises(QueryError, match="nowhere"):
        client.query("SELECT * FROM nowhere")


def test_a_result_spanning_many_batches_iterates_straight_through(client):
    rows = list(client.query("SELECT id, reading FROM readings"))

    assert len(rows) == 5000
    assert rows[0]["reading"] == 0.0


def test_to_table_gives_arrow_and_therefore_pandas(client):
    # The reason the wire format is Arrow: no conversion between the answer and the
    # tools Python people actually use.
    table = client.query("SELECT user_id, total FROM user_volume").to_table()

    assert table.num_rows == 3
    assert table.column_names == ["user_id", "total"]
    assert sorted(table.column("total").to_pylist()) == [7, 50, 300]


def test_reading_a_result_twice_is_refused_rather_than_silently_empty(client):
    result = client.query("SELECT user_id FROM user_volume")
    assert len(list(result)) == 3

    with pytest.raises(ReadError, match="already been read"):
        list(result)


def test_a_parameter_binds_rather_than_being_interpolated(client):
    rows = [row["total"] for row in client.query(
        "SELECT total FROM user_volume WHERE user_id = ?", ["u1"]
    )]

    assert rows == [300]


def test_one_statement_answers_different_questions(client):
    first = [row["total"] for row in client.query(
        "SELECT total FROM user_volume WHERE user_id = ?", ["u1"]
    )]
    second = [row["total"] for row in client.query(
        "SELECT total FROM user_volume WHERE user_id = ?", ["u2"]
    )]

    assert first == [300]
    assert second == [50]


def test_a_numeric_parameter_binds(client):
    rows = sorted(row["user_id"] for row in client.query(
        "SELECT user_id FROM user_volume WHERE total > ?", [40]
    ))

    assert rows == ["u1", "u2"]


def test_a_value_that_looks_like_sql_is_a_value(client):
    # Never escaped, because never parsed: by the time this reaches the server the
    # statement is planned and there is no parser left for it to reach.
    rows = list(client.query(
        "SELECT user_id FROM user_volume WHERE user_id = ?", ["u1' OR '1'='1"]
    ))

    assert rows == []


def test_binding_none_matches_nothing_rather_than_everything(client):
    # `tier = NULL` is UNKNOWN for every row, u3's included. Three-valued logic, not a bug.
    rows = list(client.query("SELECT user_id FROM user_volume WHERE tier = ?", [None]))

    assert rows == []


def test_the_wrong_number_of_values_is_refused_before_the_call(client):
    with pytest.raises(ValueError) as refused:
        client.query("SELECT user_id FROM user_volume WHERE user_id = ? AND total > ?", ["u1"])

    assert "placeholder" in str(refused.value)


def test_a_value_of_the_wrong_type_names_the_placeholder(client):
    with pytest.raises(ValueError) as refused:
        client.query("SELECT user_id FROM user_volume WHERE total > ?", ["not a number"])

    assert "?1" in str(refused.value)


# --- Continuous queries: registering them, and subscribing to what they produce -----------

TRADE_SQL = "SELECT trade_id, product_type, trade_json FROM trade"


def test_a_continuous_query_can_be_registered_and_listed(client):
    registered = client.register("py_feed", TRADE_SQL, [0])
    try:
        assert registered.name == "py_feed"
        assert registered.is_running
        # The fingerprint identifies the computation, not the name. Two names sharing one is
        # one copy of the state, which is the whole reason it is reported.
        assert registered.fingerprint

        names = [q.name for q in client.queries()]
        assert "py_feed" in names
    finally:
        client.drop("py_feed")


def test_a_healthy_query_has_no_stopped_source(client):
    client.register("py_fed", TRADE_SQL, [0])
    try:
        listed = [q for q in client.queries() if q.name == "py_fed"][0]
        # Nothing of the server's own is bound to this query: the fixture pushes its rows.
        assert listed.feed == "NONE"
        assert not listed.is_source_stopped
        assert listed.feed_stop is None
    finally:
        client.drop("py_fed")


def test_a_stopped_source_is_listed_with_its_code_where_and_when(client):
    # The fixture server stops the feed of any query named stalled_* with PRV-5040 (FEED-1).
    client.register("stalled_py", TRADE_SQL, [0])
    try:
        listed = [q for q in client.queries() if q.name == "stalled_py"][0]
        assert listed.state == "RUNNING" and listed.is_running
        assert listed.feed == "STOPPED" and listed.is_source_stopped
        assert listed.feed_stop.code == "PRV-5040"
        assert listed.feed_stop.message.endswith("line 3: 'abc' is not an INT64")
        assert listed.feed_stop.where == "trade#0"
        assert listed.feed_stop.at == "2026-09-19T08:00:00Z"
    finally:
        client.drop("stalled_py")


def test_a_retention_chosen_at_registration_is_listed_with_the_key(client):
    client.register("py_hourly", TRADE_SQL, [0, 1], retention="PT1H")
    try:
        listed = [q for q in client.queries() if q.name == "py_hourly"]
        assert len(listed) == 1
        assert listed[0].key_columns == (0, 1)
        assert listed[0].retention == "PT1H"
        # No sink is None, not an empty binding name.
        assert listed[0].sink is None
    finally:
        client.drop("py_hourly")


def test_a_retention_the_server_cannot_read_is_refused_rather_than_defaulted(client):
    with pytest.raises(QueryError) as refused:
        client.register("py_badly_kept", TRADE_SQL, [0], retention="a while")

    assert "is not a retention" in str(refused.value)
    assert "py_badly_kept" not in [q.name for q in client.queries()]


def test_the_same_question_registered_twice_is_one_computation(client):
    first = client.register("py_a", TRADE_SQL, [0])
    # Different text, same normalised plan.
    second = client.register("py_b", "SELECT t.trade_id, t.product_type, t.trade_json FROM trade AS t", [0])
    try:
        assert first.fingerprint == second.fingerprint
    finally:
        client.drop("py_a")
        client.drop("py_b")


def test_a_query_can_be_paused_resumed_and_dropped(client):
    client.register("py_life", TRADE_SQL, [0])
    client.pause("py_life")
    assert [q.state for q in client.queries() if q.name == "py_life"] == ["PAUSED"]

    client.resume("py_life")
    assert [q.state for q in client.queries() if q.name == "py_life"] == ["RUNNING"]

    client.drop("py_life")
    assert "py_life" not in [q.name for q in client.queries()]


# --- Blue/green replacement (ADR-046) -----------------------------------------------------

REPLACEMENT_SQL = "SELECT trade_id, product_type, trade_json, 'reviewed' AS status FROM trade"


def _await_caught_up(client, name):
    deadline = time.time() + 30
    while time.time() < deadline:
        status = client.replacement(name)
        if status is not None and status.state == "CAUGHT_UP":
            return status
        time.sleep(0.02)
    raise AssertionError(f"{name} never caught up")


def test_a_query_is_replaced_cut_over_and_rolled_back(client):
    client.register("py_replace", TRADE_SQL, [0])
    try:
        started = client.replace("py_replace", REPLACEMENT_SQL, [0], cutover="manual")
        # The name still answers the version it answers now: a replacement is not a swap.
        assert started.state in ("BACKFILLING", "CAUGHT_UP")
        assert started.sql == REPLACEMENT_SQL
        assert started.active
        assert [q.sql for q in client.queries() if q.name == "py_replace"] == [TRADE_SQL]

        _await_caught_up(client, "py_replace")
        cut = client.cut_over("py_replace")
        assert cut.state == "CUT_OVER"
        assert cut.rollback_available
        assert [q.sql for q in client.queries() if q.name == "py_replace"] == [REPLACEMENT_SQL]

        back = client.roll_back("py_replace")
        assert back.state == "ROLLED_BACK"
        assert [q.sql for q in client.queries() if q.name == "py_replace"] == [TRADE_SQL]
    finally:
        client.drop("py_replace")


def test_a_backfill_is_throttled_paused_and_resumed_from_python(client):
    client.register("py_throttled", TRADE_SQL, [0])
    try:
        client.replace("py_throttled", REPLACEMENT_SQL, [0], rate_limit=500, cutover="manual")
        assert client.throttle_backfill("py_throttled", 50).rate_limit == 50
        assert client.pause_backfill("py_throttled").paused is True
        assert client.resume_backfill("py_throttled").paused is False

        listed = [r.name for r in client.replacements()]
        assert "py_throttled" in listed

        # A rate above the ceiling it was started with is refused: a ceiling is a ceiling.
        with pytest.raises(QueryError) as refused:
            client.throttle_backfill("py_throttled", 100_000)
        assert "PRV-4018" in str(refused.value)

        client.abandon_replacement("py_throttled")
        assert client.replacement("py_throttled").state == "ABANDONED"
    finally:
        client.drop("py_throttled")


def test_the_replacement_refusals_reach_python_with_their_codes(client):
    client.register("py_refused", TRADE_SQL, [0])
    try:
        with pytest.raises(QueryError) as no_replacement:
            client.cut_over("py_refused")
        assert "PRV-4016" in str(no_replacement.value)

        with pytest.raises(QueryError) as same_question:
            client.replace("py_refused", TRADE_SQL, [0])
        assert "PRV-4017" in str(same_question.value)

        with pytest.raises(QueryError) as not_built:
            client.replace("py_refused", REPLACEMENT_SQL, [0], backfill="window")
        assert "PRV-4018" in str(not_built.value)

        assert client.replacement("py_never_replaced") is None
    finally:
        client.drop("py_refused")


def test_dropping_an_unknown_query_is_refused(client):
    with pytest.raises(QueryError) as refused:
        client.drop("py_never_registered")

    assert "PRV-8002" in str(refused.value)


def test_registering_a_query_over_an_unknown_stream_is_refused(client):
    with pytest.raises(QueryError) as refused:
        client.register("py_bad", "SELECT nope FROM nosuchstream", [0])

    # PRV-2003: the stream is not registered. Caught at registration rather than at the
    # first row, which is the point of planning up front.
    assert "PRV-" in str(refused.value)


def test_a_sink_name_reaches_the_server_and_an_unbound_one_is_refused(client):
    # The fourth field of the register action names a sink (ADR-043). This server binds none,
    # so the registration is refused by the sink's name -- which is only possible if the name
    # travelled. A client that dropped it would have registered a view-only query instead.
    with pytest.raises(QueryError) as refused:
        client.register("py_sinked", TRADE_SQL, [0], sink="nowhere_bound")

    assert "nowhere_bound" in str(refused.value)
    assert "py_sinked" not in [q.name for q in client.queries()]


def test_a_subscription_can_be_opened_and_filtered(client):
    """The subscription surface, exercised for shape rather than for delivery.

    Nothing feeds the `trade` stream in this fixture, so no batch ever arrives -- and
    that is the honest thing to assert here. What *is* worth proving from Python is
    that the ticket is built correctly, the server accepts it, a bad filter is refused
    rather than ignored, and the generator does not blow up. Delivery over the wire is
    proven in FlightRegistryTest, which has an engine to feed.
    """
    client.register("py_sub", TRADE_SQL, [0])
    try:
        stream = client.subscribe("py_sub", {"product_type": "SWAP"})
        # A generator: nothing happens until it is iterated, and iterating would park
        # forever on a stream with no data. Closing it is what a consumer that has had
        # enough does, and it must not raise.
        stream.close()
    finally:
        client.drop("py_sub")


def _subscription_batch(rows, *, weighted=True):
    """An Arrow batch shaped exactly as the server sends one for a subscription."""
    import pyarrow as pa

    fields = [
        pa.field("trade_id", pa.string()),
        pa.field("product_type", pa.string()),
    ]
    arrays = [
        pa.array([r[0] for r in rows]),
        pa.array([r[1] for r in rows]),
    ]
    if weighted:
        fields.append(
            pa.field("_pravaha_weight", pa.int64(), nullable=False,
                     metadata={b"pravaha.weight": b"true"})
        )
        arrays.append(pa.array([r[2] for r in rows], type=pa.int64()))
    return pa.Table.from_arrays(arrays, schema=pa.schema(fields))


def test_a_retraction_arrives_as_a_retraction():
    """The weight decoded off the wire, which is what tells a correction from a copy.

    A retraction carries identical bytes in every column the view selected. A consumer
    keeping its own total that could not see the weight would apply the correction as a
    second copy of the value being corrected, and drift from the view permanently.
    """
    rows = _weighted_rows_of(_subscription_batch([("T-1", "SWAP", 1), ("T-1", "SWAP", -1)]))

    assert [r.weight for r in rows] == [1, -1]
    assert [r.is_retraction for r in rows] == [False, True]


def test_the_weight_column_is_not_mistaken_for_one_of_the_views_own():
    rows = _weighted_rows_of(_subscription_batch([("T-1", "SWAP", 1)]))

    assert list(rows[0].columns) == ["trade_id", "product_type"]
    assert rows[0].to_dict() == {"trade_id": "T-1", "product_type": "SWAP"}
    # Positional reads have to stay positional: a caller reading row[1] must get the
    # view's second column, not the engine's bookkeeping.
    assert rows[0][1] == "SWAP"


def test_an_unweighted_batch_reads_as_every_row_present():
    """A server that sends no weight column is read as all inserts rather than refused."""
    rows = _weighted_rows_of(_subscription_batch([("T-1", "SWAP")], weighted=False))

    assert rows[0].weight == 1
    assert rows[0].is_retraction is False
    assert list(rows[0].columns) == ["trade_id", "product_type"]


def _collect(stream, wanted, rows, ready):
    """Drains a subscription on its own thread until it has `wanted` rows.

    On a thread because `subscribe` is a generator: nothing is sent to the server until it is
    iterated. Feeding first and iterating afterwards misses every commit that happened in between,
    which is a subscriber that appears to hang -- and is how a consumer written from the docstring
    would get it wrong too.
    """

    def run():
        for batch in stream:
            if not ready.is_set():
                ready.set()
            rows.extend(batch)
            if len(rows) >= wanted:
                return

    worker = threading.Thread(target=run, daemon=True)
    worker.start()
    return worker


def test_a_continuous_query_delivers_rows_to_python_over_the_network(client, feed):
    """A continuous query registered, fed and delivered, all from Python, all over Flight.

    Everything below this line used to be untestable from Python: nothing fed the stream, so the
    subscription test asserted that a ticket was built and the server accepted it. An API that
    accepts a subscription and never delivers one passes that test.
    """
    client.register("py_live", TRADE_SQL, [0])
    stream = client.subscribe("py_live")
    try:
        rows, ready = [], threading.Event()
        worker = _collect(stream, 2, rows, ready)
        time.sleep(1.0)

        feed("T-1", "SWAP")
        feed("T-2", "EQUITY")
        worker.join(30)

        assert sorted(row["trade_id"] for row in rows) == ["T-1", "T-2"]
        assert sorted(row["product_type"] for row in rows) == ["EQUITY", "SWAP"]
        assert all(row.weight == 1 for row in rows)
    finally:
        stream.close()
        client.drop("py_live")


def test_a_retraction_reaches_python_as_a_retraction_over_the_network(client, feed):
    """The weight, end to end and across a language boundary.

    A retraction carries identical bytes in every column the view selected. A Python consumer
    maintaining its own total that could not see the weight would apply a correction as a second
    copy of the value being corrected. The unit tests above prove the decoder; this proves that the
    whole path -- engine, wire, decoder -- agrees with it.
    """
    client.register("py_retract", TRADE_SQL, [0])
    stream = client.subscribe("py_retract")
    try:
        rows, ready = [], threading.Event()
        worker = _collect(stream, 2, rows, ready)
        time.sleep(1.0)

        feed("T-9", "SWAP")
        feed("T-9", "SWAP", weight=-1)
        worker.join(30)

        assert [row.weight for row in rows] == [1, -1]
        assert [row.is_retraction for row in rows] == [False, True]
        assert all(row["trade_id"] == "T-9" for row in rows)
    finally:
        stream.close()
        client.drop("py_retract")


def test_a_python_subscriber_filters_at_the_tap_and_receives_only_its_slice(client, feed):
    client.register("py_filtered", TRADE_SQL, [0])
    stream = client.subscribe("py_filtered", {"product_type": "SWAP"})
    try:
        rows, ready = [], threading.Event()
        worker = _collect(stream, 2, rows, ready)
        time.sleep(1.0)

        feed("T-20", "EQUITY")
        feed("T-21", "SWAP")
        feed("T-22", "EQUITY")
        feed("T-23", "SWAP")
        worker.join(30)

        # Rows the filter excludes never cross the network, so they cannot appear here at all.
        assert sorted(row["trade_id"] for row in rows) == ["T-21", "T-23"]
    finally:
        stream.close()
        client.drop("py_filtered")


def test_a_python_client_reads_the_view_of_its_own_continuous_query(client, feed):
    """Register, feed, then ask the engine what the query holds -- all over the wire.

    The other half of a continuous query's contract: not only that changes are pushed, but that the
    maintained view can be read as a table whenever a client wants it.
    """
    client.register("py_view", TRADE_SQL, [0])
    try:
        feed("T-30", "SWAP")
        feed("T-31", "EQUITY")

        deadline = time.time() + 30
        rows = []
        while time.time() < deadline:
            rows = [row.to_dict() for row in client.query("SELECT trade_id, product_type FROM py_view")]
            if len(rows) >= 2:
                break
            time.sleep(0.1)

        assert sorted(row["trade_id"] for row in rows) == ["T-30", "T-31"]
    finally:
        client.drop("py_view")


def test_a_filter_naming_an_unknown_column_is_refused(client):
    client.register("py_filter", TRADE_SQL, [0])
    try:
        with pytest.raises(QueryError) as refused:
            # Consumed, because the refusal comes from the server when the stream opens.
            next(iter(client.subscribe("py_filter", {"prodcut_type": "SWAP"})))

        # Refused, not ignored. A typo quietly dropped would leave a consumer receiving
        # everything while believing it had asked for a slice.
        assert "has no column" in str(refused.value)
    finally:
        client.drop("py_filter")


# --- Snapshot subscriptions (SUB-1): the view, then every commit after it -------------------


def test_a_snapshot_ticket_has_its_own_verb_and_a_plain_one_is_unchanged():
    from pravaha.client import _subscribe_ticket, _wire_decode

    assert _wire_decode(_subscribe_ticket("v", ["c", "x"])) == ["subscribe", "v", "c", "x"]
    assert _wire_decode(_subscribe_ticket("v", [], snapshot=True)) == ["subscribe.snapshot", "v"]


def test_a_batch_mark_is_read_and_anything_else_is_no_mark():
    from pravaha.client import _mark_of

    assert _mark_of(pyarrow.py_buffer(b"pravaha:snapshot-end:42")) == ("snapshot-end", 42, 0)
    assert _mark_of(pyarrow.py_buffer(b"pravaha:commit:-7")) == ("commit", -7, 0)
    # STRM-10: a fourth component carries how many whole commits this subscriber has lost.
    # Split on every colon rather than on the last one, or "commit:42" reads as the kind.
    assert _mark_of(pyarrow.py_buffer(b"pravaha:commit:42:9")) == ("commit", 42, 9)
    assert _mark_of(None) is None
    assert _mark_of(pyarrow.py_buffer(b"")) is None
    assert _mark_of(pyarrow.py_buffer(b"somebody else's")) is None
    assert _mark_of(pyarrow.py_buffer(b"pravaha:commit:soon")) is None


def test_a_change_batch_is_still_a_list():
    from pravaha.client import ChangeBatch

    batch = ChangeBatch([1, 2], snapshot=True, frontier=9)
    assert batch == [1, 2] and len(batch) == 2
    assert batch.snapshot and batch.frontier == 9
    assert "snapshot" in repr(batch)


def test_a_snapshot_subscription_starts_from_the_view_and_misses_nothing_after_it(client, feed):
    """What a plain subscription cannot do: rows committed before it attached arrive first.

    The first batch is the view as a commit left it, marked as the snapshot, and the next is the
    commit after it -- so a copy built from the two is the view, with no read beside the
    subscription to race.
    """
    client.register("py_snap", TRADE_SQL, [0])
    stream = None
    try:
        feed("T-40", "SWAP")
        deadline = time.time() + 30
        while time.time() < deadline and not list(client.query("SELECT trade_id FROM py_snap")):
            time.sleep(0.1)

        stream = client.subscribe("py_snap", snapshot=True)
        batches: list = []
        done = threading.Event()

        def run():
            for batch in stream:
                batches.append(batch)
                if len(batches) >= 2:
                    done.set()
                    return

        threading.Thread(target=run, daemon=True).start()
        deadline = time.time() + 30
        while time.time() < deadline and not batches:
            time.sleep(0.05)
        feed("T-41", "EQUITY")
        assert done.wait(30)

        first, second = batches[0], batches[1]
        assert first.snapshot and first.frontier is not None
        assert [row["trade_id"] for row in first] == ["T-40"]
        assert [row.weight for row in first] == [1]
        assert not second.snapshot
        assert [(row["trade_id"], row.weight) for row in second] == [("T-41", 1)]
        assert second.frontier >= first.frontier
    finally:
        if stream is not None:
            stream.close()
        client.drop("py_snap")


def test_a_filtered_snapshot_of_nothing_still_arrives(client):
    client.register("py_snap_empty", TRADE_SQL, [0])
    stream = client.subscribe("py_snap_empty", {"product_type": "NONE"}, snapshot=True)
    try:
        first = next(iter(stream))
        assert first.snapshot
        assert list(first) == []
    finally:
        stream.close()
        client.drop("py_snap_empty")


def test_a_plain_subscriptions_batches_carry_no_snapshot_and_no_frontier(client, feed):
    client.register("py_plain", TRADE_SQL, [0])
    stream = client.subscribe("py_plain")
    try:
        batches: list = []

        def run():
            for batch in stream:
                batches.append(batch)
                return

        worker = threading.Thread(target=run, daemon=True)
        worker.start()
        time.sleep(1.0)
        feed("T-50", "SWAP")
        worker.join(30)

        assert len(batches) == 1
        assert not batches[0].snapshot and batches[0].frontier is None
    finally:
        stream.close()
        client.drop("py_plain")
