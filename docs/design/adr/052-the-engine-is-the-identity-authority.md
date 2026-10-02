# ADR-052: the engine is the identity authority, and stores only what cannot be reversed

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; stages 1-3 and 6 built. **Stages 4 (MFA) and 5 (SSO) dropped by the owner on 2026-09-27**: simple authentication -- users, passwords, API keys, sessions -- is what Pravaha needs. **Amended 2026-10-02** (lockout, the console's cookie, revocation on open connections; below) |
| Date | 2026-09-27 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-024 (the console reaches the engine only through its API), ADR-031 (authorization at the Pravaha layer), ADR-050 (tenancy), ADR-051 (help) |

## Context

Until 0.1.3 the engine authenticated callers with a static table of bearer tokens in its YAML
(`pravaha.security.tokens`, the map key being the secret, in plaintext), and the console had one
shared password in its YAML and called the engine with one shared token. Nothing expired, nothing
could be rotated without a restart, a console action was attributed to "the console" and a self-chosen
role, and every secret sat in a configuration file.

The owner decided that Pravaha manages users, passwords and API keys **the way MAYA does**, in full --
MFA and SSO included -- with an embedded file store and MAYA's documented password policy.

## Decision

**The engine is the identity authority.** Every credential -- a person's session, an API key, later an
SSO login -- resolves in the engine to one `Principal` (id, tenant, roles) through the existing
`TokenVerifier` seam, and the existing `SecurityPolicy` decides what that principal may do. The engine
stores only derived forms of secrets:

| Secret | Stored as | Why that form |
|---|---|---|
| A password | Argon2id (m=64 MiB, t=3, p=4), self-describing `argon2id$<params>$<salt>$<hash>`; PBKDF2-HMAC-SHA512 (600,000) where Argon2 is unavailable; re-hashed upward on login | slow on purpose: a stolen file must not yield passwords |
| An API key's secret | the same KDF | a key is a long-lived password for a program |
| A session token, a reset token | SHA-256 | 256 random bits: a slow hash buys nothing and costs every request |
| A TOTP seed (stage 4) | AES-GCM under a key file in `data/keys/`, never in configuration | the engine must read it back |

**The store** is an append-only, fsync'd journal of identity events in `data/identity/`, replayed at
start into memory, the same discipline as the registry journal. No database to run; it lives on the
volume that is already backed up. Sessions are journalled at creation and end, not per request.

**Users.** `username` (unique), `display_name`, `email`, `tenant` (ADR-050), `roles`, `status`
(`active`, `disabled`), `password_hash`, `must_change_password`, `password_changed_at`, a password
history (last 5 hashes), `failed_attempts`, `locked_until`, `last_login_at`, MFA state (stage 4) and
`external_subject` (stage 5). A user is disabled, never deleted: the audit trail and the registry
journal name owners by id.

**Attributes** (added 2026-09-28, STORECLAIMS-1): name-to-value facts about a user (`region=EU`),
journalled with the user record as trailing name/value pairs (a record written before them has none,
and an older engine ignores them), replaced whole by an administrator like roles, and presented as
the principal's **claims** by every credential of the user's -- a session, an API key (exactly its
holder's; a key carries no attributes of its own), and the principal a registration is restored as or
an alert runs as. So a catalogue policy's `session_attribute('claim')` (ADR-059) applies to store
users. `via`, `session`, `key` and `mustChangePassword` are the engine's own claim names and are
refused; at most 32, values 1 to 256 characters. The audit event `user.attributes_changed` names the
attributes set and removed, never their values.

**Password policy** (`pravaha.identity.password.*`, each overridable): at least **12 characters from
3 of 4 classes**; not one of the last **5**; a **90-day** maximum age that sets `must_change_password`
at login. One code path accepts every new password -- creation, change, admin reset, reset-token
redemption -- so no surface can skip it.

**Lockout.** 5 failed logins inside 15 minutes lock the account for 30 minutes. A failure is recorded
before the refusal is answered. A wrong MFA code counts as a failure.

**API keys** are `prv_<env>_<keyid>_<secret>`:
- `<env>` is the deployment's name (`pravaha.identity.environment`, e.g. `qa`, `prod`): a QA key is
  never accepted by production;
- `<keyid>` is 12 hex characters: the lookup index and the audit handle, shown to administrators;
- `<secret>` is 192 random bits, shown **once**, at creation, and stored only as a KDF hash.

Each key has a name, a holder, **roles that are a subset of its holder's** (a key can narrow, never
widen), a **mandatory expiry** (default 90 days, at most 365), `last_used_at` (updated at most once a
minute), revocation (immediate) and rotation (a successor with the same scope; the old key expires at
the end of a 7-day overlap). A report lists keys that are unused, near expiry or superseded. A
successful check is cached for 5 seconds so a KDF does not run on every request.

**Sessions.** `prv_s_<32 random bytes>`, SHA-256 in the store. 30 minutes idle, 12 hours absolute, at
most 3 per person (the oldest is ended), and ended on password change, reset, disable or logout.

**Forced change on first login is configuration, not a default** (the owner's decision, as MAYA's
`auth.password.force_change`): with `pravaha.identity.password.force-change: true`, a new account, the
bootstrap admin and an administrator's reset all set `must_change_password`, and such a session can do
nothing but change its password (`PRV-7018`). Unset -- the default -- no account is forced.

**How people sign in is configuration too** (`pravaha.identity.mode`, as MAYA's `auth.mode`):
`password` (the default) is the mechanism above; `sso` signs everyone in through the configured
identity provider (stage 5), with break-glass `password` accounts named explicitly; `hybrid` offers
both. **SSO is used only when it is configured**: with no provider configured the mode is `password`
whatever else is set, and the node says so at startup. *(Superseded in 2.1 — SSOMODE-1, the
amendment under stage 5 below: SSO was dropped, so `password` is the only value accepted, and `sso`
or `hybrid` stops the node with `PRV-7004`.)*

**Bootstrap.** When the store has no users, the engine creates `admin` with the password
`pravaha-dev-admin` (and `must_change_password` when forced change is configured), and says so at
startup. Outside the `dev` profile the
engine **refuses to start** while that password is still `admin`'s, unless
`pravaha.identity.allow-default-admin-password` is set. The QA installer sets a generated password
instead, printed once.

**The console holds no shared key.** It signs a person in against the engine (`POST /api/v1/auth/login`),
keeps their engine session token in its signed session cookie, and calls the engine with it, so every
console action is authorised and audited as that person. CSRF: a synchronizer token per session on
every form and state-changing request. The console's own password and the `console` engine token go.

**Static tokens stay, as a legacy source.** `pravaha.security.tokens` still verifies, for an embedded
engine and for migration, but a node with an identity store logs each static token it still accepts as
deprecated. An embedded engine that never configures identity is unaffected.

**Audit.** Every login, refusal, lockout, key creation, rotation, revocation, failed key check,
password change, reset and administrative change is an `AuditEvent` on the existing audit sink, and
the file sink gains a hash chain (each entry carries the previous entry's hash) so a truncated or edited
trail is detectable.

## The engine's REST contract (stages 1-3)

All under `/api/v1`, bearer-authenticated except `auth/login` and `auth/reset/redeem`. Errors are the API's
usual JSON (`code`, `message`, `helpUrl`).

| Method and path | Body / answer | Who |
|---|---|---|
| `POST auth/login` | `{username, password}` → `{token, expiresAt, mustChangePassword, mfa: ok\|challenge\|enroll}`; 401 `PRV-7010`, 423 `PRV-7011` | anyone |
| `POST auth/logout` | → 204 | the session |
| `GET auth/me` | the principal, the user's own fields, `mfa`, `passwordExpiresAt` | any caller |
| `POST auth/password` | `{current, new}` → 204; 400 `PRV-7012` (policy, with the rule broken) | a user |
| `POST auth/reset/redeem` | `{token, password}` → 204; single use; ends every session | anyone holding a token |
| `GET users` / `POST users` | list / `{username, displayName?, email?, tenant?, roles[], password}` | admin |
| `PATCH users/{u}` | `{displayName?, email?, tenant?, status?}` | admin |
| `PUT users/{u}/roles` | `{roles[]}` | admin |
| `PUT users/{u}/attributes` | `{attributes: {name: value}}`, the whole set; the user, with `attributes` | admin |
| `POST users/{u}/password-reset` | → `{resetToken, expiresAt}`, shown once, 60 minutes | admin |
| `GET keys` (`?all=true` for admin) / `POST keys` | `{name, roles[], expiresDays, forUser?}` → `{key, keyId, expiresAt}`, key shown once | the holder; admin for a service account |
| `DELETE keys/{keyId}` | revoke → 204 | the holder or admin |
| `POST keys/{keyId}/rotate` | → `{key, keyId, expiresAt, oldExpiresAt}` | the holder or admin |
| `GET keys/report` | unused, expiring, superseded | admin |
| `GET sessions` / `DELETE sessions/{id}` | own; `?all=true` and any, for admin | any caller / admin |

Stage 4 adds `auth/mfa/{enroll,confirm,verify}` and `users/{u}/mfa-reset`; stage 5 adds
`auth/oidc/{start,callback}` and `auth/saml/{start,acs}`.

New codes: `PRV-7010` credentials refused (one message for a wrong user and a wrong password),
`PRV-7011` account locked, `PRV-7012` password refused by policy, `PRV-7013` key expired or revoked,
`PRV-7014` key for another environment, `PRV-7015` a key or grant would widen its holder's roles,
`PRV-7016` session expired, `PRV-7017` reset token invalid or used, `PRV-7018` must change password
first (only when `force-change` is configured), `PRV-7019` default admin password outside dev.

## Stages

1. Engine core: store, KDF, policy, lockout, users, keys, sessions, reset tokens, bootstrap, verifier, audit.
2. Administration: the REST contract above and `pravaha user|key|session` CLI commands.
3. Console: per-user sign-in, acting as the signed-in user, CSRF, account and admin pages.
4. ~~MFA: TOTP with sealed seeds, then WebAuthn/passkeys.~~ Dropped by the owner, 2026-09-27.
5. ~~SSO: OIDC (code flow with PKCE), then SAML.~~ Dropped by the owner, 2026-09-27. `pravaha.identity.mode` accepts only what is built: `password`. **Amended 2.1 (SSOMODE-1):** `sso` and `hybrid` were accepted and signed people in with passwords, saying so only in a startup warning; they now stop the node at start with `PRV-7004`, naming the setting and the value it accepts.
6. Migration and release: the QA installer bootstraps an admin and issues the console nothing; a check
   that tracked configuration holds no secrets; documentation; 0.2.0.

## Consequences

- A QA host moves from editing YAML to administering users and keys through the console, CLI or API.
- Every console action is attributable to a person.
- A lost identity file loses every account; it is on the data volume, and restoring it is restoring the
  volume. The file holds no reversible secret.
- MAYA's documented policy (12 and 3) is used, not its code's defaults (8 and 2), which disagree with
  its own design document.

## Amendment, 2026-10-02: lockout, the console's cookie, revocation on open connections

The adversarial QA of 2.0.0 found four places where what this record decided was weaker in practice
than in intent, and Pravaha 2.0.1 changes each. The decisions above are kept as the record; these
replace them where they disagree.

- **Lockout bars an address, not an account, and is never announced (LOCKENUM-1).** "5 failed
  logins inside 15 minutes lock the account" let anyone who knew a user name lock it, `admin`
  included, and `423 PRV-7011` for a real account against `401` for an unknown one told anyone which
  names existed. Now five failures from one address within `lockout.window` bar that address from the
  account for `lockout.duration`; ten times as many from any addresses lock the account itself for
  `lockout.duration`; and an unknown name, a wrong password and a barred sign-in all answer `401
  PRV-7010` after the same password-hash work. `PRV-7011` is no longer answered to a sign-in.
  `pravaha.identity.lockout.trusted-proxies` names the proxies, the console among them, whose
  `X-Forwarded-For` is believed.
- **The console's cookie carries no credential (COOKIETOKEN-1, LOGOUTREPLAY-1).** "Keeps their
  engine session token in its signed session cookie" put a token that works directly against Flight,
  HTTP and pgwire in a readable cookie. The cookie now holds an opaque id; the token stays in the
  console process (`routes/session_vault.py`), so a console restart signs everybody out of it and
  several console instances need sticky sessions. A cookie whose session was signed out reads as
  signed out.
- **A credential is re-verified on connections already open (PGREVOKE-1, FLIGHTPRINCIPAL-1).** The
  PostgreSQL gateway checked the password once, at sign-in; it now verifies the credential before
  every statement and ends the connection `FATAL 28000` (`PRV-6218`). A Flight subscription's
  two-second re-verification now also requires the same principal.
- **The `users` profile runs the `authenticated` policy (PERMISSIVEUSERS-1).** Turning this store on
  had left the default `permissive` policy in force, so every signed-in user could administer every
  view. A home whose catalogue imported `permissive` under that profile refuses to start with
  `PRV-7034` until the policy is set explicitly.

[`../../operations/SECURITY.md`](../../operations/SECURITY.md) has the policy as it stands.
