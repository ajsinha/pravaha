# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The deck, as data. Act 3: security, drawn as flows.

The three seams; then each flow as a numbered step tree -- a person signing in to the
console, an API key reaching Flight and the PostgreSQL gateway and being revoked, the
authorization check on a read, the audit trail -- then the credentials the engine
keeps, row filters and masks as policies, and transport security.

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

SLIDES: list[dict[str, Any]] = [
    {
        "kind": "act",
        "num": "3",
        "title": "Security, drawn as flows",
        "sub": "Who is this, what may they read, and what were they told — step by step.",
        "source": "Source: docs/operations/SECURITY.md; docs/design/adr/031, 052, 059.",
        "talk": "Security is easiest to trust when you can follow it. This act draws each flow as the steps "
        "it takes, with the code each refusal carries.",
    },
    {
        "kind": "split",
        "kicker": "Three seams, one rule",
        "title": "Authentication, authorization and audit are kept apart",
        "left": {
            "head": "The seams",
            "rows": [
                ["Seam", "Answers"],
                ["TokenVerifier", "Who is this? A credential in, a Principal (id, tenant, roles) out"],
                ["SecurityPolicy", "What may they read, register, administer, write to? Allow, allow "
                 "with a row filter, or deny"],
                ["AuditSink", "What were they told? Every decision, allows included"],
            ],
            "col_w": [1.3, 2.6],
            "size": 14,
        },
        "right": {
            "head": "Why it is enforced in Pravaha, not the store",
            "items": [
                ("A view is data the store has never seen",
                 "No record in the store carries permissions for a computed total."),
                ("A feed is read once and shared",
                 "Authorizing at the source would mean a read per person, or a superuser read that "
                 "enforces nothing."),
                ("A continuous query has no caller",
                 "It runs for months while nobody is connected; there is no session to carry down."),
            ],
            "size": 14,
        },
        "takeaway": "One verifier for every transport, one policy for every read, one trail for every "
        "decision.",
        "source": "Source: docs/operations/SECURITY.md 'The three seams', 'Authorization is enforced here, "
        "not in the store'; docs/design/adr/031-authorization-at-the-pravaha-layer.md 'Why it cannot be "
        "delegated to the store'.",
        "talk": "Three seams, kept apart so you can bring your own identity provider without rewriting the "
        "rules. And the reason the engine enforces it rather than the database: the answer is something the "
        "database has never seen.",
    },
    {
        "kind": "tree",
        "kicker": "Flow · signing in to the console",
        "title": "Authentication — a person signs in to the console",
        "actors": "Browser  →  Console  →  Engine",
        "code": [
            "1. Browser → any page but landing, about, help",
            "   └─ not signed in → the sign-in page",
            "2. Browser → POST user name + password (+ CSRF)",
            "   └─ Console → POST /api/v1/auth/login",
            "      ├─ X-Forwarded-For: the browser's address",
            "      ├─ password checked against Argon2id",
            "      ├─ refused → 401 PRV-7010, alike for all",
            "      └─ accepted → session token prv_s_…",
            "3. Console keeps the token in its own process",
            "   ├─ under an opaque id: the cookie holds the id",
            "   └─ HttpOnly, SameSite=Lax, Secure over https",
            "4. Each click → the token on every REST/Flight call",
            "   └─ engine authorizes and audits it as you",
            "5. 30 min idle, 12 h in all, or sign-out",
            "   └─ the next request → sign in again",
        ],
        "code_w": 0.6,
        "items": [
            ("Lockout that names nobody",
             "Five failures from one address in 15 minutes bar that address for 30; fifty from anywhere "
             "lock the account."),
            ("A copied cookie is not a credential",
             "It holds no token for Flight, HTTP or the PostgreSQL gateway."),
            ("Headers on every response",
             "A CSP, frame-ancestors 'none', X-Frame-Options DENY, nosniff; HSTS over https."),
            ("Not built",
             "MFA and single sign-on: not in this release."),
        ],
        "size": 14,
        "takeaway": "The console holds no credential of its own: the engine checks every action, as you.",
        "source": "Source: docs/operations/SECURITY.md 'The console acts as the person signed in' (login "
        "endpoint, opaque id, cookie flags, CSRF, headers, LOGOUTREPLAY-1), 'Users, passwords, API keys and "
        "sessions (ADR-052)' (Argon2id, sessions 30 min idle, 12 h, lockout LOCKENUM-1, SSO and MFA not "
        "built); docs/guides/TROUBLESHOOTING.md (PRV-7010).",
        "talk": "Follow the numbers. The console never holds a credential of its own: it signs you in "
        "against the engine and keeps only the session token, in its own memory. Your browser gets an "
        "opaque id. From then on the engine checks and records every action as you.",
    },
    {
        "kind": "tree",
        "kicker": "Flow · an API key, from creation to revocation",
        "title": "Authentication — a script reads with an API key",
        "actors": "Administrator · Script  →  Engine (Flight, PostgreSQL, REST)",
        "code": [
            "1. Admin → pravaha key create etl --days 90",
            "   ├─ prv_<env>_<keyid>_<secret>, shown once",
            "   ├─ roles: a subset of the holder's",
            "   └─ expiry mandatory: 90 days default, 365 max",
            "2. Script → Flight call, authorization: Bearer <key>",
            "   ├─ grpc+tls:// — over grpc:// the SDK refuses",
            "   └─ TokenVerifier → Principal (id, tenant, roles)",
            "3. Long-lived reads keep checking",
            "   ├─ Flight subscription: re-verified every 2 s",
            "   └─ PostgreSQL: re-verified before each statement",
            "4. Admin → pravaha key revoke <keyId>",
            "   ├─ Flight stream ends UNAUTHENTICATED",
            "   └─ PostgreSQL ends FATAL 28000, PRV-6218",
            "5. Rotation keeps the old key 7 days, then it ends",
        ],
        "code_w": 0.6,
        "items": [
            ("Stored, never reversible",
             "The key's secret is held under the password KDF; a key from another environment is "
             "refused."),
            ("Revocation reaches open connections",
             "Revoking a key, ending a session or disabling a user takes effect on every door at the "
             "credential's next use."),
            ("A broken verifier fails closed",
             "A custom TokenVerifier that returns nothing or throws is PRV-7001 on every surface."),
        ],
        "size": 14,
        "takeaway": "A revoked key stops reading at its next statement — even on a connection already open.",
        "source": "Source: docs/operations/SECURITY.md 'Users, passwords, API keys and sessions (ADR-052)' "
        "(key format, shown once, roles subset, expiry, 7-day rotation, revocation reaches open connections: "
        "Flight every two seconds, pgwire every statement, PRV-6218), 'Transport' (SDKs refuse a token over "
        "plaintext); pravaha-console/content/topics/cli-reference.md 'Identity' (pravaha key create <name> "
        "--days N, revoke <keyId>); docs/project/RELEASE_NOTES.md '2.4.0' (J21-1: PRV-7001 on every surface).",
        "talk": "Automation uses keys, not passwords. Note step three: a long-lived connection does not get "
        "to keep reading after the key is revoked. That was a real defect in 2.0.0, found by the adversarial "
        "round, and fixed.",
    },
    {
        "kind": "tree",
        "kicker": "Flow · the check on every read",
        "title": "Authorization — what happens before a row leaves",
        "actors": "Any client (Flight · PostgreSQL · REST)  →  Engine",
        "code": [
            "1. SELECT spend FROM hourly_spend WHERE user_id='u42'",
            "2. TokenVerifier → Principal (id, tenant, roles,",
            "   │  attributes such as region=EU)",
            "   └─ nothing, ANONYMOUS or a throw → PRV-7001",
            "3. The name resolves inside the caller's tenant",
            "   └─ another tenant's view: a name nothing holds",
            "4. Policy and catalogue grants: SELECT on it?",
            "   ├─ no → PRV-7002, recorded as DENY",
            "   └─ yes, with the row filters and masks bound",
            "5. Row filter: only if the view keeps its columns",
            "   ├─ otherwise refused → PRV-7003",
            "   └─ a masked column compared → PRV-7006",
            "6. AuditSink ← principal, action, target, ALLOW",
            "7. Rows leave, filtered and masked",
        ],
        "code_w": 0.6,
        "items": [
            ("The same on every path",
             "Flight and point reads, PostgreSQL text and binary, subscriptions, queries built on the "
             "view, and alerts evaluated as their owner."),
            ("A registration is a standing read",
             "Read is asked for every stream in its plan; a sink is a standing write."),
            ("A revoke ends an open stream",
             "SUBSCRIBE is re-asked every two seconds."),
        ],
        "size": 14,
        "takeaway": "When the engine cannot apply a rule exactly, it refuses — it never returns rows it "
        "cannot vouch for.",
        "source": "Source: docs/operations/SECURITY.md 'The three seams', 'Row filters, and the rule that "
        "bounds them', 'Tenants', 'What a registration is allowed to read', 'Row filters and masks as "
        "catalogue objects'; docs/guides/CONCEPTS.md §6 (the soundness rule); docs/guides/TROUBLESHOOTING.md "
        "(PRV-7001, 7002, 7003, 7006); docs/design/adr/059 'Phase 1, as built' (mid-stream revocation every "
        "two seconds).",
        "talk": "This is the one path every read takes, whatever protocol it arrives on. Step five is the "
        "soundness rule from the principles: if the query aggregated the filter's column away, the engine "
        "cannot separate the rows any more, so it refuses rather than guess.",
    },
    {
        "kind": "tree",
        "kicker": "Flow · the audit trail",
        "title": "Audit — every decision, allows included",
        "actors": "Every decision  →  AuditSink  →  file · GET /api/v1/audit",
        "code": [
            "1. A decision: allow or deny, on any transport",
            "   └─ one AuditSink object for Flight, HTTP, engine",
            "2. The event: time, principal (id, tenant, roles),",
            "   │  action, target, ALLOW/DENY, reason, the SQL",
            "   └─ never the credential; claims never printed",
            "3. audit: file → one JSON line each, rw-------",
            "   ├─ rotates by size, keeps N generations",
            "   └─ a daemon thread behind a bounded queue",
            "4. Trouble is never silent",
            "   ├─ queue full → an audit.dropped line, counted",
            "   ├─ disk full → audit.lost once writable again",
            "   └─ meanwhile health DEGRADED, audit_failing 1",
            "5. GET /api/v1/audit?principal=&view=&decision=",
            "   └─ only for audit readers; the read is audited",
        ],
        "code_w": 0.6,
        "items": [
            ("Why allows too",
             "A log of refusals answers “who was stopped”, not “who read the payroll view” — the "
             "question that actually gets asked."),
            ("Never fails a query",
             "A sink that cannot write is counted and reported; a query it is auditing is never failed."),
            ("Sign-ins too",
             "Every sign-in, refusal, lockout and key change is an audit event."),
        ],
        "size": 14,
        "takeaway": "A trail with a gap nothing records is a trail that lies — so every gap is counted.",
        "source": "Source: docs/operations/SECURITY.md 'Audit' (allows as well as denials; one sink, both "
        "transports, CFG-5; audit: file, CFG-23; AUDITROTATE-1: audit.lost, DEGRADED, pravaha_audit_failing; "
        "audit.dropped) and 'Reading the audit trail: GET /api/v1/audit'; 'Users, passwords, API keys and "
        "sessions' (sign-ins are audit events).",
        "talk": "The trail records what was allowed as well as what was refused, because the question an "
        "auditor asks is who read something. And when the trail cannot be written, the node says so loudly "
        "instead of carrying on with a gap.",
    },
    {
        "kind": "table",
        "kicker": "Identity · ADR-052",
        "title": "The engine keeps users, keys and sessions — and nothing reversible",
        "rows": [
            ["Credential", "Held as", "Rules"],
            ["Password", "Argon2id (PBKDF2-SHA512 where Argon2 is unavailable)",
             "12+ characters from 3 of 4 kinds; none of the last 5; 90-day maximum age"],
            ["Session prv_s_…", "SHA-256", "30 minutes idle, 12 hours in all, at most 3 per person; ended by "
             "sign-out, a password change or disabling the user"],
            ["API key", "The password KDF; shown once",
             "Roles a subset of the holder's; 90 days by default, 365 at most; 7-day rotation overlap"],
            ["Reset token", "SHA-256", "Single use, 60 minutes, issued by an administrator"],
            ["Sign-in failures", "Recorded before the refusal is answered",
             "Every refusal reads alike: 5 per address bar it, 50 from anywhere lock the account"],
        ],
        "col_w": [1.3, 2.2, 3.0],
        "size": 14,
        "note": "Held in an append-only journal, with no database to run; administered from the console, "
        "REST under /api/v1/users, /keys, /sessions, or pravaha user, key, session and password. "
        "A node outside dev refuses to start while admin keeps its published password (PRV-7019).",
        "source": "Source: docs/operations/SECURITY.md 'Users, passwords, API keys and sessions (ADR-052)' "
        "(the credentials table, lockout, PRV-7019, the CLI commands); docs/design/adr/052-the-engine-is-"
        "the-identity-authority.md (journal store).",
        "talk": "Pravaha is its own identity authority. Nothing is stored that could be turned back into a "
        "secret. And the default admin password cannot reach production: the node refuses to start with it.",
    },
    {
        "kind": "code",
        "kicker": "Governance · ADR-059",
        "title": "Row filters and masks are policies, applied where rows leave",
        "code": [
            "CREATE ROW FILTER sales.region_scope",
            "  AS region = session_attribute('region')",
            "  EXCEPT ROLE finance_admin;",
            "",
            "CREATE MASK sales.card_last4 ON COLUMN card",
            "  AS 'XXXX-' || RIGHT(card, 4)",
            "  EXCEPT ROLE payments_ops;",
            "",
            "ALTER STREAM orders",
            "  SET POLICY sales.region_scope;",
            "ALTER TAG 'pii'",
            "  SET POLICY sales.card_last4;",
        ],
        "code_w": 0.5,
        "items": [
            ("Grants live in the engine",
             "tenant.namespace.object names with owners and tags; nine privileges, allow-only, inherited "
             "down namespaces; SHOW EFFECTIVE ACCESS explains them."),
            ("A masked column is never an operand",
             "As a filter, group, join or sort key it would leak the true value: PRV-7006."),
            ("A filter that restricts nothing is refused",
             "region = region, or a < 5 OR a > 2, is refused rather than enforced."),
        ],
        "size": 14,
        "source": "Source: docs/operations/SECURITY.md 'Row filters and masks as catalogue objects (ADR-059 "
        "§4)' (the statements, verbatim, reflowed); README.md 'Governed catalogue' (nine privileges, "
        "inherited, SHOW EFFECTIVE ACCESS); docs/project/RELEASE_NOTES.md '1.0.0' (PRV-7006; TAUTOFILTER-1) "
        "and commit af5ee635 (VACUITYGAP-1).",
        "talk": "Policies are objects in the catalogue: bind a row filter to a stream, a mask to a tag, and "
        "every path a row can leave by applies them. A filter that would let everything through is refused, "
        "because it would only look like security.",
    },
    {
        "kind": "bullets",
        "kicker": "Transport security",
        "title": "TLS by default, plaintext refused, certificates checked at start",
        "items": [
            ("TLS is the client default",
             "grpc:// plaintext has to be spelled out; both SDKs refuse to send a token over it, and the "
             "CLI needs --insecure-token."),
            ("A TLS-configured PostgreSQL gateway refuses plaintext",
             "FATAL 28000, PRV-6221, before the token is asked for — unless allow-plaintext is set for a "
             "migration window."),
            ("An expired certificate stops the node at start",
             "PRV-6104 for Flight, PRV-6206 for the gateway, naming the date; within 30 days a WARN, and "
             "pravaha doctor turns YELLOW."),
            ("Connections to the stores can be encrypted",
             "JDBC, PostgreSQL CDC, Aerospike, Cassandra and Kafka."),
            ("Stated plainly",
             "HTTPS on the REST API is Spring Boot's server.ssl.*; the node does not request client "
             "certificates (terminate mTLS in front); the console listens on every interface, so put a "
             "TLS terminator before it beyond a trusted network."),
        ],
        "size": 15,
        "takeaway": "Plaintext is a decision someone has to type, and an expired certificate never starts.",
        "source": "Source: docs/operations/SECURITY.md 'Transport' (P-3 --insecure-token; PGTLSONLY-1, PRV-6221; "
        "CERTEXP-1, PRV-6104, PRV-6206, 30-day WARN; MTLSDOC-1; server.ssl.*); "
        "pravaha-console/content/topics/cli-reference.md 'Doctor' (tls checks); README.md 'Sources' "
        "(CONNECTOR_TLS.md); docs/project/RELEASE_NOTES.md 'Unreleased' (console on 0.0.0.0, TLS terminator).",
        "talk": "Encryption is the default and plaintext is a decision someone has to type. The last bullet "
        "is deliberately candid: where TLS is somebody else's job, the documentation says whose.",
    },
]
