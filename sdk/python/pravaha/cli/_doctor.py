"""``pravaha doctor``: is this machine ready to talk to Pravaha, and is the node it names well?

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

One line per check: ``GREEN`` (fine), ``YELLOW`` (works, but something is missing, unusual or
about to expire) or ``RED`` (will not work), each that is not green with the ``fix:`` that makes it
so. A check that could not be made because an earlier one failed -- the node did not answer, so
nobody asked who you are -- is ``SKIPPED``, never a crash: an engine that is down still gets a
whole report. Then ``doctor: N red, M yellow``; the exit is ``1`` on any red, else ``0``.

Nothing is changed. The token file's mode is read, never set; ports are probed with a TCP connect to
127.0.0.1 and nothing is sent on them; the node is asked what ``health``, ``version``, ``queries``
and ``whoami`` already ask. No line ever holds a secret: the token is named by where it came from,
and every detail is scrubbed of it before it prints.
"""

from __future__ import annotations

import datetime
import os
import re
import shutil
import socket
import ssl
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Any, Callable, Mapping, Optional

import pravaha
from pravaha.cli._common import EXIT_OK, EXIT_REFUSED, Context
from pravaha.cli._settings import contexts_file, token_file
from pravaha.errors import (
    InvalidOptionsError,
    InvalidTlsOptionsError,
    MalformedEndpointError,
    PravahaError,
)
from pravaha.rest import ApiError, _ssl_context

GREEN, YELLOW, RED, SKIPPED = "GREEN", "YELLOW", "RED", "SKIPPED"
MIN_JAVA = 21
#: Used when the package's metadata is not installed (a source checkout on PYTHONPATH).
REQUIRES_PYTHON = ">=3.9"
#: The node's HTTP API, its Flight port and the console's: what a local install listens on.
LOCAL_PORTS = ((18080, "the node's HTTP API"), (19090, "the node's Flight port"),
               (17070, "the console"))
#: Without --timeout (or PRAVAHA_TIMEOUT), a doctor waits this long for any one answer, not the
#: 30 s a query may need: a report should arrive while the person is still looking at it.
DEFAULT_TIMEOUT = 10.0
_LOOPBACK = ("localhost", "127.0.0.1", "::1", "[::1]")


def check(status: str, name: str, detail: str, fix: str = "") -> "dict[str, str]":
    return {"name": name, "status": status, "detail": detail, "fix": fix}


def _ms(started: float) -> str:
    return f"{(time.monotonic() - started) * 1000:.0f} ms"


def _words(exc: BaseException) -> str:
    message = getattr(exc, "message", None)
    return message if isinstance(message, str) and message else str(exc)


# ---------------------------------------------------------------------------------- local


def _requires_python() -> str:
    try:
        from importlib.metadata import PackageNotFoundError, metadata

        try:
            return str(metadata("pravaha")["Requires-Python"] or REQUIRES_PYTHON)
        except PackageNotFoundError:
            return REQUIRES_PYTHON
    except ImportError:  # pragma: no cover - 3.8 has it; kept for an odd interpreter
        return REQUIRES_PYTHON


def python_check(version: "tuple[int, ...]" = tuple(sys.version_info[:3])) -> "dict[str, str]":
    wanted = _requires_python()
    match = re.search(r">=\s*(\d+)\.(\d+)", wanted)
    floor = (int(match.group(1)), int(match.group(2))) if match else (3, 9)
    text = ".".join(str(part) for part in version)
    if tuple(version[:2]) < floor:
        return check(RED, "python", f"Python {text}; pravaha needs {wanted}",
                     f"install Python {floor[0]}.{floor[1]} or later and reinstall pravaha with it")
    return check(GREEN, "python", f"Python {text} ({sys.executable}); needs {wanted}")


def pyarrow_check() -> "dict[str, str]":
    try:
        import pyarrow  # noqa: F401
    except ImportError:
        return check(YELLOW, "pyarrow", "not installed: the Flight commands (query, register, "
                     "queries, subscribe ...) cannot run; the HTTP ones can",
                     'pip install "pravaha[flight]"')
    try:
        import pyarrow.flight  # noqa: F401
    except ImportError as exc:
        return check(RED, "pyarrow", f"pyarrow {pyarrow.__version__} has no Flight: {exc}",
                     'pip install --force-reinstall "pravaha[flight]"   (a pyarrow built with Flight)')
    return check(GREEN, "pyarrow", f"pyarrow {pyarrow.__version__}, with Flight")


def cli_check() -> "dict[str, str]":
    return check(GREEN, "cli", f"pravaha {pravaha.__version__} ({Path(pravaha.__file__).parent})")


def _java_feature(home: Optional[str], java: Optional[str], timeout: float) -> "tuple[Optional[int], str]":
    """(feature version, where) from JAVA_HOME's release file, else from ``java -version``."""
    if home:
        try:
            text = (Path(home) / "release").read_text(encoding="utf-8", errors="replace")
            match = re.search(r'^JAVA_VERSION="(\d+)', text, re.M)
            if match:
                return int(match.group(1)), f"JAVA_HOME={home}"
        except OSError:
            pass
        candidate = Path(home) / "bin" / "java"
        java = str(candidate) if candidate.exists() else java
    if not java:
        return None, f"JAVA_HOME={home}" if home else ""
    try:
        done = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as exc:
        return None, f"{java} ({exc})"
    match = re.search(r'version "(\d+)(?:\.(\d+))?', done.stderr + done.stdout)
    if not match:
        return None, java
    major = int(match.group(1))
    if major == 1:  # "1.8.0_402" is Java 8
        major = int(match.group(2) or 8)
    return major, f"JAVA_HOME={home}" if home else java


def java_check(environ: Mapping[str, str], timeout: float,
               which: Callable[[str], Optional[str]] = shutil.which) -> "dict[str, str]":
    """tools/jdk.sh's rule: JAVA_HOME if set, else ``java`` on PATH; 21 or later."""
    home = environ.get("JAVA_HOME") or None
    java = None if home else which("java")
    if not home and not java:
        return check(YELLOW, "java", "no JAVA_HOME and no java on PATH: only pravaha-engine (the "
                     "offline tool) and a local node need one; this CLI does not",
                     f"install a JDK {MIN_JAVA} or later and set JAVA_HOME, if you run pravaha-engine")
    feature, where = _java_feature(home, java, timeout)
    if feature is None:
        return check(RED, "java", f"{where}: cannot tell which Java this is",
                     f"point JAVA_HOME at a JDK {MIN_JAVA} or later")
    if feature < MIN_JAVA:
        return check(RED, "java", f"{where} is Java {feature}; Pravaha runs on Java {MIN_JAVA} or later",
                     f"point JAVA_HOME at a JDK {MIN_JAVA}+, e.g. export "
                     f"JAVA_HOME=/usr/lib/jvm/java-{MIN_JAVA}-openjdk-amd64")
    return check(GREEN, "java", f"{where}: Java {feature}")


def token_file_check(ctx: Context, environ: Mapping[str, str]) -> "dict[str, str]":
    path = token_file(environ)
    source = ctx.settings.token_source
    using = {"flag": "--token", "env": "PRAVAHA_TOKEN", "file": "this file",
             "context": f"context {ctx.settings.context}"}.get(source or "", "none")
    if not path.exists():
        return check(GREEN, "token file", f"{path}: none saved (pravaha login --save writes it); "
                     f"token from {using}")
    try:
        mode = path.stat().st_mode & 0o777
    except OSError as exc:
        return check(RED, "token file", f"{path}: cannot be read ({exc.strerror or exc})",
                     f"ls -l {path}")
    if mode & 0o077:
        return check(RED, "token file", f"{path} is mode {mode:04o}: others on this machine may "
                     f"read your token; token from {using}", f"chmod 600 {path}")
    return check(GREEN, "token file", f"{path} is mode {mode:04o}; token from {using}")


def context_check(ctx: Context, environ: Mapping[str, str]) -> "dict[str, str]":
    """Which context is in use, what chose it, and whether its file is private."""
    path = contexts_file(environ)
    name, source = ctx.settings.context, ctx.settings.context_source
    chosen = {"flag": "--context", "env": "PRAVAHA_CONTEXT",
              "current": "pravaha context use"}.get(source or "", "")
    using = (f"{name} (chosen by {chosen}): {ctx.settings.http}, {ctx.settings.url}" if name
             else "none in use: flags, environment and defaults decide")
    if not path.exists():
        return check(GREEN, "context", using)
    mode = path.stat().st_mode & 0o777
    if mode & 0o077:
        return check(RED, "context", f"{using}; {path} is mode {mode:04o}: others on this machine "
                     "may read the tokens in it", f"chmod 600 {path}")
    return check(GREEN, "context", using)


# ---------------------------------------------------------------------------------- TLS


def _host_port(url: str, default_port: int) -> "tuple[str, int]":
    if "://" not in url:
        url = "tls://" + url
    parsed = urllib.parse.urlsplit(url.split(",")[0])
    return parsed.hostname or "localhost", parsed.port or default_port


def tls_check(name: str, url: str, default_port: int, ctx: Context, timeout: float,
              now: Optional[float] = None) -> "dict[str, str]":
    """Trust, hostname and expiry of the certificate at ``url``, verified exactly as the SDK's
    HTTPS client verifies it (:func:`pravaha.rest._ssl_context`)."""
    tls = ctx.settings.tls
    host, port = _host_port(url, default_port)
    if tls.disable_hostname_verification:
        return check(YELLOW, name, f"{host}:{port}: verification is off (--tls-no-verify): any "
                     "certificate is accepted, so its expiry is not checked",
                     "pass --tls-ca <the CA's PEM> instead of --tls-no-verify")
    if tls.trust_store is not None or tls.key_store is not None:
        return check(YELLOW, name, f"{host}:{port}: a JKS/PKCS12 store is checked by the Flight "
                     "call itself; its expiry is not read here",
                     "export the CA as PEM and pass --tls-ca to have doctor read the expiry")
    try:
        context = _ssl_context(tls)
    except (OSError, ssl.SSLError) as exc:
        return check(RED, name, f"the TLS material cannot be loaded: {exc}",
                     "check --tls-ca / --tls-cert / --tls-key name readable PEM files")
    server_name = tls.override_hostname or host
    try:
        with socket.create_connection((host, port), timeout=timeout) as raw:
            with context.wrap_socket(raw, server_hostname=server_name) as wrapped:
                cert = wrapped.getpeercert() or {}
    except ssl.SSLCertVerificationError as exc:
        reason = exc.verify_message or str(exc)
        if exc.verify_code == 10:  # X509_V_ERR_CERT_HAS_EXPIRED
            return check(RED, name, f"{host}:{port}: the certificate has expired",
                         "renew the node's certificate")
        if "hostname" in reason.lower() or "ip address mismatch" in reason.lower():
            return check(RED, name, f"{host}:{port}: the certificate does not name "
                         f"{server_name}: {reason}",
                         "connect by a name the certificate holds, or pass --tls-override-hostname")
        return check(RED, name, f"{host}:{port}: the certificate is not trusted: {reason}",
                     "pass --tls-ca <the CA that signed the node's certificate> (env PRAVAHA_TLS_CA)")
    except (OSError, ssl.SSLError) as exc:
        return check(RED, name, f"{host}:{port}: no TLS handshake: {exc}",
                     "check the node is up and serves TLS on this port")
    expires_text = str(cert.get("notAfter") or "")
    if not expires_text:
        return check(YELLOW, name, f"{host}:{port}: trusted, but the expiry was not given")
    expires = ssl.cert_time_to_seconds(expires_text)
    when = datetime.datetime.fromtimestamp(expires, datetime.timezone.utc).date().isoformat()
    days = (expires - (time.time() if now is None else now)) / 86400
    if days < 0:
        return check(RED, name, f"{host}:{port}: the certificate expired on {when}",
                     "renew the node's certificate")
    if days < 30:
        return check(YELLOW, name, f"{host}:{port}: trusted, names {server_name}; expires {when} "
                     f"(in {days:.0f} days)", "renew the node's certificate before it expires")
    return check(GREEN, name, f"{host}:{port}: trusted, names {server_name}; expires {when}")


# ---------------------------------------------------------------------------------- the node


def _major_minor(text: str) -> "Optional[tuple[int, int]]":
    match = re.match(r"\s*v?(\d+)\.(\d+)", text or "")
    return (int(match.group(1)), int(match.group(2))) if match else None


def version_check(node_version: Optional[str]) -> "dict[str, str]":
    mine = pravaha.__version__
    theirs = _major_minor(node_version or "")
    ours = _major_minor(mine)
    if not node_version or theirs is None or ours is None:
        return check(YELLOW, "version", f"cli {mine}; the node's version is not known",
                     "pravaha version (the node's /api/v1/status may need a token)")
    if theirs[0] != ours[0]:
        return check(RED, "version", f"cli {mine}, node {node_version}: different major versions",
                     f'pip install "pravaha=={node_version}"   (a CLI of the node\'s major version)')
    if theirs[1] != ours[1]:
        return check(YELLOW, "version", f"cli {mine}, node {node_version}: different minor versions",
                     f'pip install "pravaha=={node_version}"   to match the node')
    return check(GREEN, "version", f"cli {mine}, node {node_version}")


def http_checks(ctx: Context) -> "tuple[list[dict[str, str]], bool]":
    """(checks, whether the node answered)."""
    where = ctx.settings.http
    started = time.monotonic()
    try:
        answer = ctx.api.health()
    except ApiError as exc:
        if exc.status == 0:
            return [check(RED, "http", f"{where} did not answer: {_words(exc)}",
                          "start the node, or pass --http (env PRAVAHA_HTTP) naming it")], False
        answer = {"status": f"HTTP {exc.status}"}
    except PravahaError as exc:  # refused before sending: a token over plaintext, say
        return [check(RED, "http", f"{where}: {_words(exc)}",
                      "use https://, or pass --insecure-token for a loopback node")], False
    state = str(answer.get("status", "UNKNOWN"))
    took = _ms(started)
    if state == "UP":
        found = check(GREEN, "http", f"{where} answered in {took}: health UP")
    elif state == "DEGRADED":
        found = check(YELLOW, "http", f"{where} answered in {took}: health DEGRADED (a feed "
                      "stopped; every view still answers)", "pravaha queries --verbose")
    else:
        found = check(RED, "http", f"{where} answered in {took}: health {state}",
                      "pravaha health; then the node's log")
    out = [found]
    try:
        node = ctx.api.status()
        out.append(version_check(str(node.get("version") or "") or None))
    except PravahaError:
        out.append(version_check(None))
    return out, True


def flight_check(ctx: Context, have_pyarrow: bool) -> "dict[str, str]":
    where = ctx.settings.url
    if not have_pyarrow:
        return check(SKIPPED, "flight", f"{where}: not asked (no pyarrow)")
    started = time.monotonic()
    try:
        listed = ctx.client.queries()
    except (InvalidOptionsError, InvalidTlsOptionsError, MalformedEndpointError) as exc:
        # Refused before anything was sent: a token over plaintext grpc://, say.
        return check(RED, "flight", f"{where}: {_words(exc)}",
                     "use grpc+tls://, or pass --insecure-token for a loopback node")
    except PravahaError as exc:
        if getattr(exc, "code", None) == 1040:
            return check(RED, "flight", f"{where} did not answer: {_words(exc)}",
                         "start the node, or pass --url (env PRAVAHA_URL) naming its Flight port")
        return check(YELLOW, "flight", f"{where} answered in {_ms(started)}, and refused: "
                     f"{_words(exc)}", "pravaha whoami; pravaha login --save")
    return check(GREEN, "flight", f"{where} answered in {_ms(started)}: {len(listed)} "
                 "continuous queries you may see")


def _instant(text: Any) -> Optional[datetime.datetime]:
    if not text:
        return None
    try:
        # Python 3.9's parser takes at most six fractional digits; Java writes up to nine.
        trimmed = re.sub(r"(\.\d{6})\d+", r"\1", str(text)).replace("Z", "+00:00")
        return datetime.datetime.fromisoformat(trimmed)
    except ValueError:
        return None


def auth_checks(ctx: Context, now: Optional[datetime.datetime] = None) -> "list[dict[str, str]]":
    if not ctx.settings.token:
        return [check(YELLOW, "auth", "no token: every call is anonymous, which a node with "
                      "identity on refuses", "pravaha login --user <name> --save")]
    try:
        me = ctx.api.me()
    except ApiError as exc:
        if exc.status in (401, 403):
            return [check(RED, "auth", f"the node refused the token ({exc.status}): {_words(exc)}",
                          "pravaha login --user <name> --save   (or a new PRAVAHA_TOKEN)")]
        return [check(YELLOW, "auth", f"whoami was not answered: {_words(exc)}", "pravaha whoami")]
    except PravahaError as exc:
        return [check(RED, "auth", _words(exc), "use https://, or --insecure-token on loopback")]
    roles = ",".join(sorted(me.get("roles") or [])) or "none"
    via = me.get("via") or "token"
    detail = (f"{me.get('username') or me.get('principal')} (tenant {me.get('tenant') or '-'}), "
              f"via {via}, roles {roles}")
    out = [check(YELLOW if me.get("mustChangePassword") else GREEN, "auth", detail,
                 "pravaha password" if me.get("mustChangePassword") else "")]
    if me.get("mustChangePassword"):
        out[0]["detail"] += "; this account must change its password"
    expires: Optional[datetime.datetime] = None
    if via == "session":
        try:
            current = [s for s in ctx.api.sessions() if s.get("current")]
            expires = _instant(current[0].get("expiresAt")) if current else None
        except PravahaError:
            expires = None
    now = now or datetime.datetime.now(datetime.timezone.utc)
    if expires is None:
        out.append(check(GREEN, "token expiry", f"not known for a {via} token"))
    elif expires <= now:
        out.append(check(RED, "token expiry", f"expired at {expires.isoformat()}",
                         "pravaha login --user <name> --save"))
    elif expires - now < datetime.timedelta(days=1):
        hours = (expires - now).total_seconds() / 3600
        out.append(check(YELLOW, "token expiry", f"expires {expires.isoformat()} (in {hours:.1f} h)",
                         "pravaha login --user <name> --save   before it does"))
    else:
        out.append(check(GREEN, "token expiry", f"expires {expires.isoformat()}"))
    return out


def port_checks(timeout: float) -> "list[dict[str, str]]":
    out = []
    for port, what in LOCAL_PORTS:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(min(timeout, 1.0))
            used = probe.connect_ex(("127.0.0.1", port)) == 0
        out.append(check(GREEN, f"port {port}", ("in use" if used else "free") + f" ({what}'s default)"))
    return out


# ---------------------------------------------------------------------------------- the command


def _timeout(ctx: Context, environ: Mapping[str, str]) -> float:
    given = getattr(ctx.args, "timeout", None) is not None or bool(environ.get("PRAVAHA_TIMEOUT"))
    return ctx.settings.timeout if given else DEFAULT_TIMEOUT


def run_checks(ctx: Context, environ: Optional[Mapping[str, str]] = None) -> "list[dict[str, str]]":
    env = os.environ if environ is None else environ
    timeout = _timeout(ctx, env)
    ctx.settings.timeout = timeout
    results = [python_check(), cli_check(), pyarrow_check(), java_check(env, timeout),
               token_file_check(ctx, env), context_check(ctx, env)]
    have_pyarrow = results[2]["status"] == GREEN
    http, url = ctx.settings.http, ctx.settings.url
    if http.startswith("https://"):
        results.append(tls_check("tls http", http, 443, ctx, timeout))
    if not url.startswith(("grpc://", "grpc+tcp://")):
        results.append(tls_check("tls flight", url, 19090, ctx, timeout))
    answered_checks, answered = http_checks(ctx)
    results += answered_checks
    results.append(flight_check(ctx, have_pyarrow))
    if answered:
        results += auth_checks(ctx)
    else:
        results.append(check(SKIPPED, "auth", "not asked: the HTTP API did not answer"))
    host = urllib.parse.urlsplit(http).hostname or ""
    if getattr(ctx.args, "local", False) or host in _LOOPBACK:
        results += port_checks(timeout)
    token = ctx.settings.token
    # Belt and braces: no detail may carry the token, whatever an error message quoted. (A token
    # of a few characters is a test's, and would scrub every word it occurs in.)
    if token and len(token) >= 8:
        for item in results:
            for key in ("detail", "fix"):
                item[key] = item[key].replace(token, "<token>")
    return results


def exit_code(results: "list[dict[str, str]]") -> int:
    return EXIT_REFUSED if any(r["status"] == RED for r in results) else EXIT_OK


def doctor(ctx: Context) -> int:
    results = run_checks(ctx)
    out = ctx.out
    if out.json_mode:
        out.json(results)
        return exit_code(results)
    paint = {GREEN: out.good, RED: out.bad, YELLOW: out.caution,
             SKIPPED: out.dim}
    width = max(len(r["name"]) for r in results)
    for r in results:
        out.line(f"{paint[r['status']](r['status'].ljust(7))}  {r['name'].ljust(width)}  {r['detail']}")
        if r["fix"] and r["status"] != GREEN:
            out.line(f"{'':7}  {'':{width}}  fix: {r['fix']}")
    reds = sum(r["status"] == RED for r in results)
    yellows = sum(r["status"] == YELLOW for r in results)
    out.line(f"doctor: {reds} red, {yellows} yellow")
    return exit_code(results)
