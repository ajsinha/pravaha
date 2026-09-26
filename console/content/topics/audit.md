---
title: The audit trail — who asked what, and what they were told
slug: audit
category: security
order: 40
icon: journal-check
summary: "Every authorization decision, allows as well as denials, recorded to a rotating JSON Lines file and readable over GET /api/v1/audit and the console's Admin · Audit screen by those allowed."
badge: SECURITY
audience: Operators
keywords: [audit, audit trail, pravaha.security.audit, audit-file, audit-rotate-bytes, audit-keep, audit-recent, audit-readers, jsonl, "/api/v1/audit", mayReadAudit, http.audit.read, PRV-1051]
guide: security#audit
related: [authorization, authentication, row-filters, metrics-alerts]
---

Every decision the policy makes is recorded — **allows as well as denials**. A log of refusals
answers "who was stopped"; the question that actually gets asked is "who read the payroll view last
Tuesday, and with what SQL", and only a record of allows answers it.

One audit sink serves every surface: Flight reads and registrations, HTTP calls, listings, and reads
of the audit trail itself are recorded into the same object.

## The settings

| Setting | Default | What it does |
|---|---|---|
| `pravaha.security.audit` | `none` | `none`: nothing is recorded and there is nothing to read. `memory`: recent decisions are kept in this process and readable over `GET /api/v1/audit` until it ends. `file`: the same, **and** every decision appended to a file — the durable record. Anything else is refused at startup with PRV-7004 |
| `pravaha.security.audit-file` | `pravaha-audit.jsonl` | Where `file` writes, as JSON Lines. Relative to the working directory: set an absolute path somewhere you keep records |
| `pravaha.security.audit-rotate-bytes` | `67108864` (64 MiB) | Rotate once the file passes this size |
| `pravaha.security.audit-keep` | `5` | Rotated generations kept; the oldest is deleted |
| `pravaha.security.audit-recent` | `10000` | How many recent decisions are kept readable in memory, beside the file. Below 1 is refused with PRV-7004 |
| `pravaha.security.audit-readers` | `[admin]` | Under `policy: authenticated`, the roles whose holders may read the trail over HTTP. Empty closes it to everybody |

```yaml
pravaha:
  security:
    authentication: token
    policy: authenticated
    audit: file
    audit-file: /opt/pravaha/logs/audit.jsonl
    audit-rotate-bytes: 134217728
    audit-keep: 10
    audit-recent: 20000
    audit-readers: [admin, security-officer]
```

!!! warning "`memory` is not a retained trail"
    It holds recent decisions in this process and nowhere else; they are gone when the process ends,
    and a node set to it says so at startup with a `WARN`. `file` is the setting that leaves a record.

## The file

One JSON object per line, appended on a background thread so auditing can never slow or fail the
query it audits. Each line carries the time, the principal's id, tenant and roles, the action, the
target, `ALLOW` or `DENY`, the reason, and — when there was one — the SQL or filter as `detail`:

```json
{"at":"2026-09-19T09:30:12.418Z","principal":"ann","tenant":"acme","roles":["analyst"],"action":"query","target":"hourly_spend","result":"ALLOW","reason":"allowed","detail":"SELECT user_id, spend FROM hourly_spend WHERE user_id = ?"}
{"at":"2026-09-19T09:30:15.002Z","principal":"ann","tenant":"acme","roles":["analyst"],"action":"register:source","target":"payroll","result":"DENY","reason":"payroll is readable by the hr role only","detail":"SELECT * FROM payroll"}
{"at":"2026-09-19T09:31:40.771Z","principal":"ops-console","tenant":"acme","roles":["operator","admin"],"action":"http.audit.read","target":"audit","result":"ALLOW","reason":"allowed","detail":"principal=ann&decision=deny"}
```

(Illustrative lines; the field set and order are the file's.) The principal's claims and credential
are **never** written — `AuditEvent` carries neither, and `Principal.toString()` does not print
claims; both are tested.

**Who may read it.** The file is created `rw-------`. The operating system decides who may read it;
Pravaha will not loosen the file's own permissions — set the directory's.

**Rotation.** Past `audit-rotate-bytes` the file is renamed to `audit.jsonl.1` (the newest
generation, as logrotate does), older generations shift up, and the oldest beyond `audit-keep` is
deleted. An audit trail that fills the disk would stop the node it audits.

**Back-pressure.** Writing happens behind a bounded queue. If the queue is ever full, decisions are
dropped rather than making the query wait, and the next line written is a marker with the count, so
the gap is recorded rather than silent:

```json
{"at":"2026-09-19T09:40:00.000Z","event":"audit.dropped","count":212}
```

**A path the node cannot write** is PRV-7004 at startup — not a discovery at the first decision
nobody sees.

## Actions you will see

| Action | Recorded for |
|---|---|
| `query`, `prepare`, `schema` | a Flight read, a prepared statement's binding, a schema lookup |
| `subscribe`, `subscribe.withdrawn` | opening a subscription, and one withdrawn |
| `register`, `register:source`, `register:sink` | a registration, the read check on each stream it names, and the write check on the sink it names. `replace:*` is the same three for a blue/green replacement |
| `drop`, `pause`, `resume` | lifecycle actions |
| `list` | every per-view decision a listing makes |
| `http.read`, `http.list`, `http.administer` | the same questions asked over HTTP |
| `http.permissions` | `GET /api/v1/me/permissions` |
| `http.audit.read` | every read of the audit trail, allowed or refused |

## Reading it: `GET /api/v1/audit`

```text
GET /api/v1/audit?since=&until=&principal=&view=&action=&decision=&limit=&cursor=
```

| Parameter | Meaning |
|---|---|
| `since`, `until` | ISO-8601 instants, e.g. `2026-09-19T08:00:00Z` — inclusive, exclusive |
| `principal` | the principal's id |
| `view` | the target, ignoring case |
| `action` | one of the actions above |
| `decision` | `allow` or `deny` |
| `limit` | page size, at most 500; 100 by default |
| `cursor` | the previous page's `nextCursor` |

Pages are **newest first** and paged by sequence number, not by offset, so decisions arriving
between two pages do not shift the second. A parameter the node cannot read is PRV-1051 naming it,
never a filter silently dropped — an audit search that ignored a malformed `since` would answer a
different question and look right.

```bash
curl -s -H "Authorization: Bearer $OPS_TOKEN" \
  "https://pravaha.internal:8080/api/v1/audit?principal=ann&decision=deny&limit=2"
```

```json
{
  "recording": true,
  "sink": "file",
  "capacity": 20000,
  "retained": 1834,
  "evicted": 0,
  "oldestRetained": "2026-09-19T06:02:11.051Z",
  "actions": ["http.audit.read", "list", "query", "register", "register:source", "subscribe"],
  "events": [
    {"sequence": 1821, "at": "2026-09-19T09:30:15.002Z", "principal": "ann", "tenant": "acme",
     "roles": ["analyst"], "action": "register:source", "target": "payroll", "decision": "DENY",
     "reason": "payroll is readable by the hr role only", "detail": "SELECT * FROM payroll"}
  ],
  "nextCursor": null,
  "note": "The most recent 20000 decisions on this node are readable here; none has been evicted since it started."
}
```

(Illustrative values.) The endpoint reads a bounded **ring** of recent decisions, recorded on the same
call as the durable sink — not the file read back, which is written asynchronously, rotates
underneath a reader and may not be a file at all for a custom sink. What that gives up is history
across a restart, and the response says so: `capacity`, `retained`, `evicted`, `oldestRetained` and
the `note`. Decisions evicted from the ring are still in the file when `audit: file`. With
`audit: none` the response is `recording: false` and an empty page, with a note saying nothing was
recorded — never an empty trail that reads as "nobody asked for anything".

From Python:

```python
page = client.audit(principal="ann", decision="deny", limit=50)
for event in page["events"]:
    print(event["at"], event["action"], event["target"], event["reason"])
older = client.audit(principal="ann", decision="deny", limit=50, cursor=page["nextCursor"])
```

In the console: **Admin → Audit trail** (`/admin/audit`) is built on this endpoint — filter by
principal, view, action, decision and a UTC time window, page back by the cursor, click a principal
to filter by it. Every filter is in the URL.

## Who may read the trail

Reading the trail is a permission of its own, `mayReadAudit` — never derived from being allowed to
read views. The trail names every principal that read anything and the SQL they read it with, so it
discloses more than any one view.

| Policy | Who may read it |
|---|---|
| `permissive` | every caller — who is already entitled to everything the trail describes |
| `authenticated` | a verified principal with a role in `pravaha.security.audit-readers` |
| a policy of your own | nobody, unless it overrides `mayReadAudit` |

No credential is `401` PRV-7001 before the policy is asked; a refusal is `403` PRV-7002; **both are
recorded** as `http.audit.read`, with the filter as the detail — so "who looked at who read
payroll" has an answer too.

## Pitfalls

!!! warning "Pitfall: the console reads as one identity"
    The console reaches the engine with one token (`engine.token`), so the Admin · Audit screen shows
    the trail exactly when that identity may read it, and every action a console user takes is
    recorded under that identity. Attribute console actions to people from the console's own log.

!!! warning "Pitfall: a relative audit-file"
    The default `pravaha-audit.jsonl` lands in whatever directory the server was started from, which
    changes with how it was started. Set an absolute path.

!!! note "Auditing never fails a query"
    The ring cannot throw, and a durable sink that throws is counted and swallowed: an audit outage
    must not become a query outage, and the decision stays readable in the ring meanwhile.

## Where next

- [Authorization](/help/topics/authorization) — the decisions being recorded
- [Authentication](/help/topics/authentication) — where the principal comes from
