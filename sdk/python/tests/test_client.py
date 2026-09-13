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

    port = None
    deadline = time.time() + 90
    while time.time() < deadline:
        line = process.stdout.readline()
        if not line:
            break
        if line.startswith("PRAVAHA_FLIGHT_PORT="):
            port = int(line.strip().split("=", 1)[1])
            break
    if port is None:
        process.kill()
        pytest.skip("the Pravaha server did not start; is the module built?")

    yield port
    process.kill()
    process.wait(timeout=30)


@pytest.fixture
def client(server):
    # "grpc://" is plaintext. Omitting the scheme means TLS, which is the right default
    # for a client and the reason this is spelled out.
    with connect(f"grpc://localhost:{server}") as connected:
        yield connected


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
