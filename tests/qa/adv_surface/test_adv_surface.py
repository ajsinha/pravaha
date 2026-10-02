# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
ADV-SURFACE: adversarial checks of a running node's external surfaces, kept as tests.

The cases and their verdicts are ``docs/project/qa/cases/ADV-SURFACE.md`` and
``docs/project/qa/logs/ADV-SURFACE.md``. Nothing here runs unless it is pointed at a node,
because every check talks to a real one over HTTP, pgwire or Flight:

    PRAVAHA_QI_HTTP=http://127.0.0.1:28480      the node's HTTP API (users profile on)
    PRAVAHA_QI_PGWIRE=127.0.0.1:26432           its PostgreSQL gateway (pravaha.pgwire.enabled)
    PRAVAHA_QI_FLIGHT=grpc://127.0.0.1:29490    its Flight endpoint
    PRAVAHA_QI_ADMIN_PASSWORD=pravaha-dev-admin the bootstrap admin's password (dev profile)

    python -m pytest tests/qa/adv_surface -q -rs

A check that reproduces an OPEN defect is skipped with its case ID, so the file stays green while
the defect is open; ``PRAVAHA_QI_REPRODUCE=1`` runs those as strict xfails, which must fail -- a
pass there means the defect is fixed and the mark should go.
The two denial-of-service reproductions are destructive -- they push a node into
OutOfMemoryError -- and are also gated on ``PRAVAHA_QI_DESTRUCTIVE=1``; aim them at a scratch node.
Everything this creates is named ``qi_*`` and dropped again where it can be.
"""

from __future__ import annotations

import http.client
import json
import os
import socket
import struct
import threading
import time
import urllib.error
import urllib.request
import uuid

import pytest

HTTP = os.environ.get("PRAVAHA_QI_HTTP")
PGWIRE = os.environ.get("PRAVAHA_QI_PGWIRE")
FLIGHT = os.environ.get("PRAVAHA_QI_FLIGHT")
ADMIN_PASSWORD = os.environ.get("PRAVAHA_QI_ADMIN_PASSWORD", "pravaha-dev-admin")
DESTRUCTIVE = os.environ.get("PRAVAHA_QI_DESTRUCTIVE") == "1"

needs_http = pytest.mark.skipif(not HTTP, reason="set PRAVAHA_QI_HTTP to a running node")
needs_pgwire = pytest.mark.skipif(not (HTTP and PGWIRE), reason="set PRAVAHA_QI_HTTP and PRAVAHA_QI_PGWIRE")
needs_flight = pytest.mark.skipif(not (HTTP and FLIGHT), reason="set PRAVAHA_QI_HTTP and PRAVAHA_QI_FLIGHT")
destructive = pytest.mark.skipif(not DESTRUCTIVE, reason="destructive: set PRAVAHA_QI_DESTRUCTIVE=1 against a scratch node")
REPRODUCE = os.environ.get("PRAVAHA_QI_REPRODUCE") == "1"


def open_defect(reason):
    """Skipped while the defect is open; with PRAVAHA_QI_REPRODUCE=1 it runs and must fail (strict xfail)."""
    return pytest.mark.xfail(strict=True, reason=reason) if REPRODUCE else pytest.mark.skip(reason=reason)


# ---------------------------------------------------------------------------------------------
# Helpers


def http_call(method, path, token=None, body=None, raw=None, ctype="application/json", headers=None, timeout=30):
    """(status, headers, text) -- never raises on an HTTP error status."""
    host, port = HTTP.split("//", 1)[1].split(":")
    conn = http.client.HTTPConnection(host, int(port.rstrip("/")), timeout=timeout)
    h = dict(headers or {})
    if token:
        h["Authorization"] = "Bearer " + token
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    if data is not None and ctype:
        h["Content-Type"] = ctype
    conn.request(method, path, body=data, headers=h)
    r = conn.getresponse()
    return r.status, dict(r.getheaders()), r.read().decode(errors="replace")


def login(user, password):
    st, _, body = http_call("POST", "/api/v1/auth/login", body={"username": user, "password": password})
    assert st == 200, body
    return json.loads(body)["token"]


@pytest.fixture(scope="module")
def admin():
    return login("admin", ADMIN_PASSWORD)


@pytest.fixture(scope="module")
def reader(admin):
    """A fresh user with role reader, and their session token."""
    name = "qi_" + uuid.uuid4().hex[:8]
    password = "Qi-Reader-Password-2026"
    st, _, body = http_call("POST", "/api/v1/users", admin, {"username": name, "password": password, "roles": ["reader"]})
    assert st == 200, body
    return name, password, login(name, password)


def pg_host_port():
    host, port = PGWIRE.split(":")
    return host, int(port)


def pg_connect(password, user="admin", **kw):
    psycopg = pytest.importorskip("psycopg")
    host, port = pg_host_port()
    return psycopg.connect(host=host, port=port, user=user, password=password, dbname="pravaha", autocommit=True, **kw)


def pg_startup(user="qi"):
    body = struct.pack("!I", 196608) + b"user\0" + user.encode() + b"\0database\0pravaha\0\0"
    return struct.pack("!I", len(body) + 4) + body


def any_view(token):
    st, _, body = http_call("GET", "/api/v1/queries", token)
    assert st == 200, body
    parsed = json.loads(body)
    rows = parsed.get("queries", parsed) if isinstance(parsed, dict) else parsed
    if not rows:
        pytest.skip("the node has no registered view to read")
    return rows[0]["name"]


# ---------------------------------------------------------------------------------------------
# PostgreSQL gateway


@needs_pgwire
@destructive
@open_defect("QI-012: open defect -- an unauthenticated client makes the gateway allocate a declared "
                         "16 MiB PasswordMessage before reading it; ~60 sockets end the shipped image's JVM "
                         "(-XX:+ExitOnOutOfMemoryError)")
def test_qi012_unauthenticated_password_flood_leaves_the_node_serving():
    host, port = pg_host_port()
    socks = []
    for _ in range(100):
        s = socket.create_connection((host, port), timeout=10)
        s.sendall(pg_startup())
        s.recv(64)  # AuthenticationCleartextPassword
        s.sendall(b"p" + struct.pack("!I", 16 * 1024 * 1024))  # declares 16 MiB, sends nothing more
        socks.append(s)
    time.sleep(3)
    st, _, _ = http_call("GET", "/actuator/health/readiness")
    for s in socks:
        s.close()
    assert st == 200


@needs_pgwire
def test_qi009_oversized_startup_packet_is_refused_by_name():
    host, port = pg_host_port()
    with socket.create_connection((host, port), timeout=10) as s:
        s.sendall(struct.pack("!II", 2**31 - 1, 196608))
        reply = b""
        while chunk := s.recv(4096):  # the server answers, then closes
            reply += chunk
    assert reply[:1] == b"E" and b"PRV-6202" in reply and b"08P01" in reply


@needs_pgwire
def test_qi002_account_password_is_not_a_pgwire_credential(reader):
    name, password, _ = reader
    with pytest.raises(Exception) as refused:
        pg_connect(password, user=name)
    assert "PRV-7001" in str(refused.value)


@needs_pgwire
def test_qi003_revoked_key_cannot_open_a_new_connection(admin):
    st, _, body = http_call("POST", "/api/v1/keys", admin, {"name": "qi_" + uuid.uuid4().hex[:6], "days": 1})
    assert st == 200, body
    key = json.loads(body)
    with pg_connect(key["key"]):
        pass
    assert http_call("DELETE", f"/api/v1/keys/{key['keyId']}", admin)[0] == 204
    with pytest.raises(Exception) as refused:
        pg_connect(key["key"])
    assert "PRV-7001" in str(refused.value)


@needs_pgwire
@open_defect("QI-004: open defect -- a pgwire connection opened with a key, session or user that is "
                         "later revoked, ended or disabled keeps reading for as long as it stays open")
def test_qi004_an_open_connection_stops_reading_once_its_key_is_revoked(admin):
    view = any_view(admin)
    st, _, body = http_call("POST", "/api/v1/keys", admin, {"name": "qi_" + uuid.uuid4().hex[:6], "days": 1})
    key = json.loads(body)
    with pg_connect(key["key"]) as conn:
        conn.execute(f"SELECT COUNT(*) FROM {view}").fetchone()
        assert http_call("DELETE", f"/api/v1/keys/{key['keyId']}", admin)[0] == 204
        with pytest.raises(Exception):
            conn.execute(f"SELECT COUNT(*) FROM {view}").fetchone()


@needs_pgwire
@open_defect("QI-031: open defect -- an int4 binary parameter against a BIGINT column is PRV-6202 "
                         "08P01 (pgjdbc setInt, psycopg %b with a small int, Npgsql AddWithValue(int))")
def test_qi031_int4_binary_parameter_against_a_bigint_column(admin):
    psycopg = pytest.importorskip("psycopg")
    from psycopg.types.numeric import Int4

    view = any_view(admin)
    with pg_connect(admin) as conn:
        description = conn.execute(f"SELECT * FROM {view}").description
        bigint = next((d.name for d in description if d.type_code == 20), None)
        if bigint is None:
            pytest.skip(f"{view} has no BIGINT column")
        conn.execute(f"SELECT * FROM {view} WHERE {bigint} > %b", [Int4(1)]).fetchall()


@needs_pgwire
def test_qi019_a_failed_statement_aborts_the_block_until_rollback(admin):
    psycopg = pytest.importorskip("psycopg")
    view = any_view(admin)
    host, port = pg_host_port()
    with psycopg.connect(host=host, port=port, user="admin", password=admin, dbname="pravaha") as conn:
        with pytest.raises(psycopg.Error):
            conn.execute(f"SELECT no_such_column FROM {view}")
        with pytest.raises(psycopg.errors.InFailedSqlTransaction):
            conn.execute(f"SELECT * FROM {view}")
        conn.rollback()
        conn.execute(f"SELECT * FROM {view}").fetchall()


@needs_pgwire
@open_defect("QI-021: open defect -- COPY is PRV-2001 42000 'Non-query expression', where the pgwire "
                         "help topic promises PRV-6201 0A000")
def test_qi021_copy_is_refused_as_documented(admin):
    psycopg = pytest.importorskip("psycopg")
    view = any_view(admin)
    with pg_connect(admin) as conn:
        with pytest.raises(psycopg.Error) as refused:
            conn.execute(f"COPY {view} TO STDOUT")
    assert refused.value.sqlstate == "0A000" and "PRV-6201" in str(refused.value)


# ---------------------------------------------------------------------------------------------
# HTTP


@needs_http
def test_qi040_every_locked_path_wants_a_credential():
    lock = json.load(open(os.path.join(os.path.dirname(__file__), "../../../api/openapi.lock.json")))["paths"]
    open_paths = []
    for path, ops in lock.items():
        for method in ops:
            concrete = (path.replace("{name}", "x").replace("{username}", "x").replace("{keyId}", "x")
                        .replace("{id}", "x").replace("{operator}", "x"))
            st, _, _ = http_call(method.upper(), concrete, body={} if method in ("post", "put", "patch") else None)
            if st != 401:
                open_paths.append((method, path, st))
    assert open_paths == [("post", "/api/v1/auth/reset/redeem", 400)] or open_paths == []


@needs_http
@pytest.mark.parametrize("raw", [b"{", b"[]", b"null", b"", b"\xff\xfe"])
def test_qi043_malformed_json_is_an_api_error(admin, raw):
    st, headers, body = http_call("POST", "/api/v1/queries/validate", admin, raw=raw)
    assert st == 400 and body.startswith("{") and '"code":"PRV-' in body
    assert "Exception" not in body and "springframework" not in body


@needs_http
@open_defect("QI-046/QI-060: open defect -- an encoded slash or NUL in a path, a 10 KB Authorization "
                         "header or a 64 KiB header is answered by Tomcat's HTML error page, not an ApiError")
@pytest.mark.parametrize("path, headers", [
    ("/api/v1/queries/..%2fx", {}),
    ("/api/v1/streams/a%00b", {}),
    ("/api/v1/status", {"X-Big": "a" * 65536}),
])
def test_qi046_every_error_is_an_api_error(admin, path, headers):
    st, h, body = http_call("GET", path, admin, headers=headers)
    assert st >= 400 and h.get("Content-Type", "").startswith("application/json"), body[:200]


@needs_http
@open_defect("QI-054: open defect -- after five failures an existing account answers 423 PRV-7011 "
                         "'locked until ...' while an unknown name keeps answering 401 PRV-7010: an enumeration oracle")
def test_qi054_a_locked_account_reads_like_an_unknown_one(admin):
    name = "qi_" + uuid.uuid4().hex[:8]
    http_call("POST", "/api/v1/users", admin, {"username": name, "password": "Qi-Lock-Password-2026", "roles": ["reader"]})
    for _ in range(6):
        locked = http_call("POST", "/api/v1/auth/login", body={"username": name, "password": "wrong-wrong-1A"})
    unknown = http_call("POST", "/api/v1/auth/login", body={"username": name + "_x", "password": "wrong-wrong-1A"})
    assert locked[0] == unknown[0]


@needs_http
@destructive
@open_defect("QI-045: open defect -- 30 concurrent anonymous 19 MB POST /api/v1/auth/login bodies are "
                         "buffered whole before authentication and push the node into OutOfMemoryError")
def test_qi045_anonymous_large_login_bodies_leave_the_node_serving():
    body = b'{"username":"' + b"a" * (19 * 1000 * 1000) + b'","password":"x"}'

    def one():
        try:
            http_call("POST", "/api/v1/auth/login", raw=body, timeout=120)
        except OSError:
            pass

    threads = [threading.Thread(target=one) for _ in range(30)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert http_call("GET", "/actuator/health/readiness")[0] == 200


@needs_flight
@open_defect("QI-REG: open defect -- a stream declared over POST /api/v1/streams (pravaha streams "
                         "declare) validates over HTTP but is PRV-2002 'not found' to a Flight registration")
def test_qi059_a_declared_stream_can_be_registered_over(admin):
    pytest.importorskip("pyarrow")
    from pravaha import connect
    from pravaha.options import ClientOptions

    stream = "qi_s_" + uuid.uuid4().hex[:6]
    st, _, body = http_call("POST", "/api/v1/streams", admin, {"name": stream, "schema": "k:INT64,v:INT64"})
    assert st == 201, body
    options = ClientOptions.create(FLIGHT, token=admin, allow_insecure_token=True)
    with connect(options=options) as client:
        client.register(stream + "_v", f"SELECT k, v FROM {stream}", [0])
        client.drop(stream + "_v")


# ---------------------------------------------------------------------------------------------
# Flight


@needs_flight
@open_defect("QI-071: open defect -- a ticket the server cannot parse is INTERNAL 'There was an error "
                         "servicing your request', not INVALID_ARGUMENT with a PRV code")
def test_qi071_a_garbage_ticket_is_invalid_argument(admin):
    flight = pytest.importorskip("pyarrow.flight")
    client = flight.FlightClient(FLIGHT)
    options = flight.FlightCallOptions(headers=[(b"authorization", ("Bearer " + admin).encode())])
    with pytest.raises(flight.FlightError) as refused:
        client.do_get(flight.Ticket(b"\x00\xff garbage"), options).read_all()
    assert not isinstance(refused.value, flight.FlightInternalError)


@needs_flight
def test_qi072_anonymous_flight_calls_are_unauthenticated():
    flight = pytest.importorskip("pyarrow.flight")
    client = flight.FlightClient(FLIGHT)
    with pytest.raises(flight.FlightUnauthenticatedError):
        list(client.list_actions())
    with pytest.raises(flight.FlightUnauthenticatedError):
        client.do_get(flight.Ticket(b"x")).read_all()
