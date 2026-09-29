---
title: Frequently asked questions
slug: faq
category: about
order: 10
icon: question-circle
summary: "The questions a QA team or an integrator asks in the first week — two ports, refused GROUP BYs, windows that publish late, tokens over plaintext, what a policy grants, where files live — each answered briefly, with the page that goes deeper."
badge: FAQ
audience: Everyone
keywords: [faq, questions, two ports, 19090, 18080, 17070, token, plaintext, policy, authenticated, opt pravaha, replace, why refused, late window, qa host]
guide: troubleshooting
related: [getting-started, sql-refusals, event-time-watermarks, authentication, authorization, backfill-cutover]
---

<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential. -->

Short answers, each drawn from the documentation and each ending at the page that says the rest.
If a question is about a `PRV` code, the [code browser](/help/codes) has every one.

## Connecting

### Why does the engine have two ports, and which one do I use?

Because it speaks two protocols. **19090** is Arrow Flight SQL (gRPC): registering, pausing,
resuming and dropping queries, **reading views** and **subscribing**. **18080** is HTTP and JSON: the
catalogue, validation and plans, descriptions, status, plugins, permissions, the audit trail and
metrics. The SDK needs the Flight address to connect and the HTTP address (`http_url`) for the calls
that live on HTTP. The console is a third process, on **17070**. Confusing the three is the commonest
way a first run fails. These are the defaults after 0.1.1; the 0.1.1 build used 9090, 8080 and 8090.
[The Python guide, §1 →](/help/python-api-guide#1-what-you-are-integrating-with)

### Is there an HTTP endpoint that registers a query or returns a view's rows?

No. Both need Flight. The HTTP surface describes, validates and explains; it does not register or
serve rows. [What REST cannot do →](/help/python-api-guide#20-what-rest-cannot-do)

### Why does the SDK refuse to send my token (PRV-1031)?

A token sent over plaintext can be read off the network, so the SDK will not send one over a
`grpc://` connection unless you pass `allow_insecure_token=True`. A connection string with no scheme
means TLS. The flag is for loopback, a local TLS sidecar or a QA host; anywhere else, serve TLS and
point the SDK at the engine's CA. [Connect →](/help/python-api-guide#3-connect) ·
[TLS →](/help/topics/tls)

### What does `policy: authenticated` grant?

Every verified caller may register queries, declare streams, and read and administer every view;
an anonymous caller is refused. Roles on a token are recorded in the audit trail, but the only thing
they gate in the two shipped policies is **reading the audit trail**, which needs a role in
`pravaha.security.audit-readers` (default `[admin]`). Neither shipped policy returns row filters or
tells one view from another — that takes a policy of your own.
[Authorization →](/help/topics/authorization)

### Where do the tokens come from?

Each is a key under `pravaha.security.tokens` in the engine's configuration file, with an `id` that
the audit trail and the registry journal record as the owner. On a QA host `install.sh` generated
them and printed them once. [Authentication →](/help/topics/authentication)

## A QA host

### Where do the files live on a QA host?

Everything is under `/opt/pravaha`. The engine's configuration is
`/opt/pravaha/conf/application.yaml`; the console's is `/opt/pravaha/console/conf/application.yaml`;
state — the registry journal, checkpoints, dead letters and spill — is in `/opt/pravaha/data`; the
engine's log and the audit trail are in `/opt/pravaha/logs`. Each file is edited in place and read on
restart. A 0.1.0 deployment that mounted `/var/lib/pravaha` or `/etc/pravaha` must move its mounts.
[One root →](/help/deploying#one-root-optpravaha)

### What survives a restart?

Checkpoints hold operator state, source offsets and the served view, cut at one point across every
input, so a restart resumes rather than replaying from scratch; the registry journal brings back
every registration and its sink. [Checkpoints and recovery →](/help/topics/checkpoints-recovery)

### What happens to a record that will not decode?

With `pravaha.dlq.directory` set, it goes to that query's dead-letter queue, where it can be listed,
read and replayed. Unset — the default — a decode failure stops that query's source.
[Dead letters →](/help/topics/dead-letters)

## Writing queries

### Why was my `GROUP BY` refused with PRV-2050?

A `GROUP BY` over a stream with no window has no bound on its key space: its state grows with every
distinct key and never shrinks, so the node would fail months later when the heap fills. It is
refused when you register it instead. Put a window in the `GROUP BY` —
`TUMBLE(event_time, INTERVAL '1' MINUTE)` — or group a view, whose scan ends.
[Refusals →](/help/topics/sql-refusals) ·
[Why it is refused →](/help/continuous-queries#why-an-unwindowed-group-by-is-refused)

### Why do a window's rows appear late, or not at all?

A window publishes when the **watermark** passes its end, and the watermark is the newest event time
seen minus the stream's out-of-orderness. So a one-minute window with ten seconds of out-of-orderness
publishes only once a row stamped ten seconds past its end has arrived. No newer rows means no
publication; a quiet partition is set aside only after its idle timeout; and in a join the slowest
input holds the rest back. A bounded file read fires every open window when it ends.
[Event time and watermarks →](/help/topics/event-time-watermarks) ·
[When a window emits →](/help/topics/windows)

### A late row was dropped. Why?

A stream's allowed lateness is zero unless it declares one, so a row for a window that has already
published is dropped and the published answer stands. With allowed lateness set, the same row
reopens the window as a retraction of the old answer and the corrected one.
[Late data →](/help/topics/event-time-watermarks#late-data)

### Why did a count go down?

Because the answer was corrected. Every change carries a weight — `+1` for a row appearing, `-1` for
one withdrawn — and an update or a correction is a `-1` of the old row and a `+1` of the new, in one
commit. A consumer applies both. [Weights and retractions →](/help/topics/zset-weights)

### Which SQL runs, and which is refused?

The support matrix lists every construct that runs and every one refused, with the code and the
reason, and a test holds it to the planner. [SQL reference →](/help/topics/sql-reference) ·
[The matrix →](/help/continuous-queries)

## Running queries

### How do I replace a running query without losing its answer?

`CREATE OR REPLACE CONTINUOUS QUERY`, `pravaha replace`, either SDK, or the console's replacement
screen. The new version backfills beside the old one, meets it at an exact position in the input and
takes the name — no gap and nothing counted twice. The replaced version is kept for an hour, so a
rollback is one step. [Backfill and cutover →](/help/topics/backfill-cutover)

### Two people registered the same query. Is it computed twice?

No, within one tenant: identical questions share one computation, matched on the normalised plan and
the security predicates rather than the SQL text. Two tenants' identical SQL is two computations.
[Sharing →](/help/topics/sharing)

### What delivery guarantee does my sink give?

It is stated at registration: exactly once to a transactional sink (`jdbc-sink`, `kafka-sink`,
`delta-sink`), effectively once to an idempotent upsert (`aerospike-sink`), at least once to a plain
append (`filesystem`) — and never better than the source allows.
[Delivery guarantees →](/help/topics/delivery-guarantees)

### Can `psql`, DBeaver or Grafana read a view?

Yes, over the PostgreSQL wire protocol, which is off by default: turn it on with
`pravaha.pgwire.enabled`. [The PostgreSQL gateway →](/help/topics/pgwire)

## The product

### Can I run it inside my own application?

Yes. `PravahaEngine` runs the whole loop in process, with no Spring and no network, and the Spring
Boot starter makes it a bean. [Embedded engine →](/help/topics/embedded-engine) ·
[Spring Boot starter →](/help/topics/spring-boot-starter)

### Does it run on more than one node?

Not yet. Execution is single-node; a standby can take over a node whose claim goes stale, and a node
refuses `PARTITIONED` mode (PRV-9002) rather than pretend to own partitions. Multi-node is designed
and on hold. [Cluster mode →](/help/topics/standby#cluster-mode) · [Standby →](/help/topics/standby)

### How fast is it?

The requirement is about 1,000 rows per second. What was measured — on a development machine, not
reference hardware, each figure with its caveat — is on the About page, and the scaling gate is
recorded as not reached. [Measured, with the caveats →](/about#numbers)

### What changed in this build, and what is still to come?

[What's new →](/help/whats-new) · [Roadmap →](/help/roadmap) ·
[Competitive landscape →](/about/competitive)
