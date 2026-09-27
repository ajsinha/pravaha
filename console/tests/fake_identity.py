"""The engine's identity authority (ADR-052), in memory, for the console's tests.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The contract the console is written against, kept faithfully enough that a test of the console
is a test of the console's use of it:

- ``auth/login`` answers ``{token, expiresAt, mustChangePassword, mfa}``, or ``PRV-7010`` -- one
  message whether the username or the password was wrong -- or ``PRV-7011`` once 5 failures
  inside 15 minutes have locked the account for 30;
- a session is ``prv_s_<32 random bytes>``, kept only as its SHA-256; it ends after 30 minutes
  idle or 12 hours, at logout, on a password change (the others), a reset or a disable, and a
  person holds at most ``max_sessions`` (3), the oldest ending first; a call on an ended one is
  ``PRV-7016``;
- a password is kept only as a hash (the KDF is irrelevant here, so it is a salted SHA-256
  marked as such) and must pass the policy -- 12 characters from 3 of 4 classes, none of the
  last 5 -- or it is ``PRV-7012``, naming the rule;
- with ``force_change`` a new account, the bootstrap admin and an administrator's reset must
  change their password first, and such a session can do nothing else (``PRV-7018``);
- an API key is ``prv_<env>_<12 hex>_<secret>``, shown once and kept as a hash, its roles a
  subset of its holder's (``PRV-7015``), expiring in at most 365 days; rotation makes a
  successor and leaves the old key 7 days; revocation is immediate;
- administration (``users``, ``keys?all``, ``sessions?all``, the key report) needs ``admin``,
  and anything else is ``PRV-7002``.

Every refusal is raised as the engine's HTTP API would answer it, an ``EngineHttpError`` with
its status and code, so the console's own mapping is what the tests exercise.
"""
from __future__ import annotations

import dataclasses
import datetime as dt
import hashlib
import itertools
import re
import secrets
import time
from collections.abc import Callable

from core.engine import EngineHttpError

#: The bootstrap administrator the tests sign in as, and a password the policy accepts.
ADMIN = "admin"
ADMIN_PASSWORD = "Pravaha-test-admin-1"

LOCK_AFTER = 5
LOCK_WINDOW = 15 * 60
LOCK_FOR = 30 * 60
IDLE = 30 * 60
ABSOLUTE = 12 * 3600
RESET_FOR = 60 * 60
OVERLAP = 7 * 86400


_CSRF = re.compile(r'<meta name="csrf-token" content="([^"]*)"')
_FORM_CSRF = re.compile(r'name="csrf_token" value="([^"]*)"')


def csrf_of(html: str) -> str:
    """The CSRF token a console page carries, from its meta tag or its first form."""
    found = _CSRF.search(html) or _FORM_CSRF.search(html)
    return found.group(1) if found else ""


def sign_in(client, username: str = ADMIN, password: str = ADMIN_PASSWORD, next_path: str = "/home",
            *, keep_token: bool = True):
    """Signs a TestClient in the way a browser does: the form (and its CSRF token), then the
    post. With ``keep_token`` the session's token is sent as ``X-CSRF-Token`` on every later
    request, as api.js sends it -- so a test posting to a route is a test of that route, and the
    CSRF refusal is tested on its own. Returns the sign-in's answer."""
    client.headers.pop("X-CSRF-Token", None)  # a previous session's, which the form's replaces
    form = client.get("/login?next=" + next_path)
    answer = client.post("/login", data={"username": username, "password": password,
                                         "next": next_path, "csrf_token": csrf_of(form.text)},
                         follow_redirects=False)
    if keep_token:
        client.headers["X-CSRF-Token"] = csrf_of(client.get("/login").text)
    return answer


def _iso(seconds: float) -> str:
    return dt.datetime.fromtimestamp(seconds, dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _hash(secret: str, salt: str = "") -> str:
    """Only ever a hash of a secret is kept -- as the engine keeps only a KDF's."""
    return "fake-kdf$" + salt + "$" + hashlib.sha256((salt + secret).encode("utf-8")).hexdigest()


def _sha(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


@dataclasses.dataclass
class User:
    username: str
    password_hash: str
    roles: list[str]
    display_name: str = ""
    email: str = ""
    tenant: str = "public"
    status: str = "active"
    must_change: bool = False
    history: list[str] = dataclasses.field(default_factory=list)
    failures: list[float] = dataclasses.field(default_factory=list)
    locked_until: float = 0.0
    last_login: float | None = None


class FakeIdentity:
    def __init__(self, *, environment: str = "test", force_change: bool = False,
                 clock: Callable[[], float] = time.time, max_sessions: int | None = 3) -> None:
        self.environment = environment
        self.force_change = force_change
        self.clock = clock
        self.max_sessions = max_sessions
        self.users: dict[str, User] = {}
        #: By SHA-256 of the token: {id, username, created, seen, sequence}.
        self.sessions: dict[str, dict] = {}
        #: By key id: the record, with ``hash`` the only form of its secret.
        self.keys: dict[str, dict] = {}
        #: By SHA-256 of the reset token: (username, expires).
        self.resets: dict[str, tuple[str, float]] = {}
        #: Every identity call the console made, as (verb, subject): the order a test can assert.
        self.calls: list[tuple[str, str]] = []
        self._sequence = itertools.count(1)
        #: Every session token handed out -- which the engine could not list, having kept only
        #: hashes -- so a test can prove the console never repeats one on a page.
        self.issued_tokens: list[str] = []
        self.add_user(ADMIN, ADMIN_PASSWORD, ["admin", "operator", "developer", "analyst"],
                      display_name="Administrator", must_change=force_change)

    # ------------------------------------------------------------------ fixtures for a test
    def add_user(self, username: str, password: str, roles: list[str], *, must_change: bool = False,
                 **fields) -> User:
        salt = secrets.token_hex(4)
        user = User(username, _hash(password, salt), list(roles), must_change=must_change, **fields)
        user.history = [user.password_hash]
        self.users[username] = user
        return user

    def expire(self, token: str) -> None:
        """The session ends as the idle timeout would end it."""
        self.sessions.pop(_sha(token), None)

    def token_of_newest(self, username: str) -> str | None:
        """Not a real token -- the engine cannot give one back -- but the session's id, for a
        test that ends a person's session through the administrator's screen."""
        mine = [s for s in self.sessions.values() if s["username"] == username]
        return max(mine, key=lambda s: s["sequence"])["id"] if mine else None

    def seed_session(self, session_id: str, username: str, *, created: float, seen: float) -> None:
        """An open session with a fixed id and times, for a screen photographed the same way
        every time. Its token is thrown away: nobody signs in with it."""
        self.sessions[_sha(secrets.token_hex(16))] = {"id": session_id, "username": username,
                                                      "created": created, "seen": seen,
                                                      "sequence": next(self._sequence)}

    def seed_key(self, key_id: str, holder: str, name: str, roles: list[str], *, created: float,
                 expires: float, last_used: float | None = None, superseded_by: str = "") -> None:
        """A key with a fixed id, for a screen photographed the same way every time."""
        self.keys[key_id] = {"keyId": key_id, "name": name, "holder": holder, "roles": list(roles),
                             "createdAt": _iso(created), "expiresAt": _iso(expires),
                             "lastUsedAt": _iso(last_used) if last_used else None,
                             "status": "active", "supersededBy": superseded_by or None,
                             "hash": _hash(secrets.token_hex(24))}

    # ------------------------------------------------------------------ refusals
    @staticmethod
    def _refuse(status: int, code: str, message: str):
        raise EngineHttpError(status, message, code)

    def _forbidden(self, what: str):
        self._refuse(403, "PRV-7002", f"{what} needs the role [admin]")

    # ------------------------------------------------------------------ the bearer
    def principal(self, token: str | None, *, allow_must_change: bool = False) -> tuple[User, dict | None]:
        """The user a bearer names, and the session when it is one. ``PRV-7016`` for a session
        that has ended; ``PRV-7001`` for no credential; ``PRV-7013`` for a key that has."""
        if not token:
            self._refuse(401, "PRV-7001", "a credential is required")
        now = self.clock()
        if token.startswith("prv_s_"):
            session = self.sessions.get(_sha(token))
            if session is not None and (now - session["seen"] > IDLE or now - session["created"] > ABSOLUTE):
                self.sessions.pop(_sha(token), None)
                session = None
            user = self.users.get(session["username"]) if session else None
            if session is None or user is None or user.status != "active":
                self._refuse(401, "PRV-7016", "this session has expired or was ended; sign in again")
            session["seen"] = now
            if user.must_change and not allow_must_change:
                self._refuse(403, "PRV-7018", "the password must be changed before anything else")
            return user, session
        if token.startswith(f"prv_{self.environment}_"):
            parts = token.split("_", 3)
            record = self.keys.get(parts[2]) if len(parts) == 4 else None
            if record is None or record["hash"] != _hash(parts[3]):
                self._refuse(401, "PRV-7013", "this API key is not valid")
            if record["status"] != "active" or record["expiresAt"] < _iso(now):
                self._refuse(401, "PRV-7013", "this API key has expired or was revoked")
            record["lastUsedAt"] = _iso(now)
            return self.users[record["holder"]], None
        if token.startswith("prv_"):
            self._refuse(401, "PRV-7014", "this API key belongs to another environment")
        self._refuse(401, "PRV-7016", "this session has expired or was ended; sign in again")
        raise AssertionError("unreachable")

    def _admin(self, token: str | None, what: str) -> User:
        user, _ = self.principal(token)
        if "admin" not in user.roles:
            self._forbidden(what)
        return user

    # ------------------------------------------------------------------ auth/*
    def login(self, username: str, password: str) -> dict:
        self.calls.append(("login", username))
        now = self.clock()
        user = self.users.get(username)
        if user is not None and user.locked_until > now:
            self._refuse(423, "PRV-7011", f"this account is locked until {_iso(user.locked_until)}")
        if user is None or user.status != "active" or not self._verify(user, password):
            if user is not None:
                # Recorded before the refusal is answered, as the engine records it.
                user.failures = [t for t in user.failures if now - t < LOCK_WINDOW] + [now]
                if len(user.failures) >= LOCK_AFTER:
                    user.locked_until, user.failures = now + LOCK_FOR, []
                    self._refuse(423, "PRV-7011", f"this account is locked until {_iso(user.locked_until)}")
            self._refuse(401, "PRV-7010", "the username or password was not accepted")
        user.failures, user.last_login = [], now
        token = "prv_s_" + secrets.token_urlsafe(32)
        self.sessions[_sha(token)] = {"id": "s" + secrets.token_hex(6), "username": username,
                                      "created": now, "seen": now, "sequence": next(self._sequence)}
        if self.max_sessions is not None:
            mine = sorted((s for s in self.sessions.items() if s[1]["username"] == username),
                          key=lambda s: s[1]["sequence"])
            for digest, _ in mine[:-self.max_sessions]:
                del self.sessions[digest]
        self.issued_tokens.append(token)
        return {"token": token, "expiresAt": _iso(now + ABSOLUTE),
                "mustChangePassword": user.must_change, "mfa": "ok"}

    def logout(self, token: str | None) -> None:
        self.calls.append(("logout", ""))
        self.principal(token, allow_must_change=True)
        self.sessions.pop(_sha(token or ""), None)

    def me(self, token: str | None) -> dict:
        user, _ = self.principal(token, allow_must_change=True)
        return {"principal": user.username, "username": user.username, "displayName": user.display_name,
                "email": user.email, "tenant": user.tenant, "roles": list(user.roles), "mfa": "ok",
                "status": user.status, "mustChangePassword": user.must_change,
                "passwordExpiresAt": None}

    def change_password(self, token: str | None, current: str, new: str) -> None:
        user, session = self.principal(token, allow_must_change=True)
        self.calls.append(("password", user.username))
        if not self._verify(user, current):
            self._refuse(401, "PRV-7010", "the current password was not accepted")
        self._set_password(user, new)
        user.must_change = False
        # Every other session of the person ends; the one that changed it carries on.
        for digest in [d for d, s in self.sessions.items() if s["username"] == user.username and s is not session]:
            del self.sessions[digest]

    def redeem(self, reset_token: str, password: str) -> None:
        held = self.resets.pop(_sha(reset_token or ""), None)
        if held is None or held[1] < self.clock():
            self._refuse(400, "PRV-7017", "this reset token is not valid, or was already used")
        user = self.users[held[0]]
        self._set_password(user, password)
        user.must_change = False
        self._end_sessions_of(user.username)

    # ------------------------------------------------------------------ users
    def list_users(self, token: str | None) -> list[dict]:
        self._admin(token, "listing users")
        return [self._user_json(u) for u in self.users.values()]

    def create_user(self, token: str | None, fields: dict) -> dict:
        self._admin(token, "creating a user")
        username = str(fields.get("username") or "").strip()
        if not username:
            self._refuse(400, "PRV-1051", "a user needs a username")
        if username in self.users:
            self._refuse(409, "PRV-1051", f"a user named '{username}' already exists")
        self._policy(str(fields.get("password") or ""), [])
        user = self.add_user(username, str(fields["password"]), list(fields.get("roles") or []),
                             display_name=str(fields.get("displayName") or ""),
                             email=str(fields.get("email") or ""), tenant=str(fields.get("tenant") or "public"),
                             must_change=self.force_change)
        self.calls.append(("create-user", username))
        return self._user_json(user)

    def update_user(self, token: str | None, username: str, fields: dict) -> dict:
        self._admin(token, "changing a user")
        user = self._user(username)
        if "status" in fields:
            if fields["status"] not in ("active", "disabled"):
                self._refuse(400, "PRV-1051", "status is active or disabled")
            user.status = fields["status"]
            if user.status == "disabled":
                self._end_sessions_of(username)
        for name, attribute in (("displayName", "display_name"), ("email", "email"), ("tenant", "tenant")):
            if name in fields:
                setattr(user, attribute, str(fields[name] or ""))
        self.calls.append(("update-user", username))
        return self._user_json(user)

    def set_roles(self, token: str | None, username: str, roles: list[str]) -> dict:
        self._admin(token, "setting roles")
        user = self._user(username)
        user.roles = [str(r) for r in roles]
        self.calls.append(("roles", username))
        return self._user_json(user)

    def reset(self, token: str | None, username: str) -> dict:
        self._admin(token, "issuing a password reset")
        user = self._user(username)
        reset_token = "prv_r_" + secrets.token_urlsafe(24)
        expires = self.clock() + RESET_FOR
        self.resets[_sha(reset_token)] = (username, expires)
        if self.force_change:
            user.must_change = True
        self._end_sessions_of(username)
        self.calls.append(("reset", username))
        return {"resetToken": reset_token, "expiresAt": _iso(expires)}

    # ------------------------------------------------------------------ keys
    def list_keys(self, token: str | None, all_keys: bool = False) -> list[dict]:
        user = self._admin(token, "listing every key") if all_keys else self.principal(token)[0]
        return [self._key_json(k) for k in sorted(self.keys.values(), key=lambda k: k["createdAt"])
                if all_keys or k["holder"] == user.username]

    def create_key(self, token: str | None, name: str, roles: list[str], days: int,
                   for_user: str | None = None) -> dict:
        user, _ = self.principal(token)
        holder = user
        if for_user and for_user != user.username:
            self._admin(token, "a key for another account")
            holder = self._user(for_user)
        if not name:
            self._refuse(400, "PRV-1051", "a key needs a name")
        if not 1 <= int(days) <= 365:
            self._refuse(400, "PRV-1051", "a key expires in 1 to 365 days")
        wider = [r for r in roles if r not in holder.roles]
        if wider or not roles:
            self._refuse(400, "PRV-7015", f"a key's roles must be a subset of its holder's; not held: {wider}"
                         if wider else "a key must carry at least one of its holder's roles")
        return self._mint(holder.username, name, list(roles), self.clock() + int(days) * 86400)

    def revoke_key(self, token: str | None, key_id: str) -> None:
        record = self._own_key(token, key_id)
        record["status"] = "revoked"
        self.calls.append(("revoke", key_id))

    def rotate_key(self, token: str | None, key_id: str) -> dict:
        record = self._own_key(token, key_id)
        if record["status"] != "active":
            self._refuse(400, "PRV-7013", f"key {key_id} is not active")
        days_left = max(1.0, (dt.datetime.fromisoformat(record["expiresAt"]).timestamp()
                              - self.clock()))
        minted = self._mint(record["holder"], record["name"], record["roles"], self.clock() + days_left)
        old_expires = min(self.clock() + OVERLAP,
                          dt.datetime.fromisoformat(record["expiresAt"]).timestamp())
        record["expiresAt"], record["supersededBy"] = _iso(old_expires), minted["keyId"]
        self.calls.append(("rotate", key_id))
        return {**minted, "oldExpiresAt": record["expiresAt"]}

    def key_report(self, token: str | None) -> dict:
        self._admin(token, "the key report")
        soon = _iso(self.clock() + 14 * 86400)
        active = [k for k in self.keys.values() if k["status"] == "active"]
        return {"unused": [self._key_json(k) for k in active if not k["lastUsedAt"]],
                "expiring": [self._key_json(k) for k in active if k["expiresAt"] <= soon],
                "superseded": [self._key_json(k) for k in active if k["supersededBy"]]}

    # ------------------------------------------------------------------ sessions
    def list_sessions(self, token: str | None, all_sessions: bool = False) -> list[dict]:
        if all_sessions:
            self._admin(token, "listing every session")
        user, current = self.principal(token)
        rows = [s for s in self.sessions.values() if all_sessions or s["username"] == user.username]
        rows.sort(key=lambda s: -s["sequence"])
        return [{"id": s["id"], "username": s["username"], "createdAt": _iso(s["created"]),
                 "lastSeenAt": _iso(s["seen"]), "expiresAt": _iso(min(s["seen"] + IDLE, s["created"] + ABSOLUTE)),
                 "current": s is current} for s in rows]

    def end_session(self, token: str | None, session_id: str) -> None:
        user, _ = self.principal(token)
        for digest, s in list(self.sessions.items()):
            if s["id"] == session_id:
                if s["username"] != user.username and "admin" not in user.roles:
                    self._forbidden("ending another person's session")
                del self.sessions[digest]
                self.calls.append(("end-session", s["username"]))
                return
        self._refuse(404, "PRV-1051", f"no session {session_id}")

    # ------------------------------------------------------------------ the rest
    def _verify(self, user: User, password: str) -> bool:
        _, salt, _ = user.password_hash.split("$", 2)
        return secrets.compare_digest(user.password_hash, _hash(password, salt))

    @staticmethod
    def _policy(password: str, history: list[str]) -> None:
        classes = sum([any(c.islower() for c in password), any(c.isupper() for c in password),
                       any(c.isdigit() for c in password), any(not c.isalnum() for c in password)])
        if len(password) < 12 or classes < 3:
            raise EngineHttpError(400, "a password needs at least 12 characters, from 3 of: lower case, "
                                  "upper case, digits, symbols", "PRV-7012")
        for old in history:
            _, salt, _ = old.split("$", 2)
            if old == _hash(password, salt):
                raise EngineHttpError(400, "a password may not be one of your last 5", "PRV-7012")

    def _set_password(self, user: User, password: str) -> None:
        self._policy(password, user.history[-5:])
        salt = secrets.token_hex(4)
        user.password_hash = _hash(password, salt)
        user.history = (user.history + [user.password_hash])[-5:]

    def _end_sessions_of(self, username: str) -> None:
        for digest in [d for d, s in self.sessions.items() if s["username"] == username]:
            del self.sessions[digest]

    def _user(self, username: str) -> User:
        user = self.users.get(username)
        if user is None:
            self._refuse(404, "PRV-1051", f"no user named '{username}'")
        return user

    def _own_key(self, token: str | None, key_id: str) -> dict:
        user, _ = self.principal(token)
        record = self.keys.get(key_id)
        if record is None:
            self._refuse(404, "PRV-7013", f"no key {key_id}")
        if record["holder"] != user.username and "admin" not in user.roles:
            self._forbidden("another person's key")
        return record

    def _mint(self, holder: str, name: str, roles: list[str], expires: float) -> dict:
        key_id = secrets.token_hex(6)
        secret = secrets.token_urlsafe(24)
        self.keys[key_id] = {"keyId": key_id, "name": name, "holder": holder, "roles": list(roles),
                             "createdAt": _iso(self.clock()), "expiresAt": _iso(expires), "lastUsedAt": None,
                             "status": "active", "supersededBy": None, "hash": _hash(secret)}
        self.calls.append(("create-key", key_id))
        return {"key": f"prv_{self.environment}_{key_id}_{secret}", "keyId": key_id, "expiresAt": _iso(expires)}

    @staticmethod
    def _user_json(user: User) -> dict:
        return {"username": user.username, "displayName": user.display_name, "email": user.email,
                "tenant": user.tenant, "roles": list(user.roles), "status": user.status,
                "mustChangePassword": user.must_change,
                "lockedUntil": _iso(user.locked_until) if user.locked_until else None,
                "lastLoginAt": _iso(user.last_login) if user.last_login else None}

    @staticmethod
    def _key_json(record: dict) -> dict:
        return {k: v for k, v in record.items() if k != "hash"}
