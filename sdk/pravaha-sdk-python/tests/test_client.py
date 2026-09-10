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
from pravaha.client import QueryError, ReadError  # noqa: E402

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
