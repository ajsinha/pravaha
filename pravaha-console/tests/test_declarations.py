"""Managing streams, named connections, lookups and sinks from the console (ADR-064).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Driven through the real console against the fake engine's declaration files (``fake_declarations``),
which keeps the engine's contract: Validate is a dry run that writes nothing, Save writes the file, a
change to what a query uses is refused with its code and the queries named, disabling never is, and a
literal secret is refused. The console renders the answers and decides nothing.
"""
from __future__ import annotations

import pytest

pytest.importorskip("fastapi.testclient")

from fake_engine import FakeEngine
from fake_identity import sign_in
from test_identity import _app, _person

from core.declarations import ConnectionForm, StreamForm, literal_secret, parse_schema, schema_text


@pytest.fixture
def engine():
    return FakeEngine()


@pytest.fixture
def admin(engine):
    client = _app(engine)
    sign_in(client)
    return client


def _stream_form(**extra) -> dict:
    form = {"name": "orders", "columns": "2", "col-0-name": "id", "col-0-type": "INT64",
            "col-1-name": "at", "col-1-type": "TIMESTAMP", "col-1-nullable": "1", "event_time": "at",
            "out_of_orderness": "10s", "source": "plugin", "plugin": "filesystem", "options": "1",
            "opt-0-key": "path", "opt-0-value": "data/orders.csv", "enabled": "true"}
    form.update(extra)
    return form


def test_the_schema_builder_round_trips_the_engines_spelling():
    columns = parse_schema("id:INT64,amount:DECIMAL(18,2)?,at:TIMESTAMP")
    assert [(c.name, c.type, c.nullable) for c in columns] == [
        ("id", "INT64", False), ("amount", "DECIMAL(18,2)", True), ("at", "TIMESTAMP", False)]
    assert schema_text(columns) == "id:INT64,amount:DECIMAL(18,2)?,at:TIMESTAMP"
    assert literal_secret("ssl.truststore.password", "hunter2")
    assert not literal_secret("ssl.truststore.password", "${KAFKA_PASSWORD}")
    assert not literal_secret("bootstrap.servers", "b:9092")


def test_a_file_prefills_the_edit_forms():
    form = StreamForm.from_file("txn", 'schema: "id:INT64,at:TIMESTAMP"\nevent-time: at\nenabled: false\n'
                                       "source:\n  connector: kafka\n  options:\n    topic: txn\n")
    assert form.schema() == "id:INT64,at:TIMESTAMP" and form.event_time == "at" and not form.enabled
    assert form.source == "connector" and form.connector == "kafka" and form.options == [("topic", "txn")]
    connection = ConnectionForm.from_file("k", "plugin: kafka\noptions:\n  ssl:\n    key.password: ${P}\n")
    assert connection.plugin == "kafka" and connection.options == [("ssl.key.password", "${P}")]


def test_new_stream_validates_without_writing_then_saves_the_file(admin, engine):
    page = admin.get("/catalog/streams/new").text
    assert 'id="stream-form"' in page and 'id="schema-builder"' in page and 'name="csrf_token"' in page
    assert "About this page" in page or 'id="page-help"' in page or "page-help" in page

    dry = admin.post("/catalog/streams/new", data=_stream_form(action="validate")).text
    assert 'data-valid="yes"' in dry and 'id="decl-yaml"' in dry and "plugin: filesystem" in dry
    assert ("streams", "orders") not in engine.declared.files
    assert engine.declared.calls[-1] == ("POST", "streams", "orders", True)

    saved = admin.post("/catalog/streams/new", data=_stream_form(action="save"), follow_redirects=False)
    assert saved.status_code == 303 and saved.headers["location"] == "/catalog/streams/orders"
    assert 'schema: "id:INT64,at:TIMESTAMP?"' in engine.declared.files[("streams", "orders")]["yaml"]
    assert "is written to" in admin.get("/catalog/streams/orders").text


def test_rows_are_added_and_removed_without_javascript(admin):
    added = admin.post("/catalog/streams/new", data=_stream_form(action="add_column")).text
    assert 'name="col-2-name"' in added
    removed = admin.post("/catalog/streams/new", data=_stream_form(action="remove_column-0")).text
    assert 'name="col-1-name"' not in removed and 'value="at"' in removed


def test_a_literal_secret_is_marked_and_refused_with_its_code(admin, engine):
    page = admin.post("/catalog/connectors/new", data={
        "name": "kafka", "plugin": "filesystem", "options": "1", "opt-0-key": "sasl.password",
        "opt-0-value": "hunter2", "action": "save"})
    assert page.status_code == 400
    assert "PRV-1063" in page.text and 'aria-invalid="true"' in page.text
    assert ("connectors", "kafka") not in engine.declared.files


def test_a_change_in_use_is_refused_naming_the_queries_and_nothing_is_written(admin, engine):
    before = engine.declared.files[("streams", "txn")]["yaml"]
    edit = admin.get("/catalog/streams/txn/edit").text
    assert 'id="decl-holders"' in edit and "big_txn" in edit and 'value="txn_id"' in edit
    refused = admin.post("/catalog/streams/txn/edit", data=_stream_form(name="txn", action="save"))
    assert refused.status_code == 409
    assert "PRV-1065" in refused.text and "big_txn" in refused.text and 'id="decl-refusal"' in refused.text
    assert engine.declared.files[("streams", "txn")]["yaml"] == before


def test_delete_shows_the_dry_run_and_disable_is_allowed_in_use(admin, engine):
    delete = admin.get("/catalog/streams/txn/delete").text
    assert 'data-valid="no"' in delete and "PRV-1065" in delete and 'id="decl-confirm"' not in delete
    disable = admin.get("/catalog/streams/txn/disable").text
    assert 'data-valid="yes"' in disable and 'id="decl-confirm"' in disable and "big_txn" in disable
    done = admin.post("/catalog/streams/txn/disable", follow_redirects=False)
    assert done.status_code == 303 and done.headers["location"] == "/catalog/streams/txn"
    assert engine.declared.files[("streams", "txn")]["enabled"] is False
    assert "enabled: false" in engine.declared.files[("streams", "txn")]["yaml"]


def test_the_connectors_page_lists_plugins_and_connections_and_tests_one(admin, engine):
    page = admin.get("/catalog/connectors").text
    assert 'id="loaded-plugins"' in page and 'data-plugin="filesystem"' in page
    assert 'data-connection="files"' in page and "source:txn" in page and 'id="new-connection"' in page
    tested = admin.post("/catalog/connectors/files/test").text
    assert 'id="connection-test"' in tested and "HEALTHY" in tested and "42 ms" in tested
    engine.declared.test_outcome = {"ok": False, "role": "source", "latencyMillis": 10000, "state": "TIMED_OUT",
                                    "detail": "no answer within 10s", "code": "PRV-1073"}
    failed = admin.post("/catalog/connectors/files/test").text
    assert 'data-ok="no"' in failed and "TIMED_OUT" in failed and "PRV-1073" in failed


def test_a_lookup_or_sink_is_edited_as_yaml(admin, engine):
    page = admin.post("/catalog/sinks/new", data={"name": "out", "yaml": "plugin: filesystem\noptions:\n  path: o\n",
                                                  "action": "save"}, follow_redirects=False)
    assert page.status_code == 303 and ("sinks", "out") in engine.declared.files
    assert 'id="declaration-yaml-form"' in admin.get("/catalog/sinks/out/edit").text


def test_somebody_who_may_not_create_is_not_offered_new(engine):
    ann = _person(engine)
    catalog = ann.get("/catalog").text
    assert 'id="new-stream"' not in catalog
    assert 'id="new-connection"' not in ann.get("/catalog/connectors").text
    form = ann.get("/catalog/streams/new").text
    assert 'id="decl-not-allowed"' in form and 'id="sf-save" disabled' in form
