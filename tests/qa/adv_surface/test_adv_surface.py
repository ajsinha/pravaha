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
The two denial-of-service replays (QI-012, QI-045) are destructive -- before PGPREAUTH-1 and
HTTPBODY-1 they pushed a node into OutOfMemoryError; now they check it keeps serving -- and are gated
on ``PRAVAHA_QI_DESTRUCTIVE=1``; aim them at a scratch node.
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


def _read_until_closed(s):
    reply = b""
    try:
        while chunk := s.recv(4096):
            reply += chunk
    except OSError:
        pass
    return reply


@needs_pgwire
def test_qi012_a_declared_16_mib_password_is_refused_on_its_length():
    # PGPREAUTH-1, fixed: before sign-in a message may be 16 KiB; a larger declaration is refused
    # FATAL 54000 PRV-6217 before anything is read or allocated, and the connection closes.
    host, port = pg_host_port()
    with socket.create_connection((host, port), timeout=10) as s:
        s.sendall(pg_startup())
        assert s.recv(64)[:1] == b"R"  # AuthenticationCleartextPassword
        s.sendall(b"p" + struct.pack("!I", 16 * 1024 * 1024))
        reply = _read_until_closed(s)
    assert reply[:1] == b"E" and b"PRV-6217" in reply and b"54000" in reply


@needs_pgwire
@destructive
def test_qi012_unauthenticated_password_flood_leaves_the_node_serving():
    # PGPREAUTH-1, fixed: the QI-012 attack -- 100 sockets each declaring a 16 MiB PasswordMessage.
    # Each is refused on its length (PRV-6217), or, past pravaha.pgwire.limits.max-unauthenticated,
    # refused at once with 53300 PRV-6216; the node keeps serving.
    host, port = pg_host_port()
    socks, refused = [], 0
    for _ in range(100):
        s = socket.create_connection((host, port), timeout=10)
        try:
            s.sendall(pg_startup())
            first = s.recv(64)
            if first[:1] == b"E":
                refused += b"PRV-6216" in first or b"53300" in first
            else:
                s.sendall(b"p" + struct.pack("!I", 16 * 1024 * 1024))  # declares 16 MiB, sends nothing more
        except OSError:
            pass
        socks.append(s)
    time.sleep(3)
    st, _, _ = http_call("GET", "/actuator/health/readiness")
    answers = [_read_until_closed(s) for s in socks]
    for s in socks:
        s.close()
    assert st == 200
    assert all(a[:1] in (b"E", b"") for a in answers)
    assert any(b"PRV-6217" in a for a in answers)


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
def test_qi004_an_open_connection_stops_reading_once_its_key_is_revoked(admin):
    # PGREVOKE-1, fixed: the credential is verified again before every statement; a revoked key
    # ends its open connection with FATAL 28000 PRV-6218.
    view = any_view(admin)
    st, _, body = http_call("POST", "/api/v1/keys", admin, {"name": "qi_" + uuid.uuid4().hex[:6], "days": 1})
    key = json.loads(body)
    with pg_connect(key["key"]) as conn:
        conn.execute(f"SELECT COUNT(*) FROM {view}").fetchone()
        assert http_call("DELETE", f"/api/v1/keys/{key['keyId']}", admin)[0] == 204
        with pytest.raises(Exception) as refused:
            conn.execute(f"SELECT COUNT(*) FROM {view}").fetchone()
    assert "PRV-6218" in str(refused.value)


@needs_pgwire
def test_qi004_an_open_connection_stops_reading_once_its_session_signs_out(admin):
    view = any_view(admin)
    session = login("admin", ADMIN_PASSWORD)
    with pg_connect(session) as conn:
        conn.execute(f"SELECT COUNT(*) FROM {view}").fetchone()
        assert http_call("POST", "/api/v1/auth/logout", session)[0] in (200, 204)
        with pytest.raises(Exception) as refused:
            conn.execute(f"SELECT COUNT(*) FROM {view}").fetchone()
    assert "PRV-6218" in str(refused.value)


@needs_pgwire
def test_qi031_int4_binary_parameter_against_a_bigint_column(admin):
    # PGINTPARAM-1, fixed: a binary int2/int4 parameter is read at its declared width and widened to
    # the BIGINT it is compared with, answering as the same value bound as text does.
    psycopg = pytest.importorskip("psycopg")
    from psycopg.types.numeric import Int2, Int4

    view = any_view(admin)
    with pg_connect(admin) as conn:
        description = conn.execute(f"SELECT * FROM {view}").description
        bigint = next((d.name for d in description if d.type_code == 20), None)
        if bigint is None:
            pytest.skip(f"{view} has no BIGINT column")
        def rows(param, value):
            return sorted(conn.execute(f"SELECT * FROM {view} WHERE {bigint} > {param}", [value]).fetchall(), key=repr)

        as_text = rows("%s", "1")
        assert rows("%b", Int4(1)) == as_text
        assert rows("%b", Int2(1)) == as_text


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
@pytest.mark.parametrize("statement", ["COPY {view} TO STDOUT", "DECLARE c CURSOR FOR SELECT * FROM {view}",
                                       "SELECT STREAM * FROM {view}"])
def test_qi021_copy_is_refused_as_documented(admin, statement):
    # PGCOPY-1, fixed: COPY, cursors and SELECT STREAM are refused by name, PRV-6201 0A000.
    psycopg = pytest.importorskip("psycopg")
    view = any_view(admin)
    with pg_connect(admin) as conn:
        with pytest.raises(psycopg.Error) as refused:
            conn.execute(statement.format(view=view))
        assert refused.value.sqlstate == "0A000" and "PRV-6201" in str(refused.value)
        conn.execute(f"SELECT * FROM {view}").fetchall()


@needs_pgwire
def test_qi029_connection_validation_probes_are_answered(admin):
    # PGVALIDATE-1, fixed: the probes pools and BI tools validate a connection with.
    with pg_connect(admin) as conn:
        assert conn.execute("SELECT 1").fetchone() == (1,)
        assert conn.execute("SELECT 'a'::text").fetchone() == ("a",)
        assert conn.execute("SELECT now()").fetchone()[0] is not None
        assert conn.execute("SHOW search_path").fetchone() == ('"$user", public',)
        conn.execute("SET search_path TO public")
        cur = conn.execute("SELECT 1 AS ok, current_user")
        assert [d.name for d in cur.description] == ["ok", "current_user"]
        assert cur.fetchone() == (1, "admin")


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
def test_qi054_a_locked_account_reads_like_an_unknown_one(admin):
    # LOCKENUM-1, fixed: a barred sign-in -- the right password included -- answers exactly as an
    # unknown name does (401 PRV-7010, the same message); the lock is recorded, not announced.
    name = "qi_" + uuid.uuid4().hex[:8]
    password = "Qi-Lock-Password-2026"
    http_call("POST", "/api/v1/users", admin, {"username": name, "password": password, "roles": ["reader"]})
    for _ in range(6):
        locked = http_call("POST", "/api/v1/auth/login", body={"username": name, "password": "wrong-wrong-1A"})
    right = http_call("POST", "/api/v1/auth/login", body={"username": name, "password": password})
    unknown = http_call("POST", "/api/v1/auth/login", body={"username": name + "_x", "password": "wrong-wrong-1A"})

    def said(answer):
        body = json.loads(answer[2])
        return answer[0], body.get("code"), body.get("message")

    assert said(locked) == said(right) == said(unknown)
    assert said(unknown)[:2] == (401, "PRV-7010")


def _declared_only(path, length):
    """Sends a request head declaring ``length`` body bytes and none of them; the raw answer."""
    host, port = HTTP.split("//", 1)[1].split(":")
    with socket.create_connection((host, int(port.rstrip("/"))), timeout=30) as s:
        s.sendall(f"POST {path} HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n"
                  f"Content-Length: {length}\r\n\r\n".encode())
        s.shutdown(socket.SHUT_WR)
        reply = b""
        try:
            while chunk := s.recv(4096):
                reply += chunk
        except OSError:
            pass
    return reply.decode(errors="replace")


@needs_http
def test_qi045_a_large_anonymous_login_is_refused_413_before_its_body_is_read():
    # HTTPBODY-1, fixed: refused on the declared length, before a byte of the body is read.
    reply = _declared_only("/api/v1/auth/login", 19 * 1000 * 1000)
    assert reply.startswith("HTTP/1.1 413") and '"code":"PRV-1054"' in reply, reply[:300]


@needs_http
@destructive
def test_qi045_anonymous_large_login_bodies_leave_the_node_serving():
    # HTTPBODY-1, fixed: the QI-045 attack. Each is refused 413 PRV-1054 (or the connection is closed
    # once the refused body passes server.tomcat.max-swallow-size); the node keeps serving.
    body = b'{"username":"' + b"a" * (19 * 1000 * 1000) + b'","password":"x"}'
    answers = []

    def one():
        try:
            answers.append(http_call("POST", "/api/v1/auth/login", raw=body, timeout=120)[0])
        except OSError:
            answers.append("closed")

    threads = [threading.Thread(target=one) for _ in range(30)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert http_call("GET", "/actuator/health/readiness")[0] == 200
    assert set(answers) <= {413, "closed"}, answers


@needs_flight
def test_qi059_a_declared_stream_can_be_registered_over(admin):
    # DECLSTREAM-1, fixed: the registry is told of every stream declared after start, so a stream
    # declared over POST /api/v1/streams (pravaha streams declare) is registered over by Flight.
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
@pytest.mark.parametrize("ticket", [b"\x00\xff garbage", b"NOPE:x", b"LIST"])
def test_qi071_a_garbage_ticket_is_invalid_argument(admin, ticket):
    # FLIGHTTICKET-1, fixed: INVALID_ARGUMENT with PRV-6106, not INTERNAL without a code.
    flight = pytest.importorskip("pyarrow.flight")
    client = flight.FlightClient(FLIGHT)
    options = flight.FlightCallOptions(headers=[(b"authorization", ("Bearer " + admin).encode())])
    with pytest.raises(Exception) as refused:
        client.do_get(flight.Ticket(ticket), options).read_all()
    assert not isinstance(refused.value, flight.FlightInternalError)
    assert "PRV-6106" in str(refused.value)


@needs_flight
def test_qi077_a_path_descriptor_is_unimplemented_with_a_code(admin):
    # FLIGHTTICKET-1, fixed: Flight SQL speaks command descriptors; a path one is UNIMPLEMENTED PRV-6101.
    flight = pytest.importorskip("pyarrow.flight")
    client = flight.FlightClient(FLIGHT)
    options = flight.FlightCallOptions(headers=[(b"authorization", ("Bearer " + admin).encode())])
    with pytest.raises(Exception) as refused:
        client.get_flight_info(flight.FlightDescriptor.for_path(any_view(admin)), options)
    assert not isinstance(refused.value, flight.FlightInternalError)
    assert "PRV-6101" in str(refused.value)


@needs_flight
def test_qi072_anonymous_flight_calls_are_unauthenticated():
    flight = pytest.importorskip("pyarrow.flight")
    client = flight.FlightClient(FLIGHT)
    with pytest.raises(flight.FlightUnauthenticatedError):
        list(client.list_actions())
    with pytest.raises(flight.FlightUnauthenticatedError):
        client.do_get(flight.Ticket(b"x")).read_all()
