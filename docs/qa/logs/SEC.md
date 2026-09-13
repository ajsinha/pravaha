# SEC — execution log

Executed 2026-09-12 against `pravaha-server-0.1.0-SNAPSHOT-app.jar` and
`pravaha-cli-0.1.0-SNAPSHOT-cli.jar` as built, on branch `develop`, Java 21.
Ports: HTTP 18200–18206, Flight 19200–19207. Every server started here was stopped by PID.

Where a case needed a policy with real rules, it was run against a QA-only harness in
`/tmp/.../qa-sec/harness` built on the released jars (`QaPolicy`, `SecChecks`, `SecFlightMain`,
`SecListProbe`, `SecTlsProbe`). **No production code was modified.** The harness policy is:

| principal | rule |
|---|---|
| `ann` | reads everything, no filter |
| `bob` | reads everything **with row filter `region = 'EU'`** |
| `carol` | **denied** any view whose name contains `payroll`; allowed otherwise |
| anonymous | denied read and denied registration |

---

## Group A — the startup refusal

### SEC-001 — PASS
```
$ bin/pravaha-server --server.port=18200 --pravaha.flight.port=19200
...
Caused by: com.ash.messaging.pravaha.api.PravahaException: PRV-7002  this node is configured to
accept unauthenticated callers and serve them every view (pravaha.security.authentication=none,
policy=permissive). ... Set pravaha.security.authentication=token with pravaha.security.tokens.*,
or set pravaha.security.policy=authenticated, or -- if open really is what you want -- set
pravaha.security.allow-anonymous=true to say so on purpose.
	at com.ash.messaging.pravaha.server.PravahaNode.refuseAccidentalOpenServer(PravahaNode.java:160)

$ ss -ltnp | grep -E '18200|19200'
nothing bound on 18200/19200
```
**Verdict:** the shipped defaults refuse to start, the message names all three escapes, and nothing
is left listening. This is the control working exactly as designed.

### SEC-002 — PASS
```
$ bin/pravaha-server --server.port=18201 --pravaha.flight.port=19201 --spring.profiles.active=dev
security: authentication=none, policy=permissive, audit=none, flight transport=PLAINTEXT
Flight SQL listening on 0.0.0.0:19201

$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18201/api/v1/status
200
```
**Verdict:** the documented first-run path works and is open on purpose.

### SEC-003 — PASS
```
$ bin/pravaha-server ... --pravaha.security.allow-anonymous=true
== STARTED
$ curl ... /api/v1/streams  -> 200
```

### SEC-004 — PASS
```
$ PRAVAHA_SECURITY_ALLOWANONYMOUS=true bin/pravaha-server --server.port=18202 --pravaha.flight.port=19202
== STARTED
security: authentication=none, policy=permissive, ...
$ curl ... /api/v1/streams  -> 200
```
**Verdict:** Spring relaxed binding means the acknowledgement can be set from the environment with
no trace in any YAML file. Not a defect — but an operator auditing a deployment by reading
`application.yaml` will not see it, so it belongs in the runbook.

### SEC-005 — PASS
```
$ ... --pravaha.security.allow-anonymous=yes        -> STARTED, open
$ ... --pravaha.security.allow-anonymous=enabled    -> REFUSED
Failed to bind properties under 'pravaha.security.allow-anonymous' to boolean:
    Property: pravaha.security.allow-anonymous
    Value: "enabled"
    Reason: failed to convert java.lang.String to boolean (Invalid boolean value 'enabled')
```
**Verdict:** `yes` binds to `true`, which is what someone writing `yes` meant — an affirmative, not
a silent default. The dangerous case (a value that looks affirmative and binds to `false`) does not
exist: an unrecognised value fails the startup loudly and names the property. Not vacuous: the
second command proves the binding is strict.

### SEC-006 — PASS
```
$ ... --pravaha.security.policy=authentcated --pravaha.security.allow-anonymous=true
Caused by: PravahaException: PRV-7002  pravaha.security.policy is 'authentcated', which is not a
policy this node knows. Use 'permissive' or 'authenticated', or implement SecurityPolicy for rules
of your own.
```
**Verdict:** fails closed on a typo, and `allow-anonymous=true` was deliberately set so the *only*
thing that could refuse the start was the bad policy name. Not vacuous.

### SEC-007 — **FAIL (CRITICAL)**
```
$ bin/pravaha-server --server.port=18202 --pravaha.flight.port=19202 --pravaha.security.policy=AUTHENTICATED
security: authentication=none, policy=AUTHENTICATED, audit=none, flight transport=PLAINTEXT
Caused by: com.ash.messaging.pravaha.api.PravahaException: PRV-7002  this server and the registry it
hosts authorize against different SecurityPolicy instances. Registering would be judged by one and
reading by the other ...
	at com.ash.messaging.pravaha.flight.PravahaFlightServer.requireOnePolicy(PravahaFlightServer.java:201)
	at com.ash.messaging.pravaha.flight.PravahaFlightServer.hosting(PravahaFlightServer.java:180)
```
**Verdict:** the case-insensitive match is fine; the node dies for a different reason, and it is a
severe one. See SEC-008 — the same failure occurs for the lower-case spelling.

### SEC-008 — **FAIL (CRITICAL, and a second HIGH)**

**Part 1: `policy: authenticated` can never start a node with Flight enabled.**
```
$ bin/pravaha-server --server.port=18202 --pravaha.flight.port=19202 --pravaha.security.policy=authenticated
security: authentication=none, policy=authenticated, audit=none, flight transport=PLAINTEXT
Caused by: PravahaException: PRV-7002  this server and the registry it hosts authorize against
different SecurityPolicy instances. ...
	at com.ash.messaging.pravaha.flight.PravahaFlightServer.requireOnePolicy(PravahaFlightServer.java:201)
	at com.ash.messaging.pravaha.flight.PravahaFlightServer.hosting(PravahaFlightServer.java:180)
	at com.ash.messaging.pravaha.server.PravahaNode.start(PravahaNode.java:...)
```
Cause, from `PravahaNode.start`:
```java
PravahaFlightServer server = new PravahaFlightServer(views)
        .hosting(registry)                                   // <-- checks first
        .authorizedBy(securityPolicyOf(registry), auditSink());  // <-- sets second
```
`hosting()` calls `requireOnePolicy()` while the server's `policy` field is still its initialiser,
`SecurityPolicy.PERMISSIVE`. The check is by **identity**, so it passes only when the registry's
policy is also `PERMISSIVE` — i.e. only for `policy: permissive`. Any other policy is refused.
One of the three documented ways to close the server is therefore unusable, and the operator who
tries it gets an error message that describes a misconfiguration they did not make. The reachable
"fix" is `allow-anonymous=true`, which is the opposite of what they wanted.
*Workaround for a deployment: `pravaha.flight.enabled=false`, or `authentication=token` with
`policy=permissive`.*

**Part 2: with `policy: authenticated` forced to start (Flight disabled), the HTTP surface serves
anonymous callers everything.**
```
$ bin/pravaha-server --server.port=18202 --pravaha.security.policy=authenticated --pravaha.flight.enabled=false
== STARTED   security: authentication=none, policy=authenticated

$ curl -o /dev/null -w '%{http_code}' http://127.0.0.1:18202/api/v1/status      -> 200
$ curl -o /dev/null -w '%{http_code}' http://127.0.0.1:18202/api/v1/streams     -> 200
$ curl -X POST -d '{"name":"payroll","schema":"employee:STRING,salary:INT64"}' \
       http://127.0.0.1:18202/api/v1/streams                                    -> 201
{"name":"payroll","version":1,"fieldCount":2,"fields":[...]}
$ curl -X POST -d '{"sql":"SELECT employee, salary FROM payroll"}' \
       'http://127.0.0.1:18202/api/v1/queries/explain?level=physical'
{"level":"physical","plan":"Project[employee, salary]\n  Scan(payroll)\n","outputFields":[...]}
```
**Verdict:** `policy: authenticated` is documented as "only verified callers see anything". With it
set, an anonymous caller lists every stream with its full schema, **registers new streams**, and
plans queries. No HTTP controller consults the policy (see SEC-028), and the `BearerTokenFilter` is
registered only when `authentication=token`, so `policy` governs Flight alone. Severity HIGH for
part 2, CRITICAL for part 1.

### SEC-009 — PASS
```
$ ... --pravaha.security.authentication=token           (no tokens configured)
== STARTED
WARN  authentication is on and Flight is serving plaintext, so credentials travel in the clear; ...
security: authentication=token, policy=permissive, audit=none, flight transport=PLAINTEXT

$ curl http://127.0.0.1:18202/api/v1/streams
{"code":"PRV-7001","message":"this server requires a credential; ...","status":401}
$ curl -H 'Authorization: Bearer anything' http://127.0.0.1:18202/api/v1/streams
{"code":"PRV-7001","message":"PRV-7001  this server has no way to verify credentials, so it accepts
none. Configure a TokenVerifier, or run without authentication ...","status":401}
```
**Verdict:** an empty token table refuses everyone rather than accepting anyone. Not vacuous: the
second call presented a credential and was still refused, with a message that explains why.

### SEC-010 — PASS (with a note)
```
$ ... --pravaha.security.authentication=tokens  (note the typo)  --pravaha.security.policy=permissive
Caused by: PRV-7002  this node is configured to accept unauthenticated callers ...
(pravaha.security.authentication=none, policy=permissive)
```
**Verdict:** `authenticates()` is `"token".equalsIgnoreCase(...)`, so `tokens` silently means
"none" — but the startup guard then catches it and refuses. The node fails closed. **Note:** the
message reports `authentication=none` rather than "`tokens` is not an authentication mechanism this
node knows", so the operator is told about a value they did not write. The combination that would
be genuinely dangerous (typo + `policy=authenticated`) cannot start at all today because of SEC-008.

### SEC-011 — PASS
```
$ bin/pravaha-server --server.port=18200 ...          (defaults, i.e. the refusal)
$ python3 poll.py 18200 /api/v1/status 75             (raw sockets, no process spawn per try)
tries=2794414 hits=0
$ grep -n "Tomcat started on port\|Application run failed" logs/sec011b.log
22: Tomcat started on port 18200 (http) with context path '/'   20:24:28.488
30: Application run failed                                       20:24:29.032
```
**Verdict:** the log shows Tomcat's connector reaching "started" ~540 ms before the refusal — the
web server lifecycle has a lower phase than `pravahaNode`. 2.8 million connection attempts across
that window got **zero** responses, so nothing is actually served. Not vacuous: the same poller
returns data against a running node (SEC-002).

---

## Group B — authentication on the HTTP surface

Baseline: `authentication=token`, `policy=permissive`, `audit=memory`, token
`sek-ret-token-aaaaaaaaaaaaaaaaaaaa` → `ann`, on HTTP 18201 / Flight 19201.

### SEC-012 — PASS
```
$ curl -i http://127.0.0.1:18201/api/v1/streams
HTTP/1.1 401
Content-Type: application/json;charset=ISO-8859-1
{"code":"PRV-7001","message":"this server requires a credential; send it as 'Authorization: Bearer <token>'","status":401}
```

### SEC-013 — PASS
```
$ curl -H 'Authorization;' .../api/v1/streams
{"code":"PRV-7001","message":"this server requires a credential; ...","status":401}  [401]
```

### SEC-014 — PASS
```
$ curl -H 'Authorization: Bearer'  .../api/v1/streams   -> 401 "the credential presented was not accepted"
$ curl -H 'Authorization: Bearer ' .../api/v1/streams   -> 401 "the credential presented was not accepted"
```
**Verdict:** neither the bare scheme nor the empty token authenticates. `StaticTokenVerifier.and`
also refuses to register a blank token, so there is no table entry an empty header could match.

### SEC-015 — PASS
```
$ curl -H 'Authorization: Bearer not-the-token' ...  -> 401 "PRV-7001  the credential presented was not accepted"
```
**Verdict:** identical message to SEC-014 and to every other rejection below — no oracle
distinguishing unknown from malformed.

### SEC-016 — PASS
```
$ curl -H "Authorization: Bearer sek-ret-token-aaaaaaaaaaaaaaaaaaaa" .../api/v1/streams
[{"name":"payroll","version":1,"fieldCount":3,"fields":[...]}]  [200]
```
**Verdict:** this is the control that makes every 401 in this group non-vacuous.

### SEC-017 — PASS
```
$ curl -H "Authorization: bearer <valid>"  -> 200
$ curl -H "Authorization: BeArEr <valid>"  -> 200
```

### SEC-018 — PASS (with a note)
```
$ curl -H "Authorization: Bearer    <valid>   "                     -> 200
$ curl -H "Authorization: Bearer sek-ret-token aaaaaaaaaaaaaaaaaaaa" -> 401
$ curl -H "Authorization: <valid>"   (no scheme at all)              -> 200
```
**Verdict:** padding is stripped, an interior space is not tolerated. **Note:** the HTTP filter
falls back to treating the *whole* header as the token when the Bearer prefix is absent, while
`PrincipalMiddleware` on Flight **requires** the scheme. The two transports disagree about what a
credential looks like. Not exploitable (the token still has to be valid), but it is the kind of
divergence `BearerTokenFilter`'s own javadoc says it exists to prevent.

### SEC-019 — PASS
```
$ curl -H "Authorization: Bearer $(python3 -c "print('A'*65536)")" ...  -> 400  time=0.008s
$ curl -H "Authorization: Bearer $(python3 -c "print('A'*4000)")"  ...  -> 401
```
**Verdict:** the container caps the header at 8 KiB and answers 400 in 8 ms; a 4000-character token
reaches the verifier and is refused normally. Never 200, no stack trace, no hang.

### SEC-020 — PASS
```
  token=' OR '1'='1                 -> {"code":"PRV-7001","message":"PRV-7001  the credential presented was not accepted","status":401}
  token="; DROP TABLE x; --         -> same
  token=${jndi:ldap://127.0.0.1/a}  -> same
  token=%00admin                    -> same
  token=a"b                         -> same
  token=../../etc/passwd            -> same
  token=<script>alert(1)</script>   -> same
  token=tok<newline>en              -> 400 (Tomcat rejects the header)
```
**Verdict:** every rejection returns the same constant message. The token is **never echoed**, so
the hand-built JSON in `BearerTokenFilter.refuse` cannot be broken by a quote or a newline in the
credential — the only interpolated value is a server-authored reason.

### SEC-021 — **FAIL (MEDIUM)**
```
401 from the filter:
{"code":"PRV-7001","message":"this server requires a credential; ...","status":401}

400 from the exception handler:
{"code":"PRV-0400","message":"level must be 'logical' or 'physical', got 'nonsense'","helpUrl":"",
 "timestamp":"2026-09-13T00:28:42.251626779Z","path":"/api/v1/queries/explain"}

404 from the exception handler:
{"code":"PRV-2003","message":"PRV-2003  no stream named 'nosuch'. Registered: [payroll]",
 "helpUrl":"https://docs.pravaha.io/errors/PRV-2003","timestamp":"...","path":"/api/v1/streams/nosuch"}
```
**Verdict:** the 401 is **not** an `ApiError`. It is missing `helpUrl`, `timestamp` and `path`, and
carries an extra `status` field nothing else has. `ApiDtos.ApiError`, `ApiExceptionHandler`,
`application.yaml` (`problemdetails: enabled: false`) and `BearerTokenFilter`'s own comment each
state that every non-2xx response is one shape. This is the one that is not. A client with a
strict error deserialiser breaks on exactly the response it will see most often from a
misconfigured deployment. Cause: `refuse()` writes the JSON by string concatenation instead of
serialising an `ApiError`. Also minor: the 401 is served as `application/json;charset=ISO-8859-1`
while the rest of the API is UTF-8.

### SEC-022 — PASS
```
  ?token=<valid>                        -> 401
  Cookie: token=<valid>                 -> 401
  X-Api-Key: <valid>                    -> 401
  Proxy-Authorization: Bearer <valid>   -> 401
  X-Forwarded-Authorization: Bearer ... -> 401
```
**Verdict:** exactly one place a credential is read from. Not vacuous — the same token in
`Authorization` returns 200 (SEC-016).

---

## Group C — which HTTP paths answer without a credential

### SEC-023 — PASS
```
/                                  401
/status                            401
/api/v1/status                     401
/api/v1/streams                    401
/api/v1/streams/payroll            401
/error                             401
/actuator                          401
/actuator/health                   200
/actuator/health/liveness          200
/actuator/health/readiness         200
/actuator/info                     200
/actuator/metrics                  401
/actuator/prometheus               401
/api/v1/openapi.json               200
/api/docs                          302
/swagger-ui/index.html             404
POST /api/v1/streams               401
POST /api/v1/queries/validate      401
POST /api/v1/queries/explain       401
```
**Verdict:** exactly the documented set is open. `/actuator/metrics` and `/actuator/prometheus` are
*exposed* by `management.endpoints.web.exposure.include` and are correctly **not** in
`OPEN_PREFIXES`, so they need a credential — the trap was avoided. **Note:** `/api/docs` 302s to
`/api/swagger-ui/index.html`, which is not covered by the `/swagger-ui` prefix and therefore 401s;
the docs UI is unreachable when authentication is on. Harmless for security (it fails closed), but
`OPEN_PREFIXES` lists a path springdoc does not serve.

### SEC-024 — PASS
```
/actuator/env                              unauth=401 authed=404
/actuator/env/pravaha.security.tokens      unauth=401 authed=404
/actuator/configprops                      unauth=401 authed=404
/actuator/beans                            unauth=401 authed=404
/actuator/heapdump                         unauth=401 authed=404
/actuator/threaddump                       unauth=401 authed=404
/actuator/loggers                          unauth=401 authed=404
/actuator/mappings                         unauth=401 authed=404
/actuator/shutdown                         unauth=401 authed=404
/actuator/caches /actuator/conditions /actuator/scheduledtasks /actuator/httpexchanges  same
```
**Verdict:** none of them are exposed, authenticated or not. This matters more than it looks:
`pravaha.security.tokens` is a `Map` whose **keys are the tokens themselves**, and Spring's
actuator sanitiser masks values, not keys — so an exposed `/actuator/env` or `/actuator/configprops`
would print every valid credential in plain text. The current `exposure.include` list is the only
thing preventing that. Recommend it be treated as a security control and commented as one.

### SEC-025 — PASS
```
$ curl http://127.0.0.1:18201/actuator/health          (no credential)
{"status":"UP","groups":["liveness","readiness"]}
$ curl -H 'Authorization: Bearer <valid>' .../actuator/health
{"status":"UP","groups":["liveness","readiness"]}
$ curl .../actuator/info
{}
```
**Verdict:** `show-details: when-authorized` with no Spring Security resolves to "nobody" — no
component names, no disk paths, no versions, authenticated or not. `/actuator/info` is empty.

### SEC-026 — PASS
```
/actuator/health/../env                          404
/actuator/health/..;/env                         404
/actuator/health%2f..%2fenv                      400  (Tomcat)
/api/docs/../v1/streams                          404
/api/v1/openapi.json/../../v1/streams            404
/swagger-ui/../api/v1/streams                    404
//api/v1/streams                                 401
/api/v1/streams;x=/actuator/health               401
/actuator/healthz                                404
/actuator/health-check                           404
/api/v1/openapi.jsonx                            404
/actuator/info/../../api/v1/streams              404
/./api/v1/streams                                401
/api/v1/openapi.json;/../api/v1/streams          404
/actuator/healthXYZ                              404
```
Proof that the `..` forms are not merely blocked by the endpoint being absent — repeated against a
node started with `--management.endpoints.web.exposure.include=health,info,metrics,env,configprops,beans`:
```
/actuator/env                    -> 401   (direct, correctly filtered)
/actuator/health/../env          -> 404   (not 200: no bypass)
/actuator/info/../configprops    -> 404   {"path":"/actuator/info/../configprops"}
/api/docs/../../actuator/beans   -> 404   {"path":"/api/docs/../../actuator/beans"}
```
**Verdict:** no bypass. `shouldNotFilter` does match `/actuator/health/../env` (the `startsWith`
check sees the raw URI), but Spring never routes a path containing `..` — the error body echoes the
un-normalised path, confirming it was not collapsed. So the prefix check is **fragile by
construction and safe by accident**: it depends on the container never normalising after the filter
runs. A `Set` of exact paths plus an explicit `/actuator/health/**` rule would not depend on that.

### SEC-027 — PASS
```
$ curl http://127.0.0.1:18201/api/v1/openapi.json    -> 200, 4229 bytes  (no credential)
paths: ['/api/v1/queries/explain','/api/v1/queries/validate','/api/v1/status','/api/v1/streams',
        '/api/v1/streams/{name}','/status']
servers: [{'url': 'http://127.0.0.1:18201'}]
grep counts: sek-ret=0  bob-token=0  payroll=0  pravaha.security=0  /home/ashutosh=0  tokens=0
```
**Verdict:** the document describes the API's shape and nothing about this deployment — no stream
names, no credentials, no filesystem paths. The only deployment fact is the server URL the client
already used to fetch it.

### SEC-028 — **FAIL (HIGH)**
```
$ grep -rn "SecurityPolicy\|principalOf\|PRINCIPAL_ATTRIBUTE\|Principal" \
      pravaha-server/src/main/java/com/ash/messaging/pravaha/server/api/
(count: 0 of 4 controller files)
```
**Verdict:** `QueryController`, `StreamController`, `StatusController` and `ApiExceptionHandler`
contain zero references to the principal or the policy. `BearerTokenFilter` parks a `Principal` on
the request as `pravaha.principal` and nothing ever reads it. Consequences:
* `pravaha.security.policy` has no effect whatsoever on HTTP (demonstrated in SEC-008 part 2).
* On HTTP, authorization is binary — a valid token is full access, including `POST /api/v1/streams`.
* A deployment with a real `SecurityPolicy` (per-tenant, row filters) has it enforced on Flight and
  ignored on HTTP. The two surfaces of one node enforce different rules.
Requirement 2 ("only authenticated users may access data") holds on HTTP only when
`authentication=token`; requirement 3 ("only the data they are authorized for") does not hold on
HTTP at all.

---

## Group D — Flight authentication

### SEC-029 — PASS
```
$ bin/pravaha queries --url grpc://127.0.0.1:19201
PRV-1041  PRV-7001  this server requires a credential: send it as the header 'authorization: Bearer <token>'
```

### SEC-030 — PASS
```
$ bin/pravaha queries --url grpc://127.0.0.1:19201 --token wrong-token
PRV-1041  PRV-7001  the credential presented was not accepted
```
**Verdict:** and the message is the same one a malformed or unknown credential gets — no oracle.

### SEC-031 — PASS
```
$ bin/pravaha queries --url grpc://127.0.0.1:19201 --token sek-ret-token-aaaaaaaaaaaaaaaaaaaa
no continuous queries are registered
```
**Verdict:** the control for SEC-029/030.

### SEC-032 — PASS
```
$ bin/pravaha register --name paystub --sql-file q1.sql --keys 0 --url ... --token <valid>
registered paystub  state=RUNNING  fingerprint=3d0031e7df37

then, with NO credential:
  queries                         -> PRV-7001 this server requires a credential ...
  register --name evil ...        -> PRV-7001 ...
  query --sql 'SELECT * FROM paystub' -> PRV-7001 ...
  drop --name paystub             -> PRV-7001 ...
  pause --name paystub            -> PRV-7001 ...
  resume --name paystub           -> PRV-7001 ...
  subscribe --view paystub        -> PRV-7001 ...
and afterwards, as ann:
  NAME     STATE    FINGERPRINT   ROWS IN
  paystub  RUNNING  3d0031e7df37  0        (still there, still RUNNING)
```
**Verdict:** when a verifier is configured, `PrincipalMiddleware` refuses every verb before the
producer runs, metadata calls included. The query survived all seven attempts. The CLI prints
"subscribed to ..." optimistically before the server answers; the refusal follows on the next line.

### SEC-033 — **FAIL (HIGH)**

Run against a harness configured exactly as `PravahaNode` would configure a node with
`policy=authenticated, authentication=none` (`AuthenticatedOnlyPolicy`, no `TokenVerifier`) —
the configuration SEC-008 shows cannot currently start, tested here on the same code path.
```
$ bin/pravaha queries --url grpc://127.0.0.1:19204            (NO credential)
NAME          STATE    FINGERPRINT   ROWS IN
sales_view    RUNNING  5e3551b27328  32
payroll_view  RUNNING  3d0031e7df37  1

$ bin/pravaha query --sql 'SELECT ... FROM sales_view' --url ...
PRV-7002  anonymous may not read 'sales_view': this server serves data only to authenticated callers ...
$ bin/pravaha query --sql 'SELECT ... FROM payroll_view' --url ...
PRV-7002  anonymous may not read 'payroll_view': ...
$ bin/pravaha register --name anon_q ... --url ...
PRV-7002  anonymous may not register a query: ...
$ bin/pravaha subscribe --view sales_view --url ...
PRV-7002  anonymous may not subscribe to 'sales_view': ...
```
Raw wire, to show what LIST actually returns (`SecListProbe`, no credential):
```
result 1: PRVH|...|sales_view|...|RUNNING|...|SELECT order_id, region, amount FROM sales|...|5e3551b27328|...|40
result 2: PRVH|...|payroll_view|...|RUNNING|...|SELECT employee, region, salary FROM payroll|...|3d0031e7df37|...|1
results=2
```
**Verdict:** read, register and subscribe are all correctly refused — and `pravaha.list` is not
checked at all. `PravahaFlightSqlProducer.doAction`'s LIST branch calls `required.names()` and
`required.require(name)` with no `policy.mayRead`. An unauthenticated caller receives every view
name, its state, its **full SQL text**, its fingerprint and its row count. `PrincipalMiddleware`'s
own javadoc says "the set of view names is a map of what this deployment does … worth a refusal";
this path does not refuse it. Severity HIGH on its own; see SEC-043 for why it is worse.

### SEC-034 — **FAIL (CRITICAL)**
```
(same anonymous client, same node, queries registered by an authorized principal)
$ bin/pravaha pause  --name sales_view   --url grpc://127.0.0.1:19204     -> "pauseped sales_view"
$ bin/pravaha queries --url ...                                           -> sales_view  PAUSED
$ bin/pravaha resume --name sales_view   --url ...                        -> "resumeped sales_view"
$ bin/pravaha drop   --name payroll_view --url ...                        -> "dropped payroll_view"
$ bin/pravaha drop   --name sales_view   --url ...                        -> "dropped sales_view"
$ bin/pravaha queries --url ...                                           -> no continuous queries are registered
```
And the authenticated form of the same gap (`carol`, who the policy explicitly denies on payroll):
```
$ bin/pravaha drop  --name payroll_view --url grpc://127.0.0.1:19203 --token carol-token-cccc
dropped payroll_view
$ bin/pravaha pause --name hr_summary   --url ... --token carol-token-cccc
pauseped hr_summary
$ bin/pravaha queries --url ... --token ann-token-aaaa
NAME        STATE    FINGERPRINT   ROWS IN
sales_view  RUNNING  5e3551b27328  334
hr_summary  PAUSED   57ed47d2dabf  2
```
**Verdict:** `DROP`, `PAUSE` and `RESUME` in `PravahaFlightSqlProducer.doAction` perform **no
authorization of any kind**. `REGISTER` calls `mayRegisterQuery`; the three verbs that destroy or
suspend another principal's standing computation call nothing. On a node whose configuration says
"only verified callers see anything", a caller with no credential at all deleted every continuous
query on it. Destroying a continuous query destroys its accumulated state, so this is not a
restartable outage — it is data loss plus a full outage of the node's function. Requirement 2 fails
for writes even though it holds for reads. Severity CRITICAL.
*(Cosmetic, same evidence: the CLI prints "pauseped"/"resumeped".)*

---

## Group E — authorization, row filters, entitlements

### SEC-035 — PASS
```
carol SELECT employee, region, salary FROM payroll_view
  -> REFUSED: PRV-7002  carol may not read 'payroll_view': carol is not entitled to payroll
ann   SELECT employee, region, salary FROM payroll_view
  -> [[e1, EU, 10]]
```
**Verdict:** not vacuous — `ann` gets the row from the same view in the same process.

### SEC-036 — PASS
```
registry.register("anon_q", ..., Principal.ANONYMOUS)
  -> refused: PRV-7002  anonymous may not register a query: anonymous callers may not register
```

### SEC-037 — PASS
```
carol registers "harmless" = SELECT employee, salary FROM payroll
  -> PRV-7002  carol may not register 'harmless' because it reads 'payroll', which they may not
     read: carol is not entitled to payroll. A registration is a standing read of everything the
     query names, so it is refused here rather than at the first row.
carol registers "payroll_alias" = SELECT employee AS e, salary AS s FROM payroll AS anything
  -> PRV-7002  ... because it reads 'payroll' ...
ann registers the same SQL
  -> ann allowed: payroll_ann
```
**Verdict:** the documented hole is closed, including the aliased form the brief calls out
(`FROM payroll AS anything`) — `sourceStreams` walks the physical plan's `ScanOperator`s, so the
alias is irrelevant. `ann`'s success proves the refusal is about the entitlement and not about the
SQL.

### SEC-038 — PASS
```
ann  SELECT order_id, region, amount FROM sales_view -> [[o1, EU, 100], [o2, US, 200], [o3, EU, 300]]
bob  SELECT order_id, region, amount FROM sales_view -> [[o1, EU, 100], [o3, EU, 300]]
```
**Verdict:** `allowWithRowFilter("region = 'EU'")` is ANDed into `bob`'s plan and the US row is
gone. Non-vacuous three ways: the view holds a US row, `ann` sees it, and `bob` sees both EU rows
(so this is filtering, not truncation).

### SEC-039 — PASS
```
ann SELECT order_id, amount FROM no_region   (view carries no `region` column)
  -> [[o1, 100], [o2, 200]]
bob SELECT order_id, amount FROM no_region
  -> REFUSED: PRV-7003  the row filter for no_region (region = 'EU') cannot be applied to it:
     PRV-2002  Column 'region' not found ... A filter naming a column this view does not carry
     cannot be enforced on it -- the column was aggregated away, so each row already mixes values
     this principal may and may not see. Register a view that applies the filter before aggregating.
```
**Verdict:** fails closed. This is the case where a silently-dropped filter would have been an
undetectable leak, and the engine refuses instead. `ann`'s success on the same view proves the
refusal is the filter and not the view.

### SEC-040 — PASS
```
$ bin/pravaha subscribe --view sales_view --limit 3 --url grpc://127.0.0.1:19203 --token bob-token-bbbb
subscribed to sales_view; changes print as they are committed. Ctrl-C to stop.
PRV-7002  bob may not subscribe to 'sales_view' because their access to it is conditional on the row
filter 'region = 'EU'', and a subscription cannot enforce a filter -- it delivers every change the
view commits. Read the view instead, where the predicate is applied to the plan, or have the policy
grant unconditional access to a view that already carries only the rows this principal may see.

$ bin/pravaha subscribe --view payroll_view --limit 1 --url ... --token carol-token-cccc
PRV-7002  carol may not subscribe to 'payroll_view': carol is not entitled to payroll
```
**Verdict:** **the past data leak is fixed**, by refusal rather than by filtering, which is the
right trade for a path that cannot express the predicate. `bob` received zero rows, not "only EU
rows" — nothing was delivered before the refusal. Non-vacuous: SEC-041 below.

### SEC-041 — PASS
```
$ bin/pravaha subscribe --view sales_view --limit 3 --url ... --token ann-token-aaaa
live-44  US  440
-- commit, 1 row
live-45  EU  450
-- commit, 1 row
live-46  US  460
-- commit, 1 row
```
**Verdict:** subscribe works, and the stream carries both regions — so SEC-040's refusal is a
security decision, not a broken subscription path.

### SEC-042 — PASS
```
ann  registers SELECT order_id, region, amount FROM sales  -> fingerprint 5e3551b27328
bob  registers the identical SQL                           -> fingerprint 9aa0124f26dd
bob2 registers the identical SQL again                     -> fingerprint 9aa0124f26dd
ann share == bob share ? false      (object identity)
bob share == bob2 share? true       (object identity)
ann's new registration == ann's earlier one? true
```
**Verdict:** two principals with different entitlements get two fingerprints and two executions, so
the restricted principal's data never sits in the unrestricted one's state. Not vacuous in either
direction: the same filters share one computation (`bob`/`bob2` are the same object), and identical
SQL from the same principal also shares — so the split is caused by the row filter and nothing else.

### SEC-043 — **FAIL (CRITICAL)**
```
$ bin/pravaha register --name secret_pay --url ... --token ann-token-aaaa \
    --sql-file <<< "SELECT employee, salary FROM payroll WHERE employee = 'ACC-0007-SECRET-CUSTOMER'"
registered secret_pay  state=RUNNING  fingerprint=0703e339a20b

$ SecListProbe 19203 carol-token-cccc          (carol is denied 'payroll' by the policy)
result 1: PRVH|...|sales_view|...|RUNNING|...|SELECT order_id, region, amount FROM sales|...
result 2: PRVH|...|payroll_view|...|RUNNING|...|SELECT employee, region, salary FROM payroll|...
result 3: PRVH|...|secret_pay|...|RUNNING|...|SELECT employee, salary FROM payroll WHERE employee = 'ACC-0007-SECRET-CUSTOMER'|...|0703e339a20b|...|0
results=3
```
**Verdict:** `carol` cannot read `payroll` — and receives, over LIST, the full SQL of a payroll
query including the literal `'ACC-0007-SECRET-CUSTOMER'`. `application.yaml` says of the journal:
"holds query text and bound parameter values (account numbers, customer ids), so permission it like
data". The LIST action hands that same query text to every caller with no `mayRead` check at all.
This is a confidentiality leak to an *authenticated but unauthorized* principal, and (SEC-033) to
an unauthenticated one. Severity CRITICAL.

### SEC-044 — PASS
```
(registered under a policy allowing ann on payroll; recovered under one that denies it)
recovered: [bob_sales]
refused:   [ann_payroll: PRV-7002  ann may not register 'ann_payroll' because it reads 'payroll',
            which they may not read: payroll access has been revoked for everyone. ...]
registry names after recovery: [bob_sales]
```
**Verdict:** a query whose owner has lost access does not come back, and the refusal names the
query so an operator can see which view is now missing. Not vacuous: `bob_sales`, whose entitlement
was untouched, recovered from the same journal in the same call.

### SEC-045 — PASS
```
(recover with a resolver that resolves nothing)
recovered: []
refused:   [ann_payroll: its owner 'ann' is not a principal this deployment knows, so there is
            nobody to authorize it as,
            bob_sales: its owner 'bob' is not ..., fresh_ann: its owner 'ann' is not ...]
```
**Verdict:** an unresolvable owner is refused rather than recovered as anonymous or as an
administrator, and one bad entry does not abort the replay.

### SEC-046 — PASS
```
recovered bob_sales fingerprint = 9aa0124f26dd
fresh ann  fingerprint          = 5e3551b27328
shared with ann? false
```
**Verdict:** the recovered registration re-enters through the same `register` path, so `bob`'s row
filter is re-derived from the *current* policy and lands in the fingerprint. The recovered query's
fingerprint matches `bob`'s from SEC-042 exactly and differs from `ann`'s — recovery does not
launder an entitlement by losing a filter.

### SEC-047 — **FAIL (CRITICAL)**
```
$ openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 2 \
      -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
$ bin/pravaha-server --server.port=18205 --pravaha.flight.port=19206 \
      --pravaha.flight.tls.certificate=.../cert.pem --pravaha.flight.tls.key=.../key.pem
== STARTED
security: authentication=token, policy=permissive, audit=memory, flight transport=TLS
Flight SQL listening on 0.0.0.0:19206

$ openssl s_client -connect 127.0.0.1:19206 -alpn h2
SSL handshake has read 0 bytes and written 1549 bytes
New, (NONE), Cipher is (NONE)

$ SecTlsProbe 19206 cert.pem <valid-token>          (grpc+tls, cert as the only trust anchor)
TLS call FAILED: FlightRuntimeException -- io exception Channel Pipeline:
  [ProtocolNegotiators$ClientTlsHandler#0, WriteBufferingAndExceptionHandler#0, ...]

server log:
io.netty.channel.ChannelPipelineException: io.netty.handler.ssl.SslHandler.handlerAdded() has thrown
an exception; removed.
	at io.grpc.netty.ProtocolNegotiators$ServerTlsHandler.handlerAdded(ProtocolNegotiators.java:445)
	at io.netty.handler.ssl.SslHandler.setOpensslEngineSocketFd(SslHandler.java:2312)
Caused by: java.lang.NoClassDefFoundError: io/netty/channel/unix/UnixChannel
Caused by: java.lang.ClassNotFoundException: io.netty.channel.unix.UnixChannel

$ ls BOOT-INF/lib | grep -c unix-common
0
```
**Verdict:** **TLS on the Flight transport does not work at all.** `netty-transport-native-unix-common`
is absent from the server's dependency set, so `SslHandler.setOpensslEngineSocketFd` (reached
because `netty-tcnative-boringssl-static` *is* present and selects the OpenSSL provider) fails on
every connection and the SSL handler is removed from the pipeline. The node starts, logs
`flight transport=TLS`, binds the port, and then serves **nobody** — every client, TLS or plaintext,
is disconnected. Discovered at the first client call, never at startup.

Why this is CRITICAL and not merely a broken feature: TLS is the only control that stops the bearer
token travelling in clear text (`PravahaNode` warns about exactly this, and `PravahaFlightServer`'s
javadoc calls plaintext "defensible on a loopback socket and nowhere else"). An operator who tries
to enable it gets a node that answers no client, and the only way back to a working node is to turn
TLS off — i.e. the failure mode of this defect is a fleet running authenticated over plaintext.
Fix is a dependency, not logic: add `io.netty:netty-transport-native-unix-common`.

### SEC-048 — PASS
```
$ SecTlsProbe 19206 cert.pem <token> plaintext      -> PLAINTEXT call FAILED: Network closed for unknown reason
$ bin/pravaha queries --url grpc://127.0.0.1:19206 --token <valid>
PRV-1041  Network closed for unknown reason
control, against the plaintext harness on 19203:
$ SecTlsProbe 19203 cert.pem ann-token-aaaa plaintext -> PLAINTEXT call SUCCEEDED, results=3
```
**Verdict:** the TLS-configured port does not answer a plaintext client, and the control proves the
probe works against a port that is genuinely plaintext. **Caveat:** given SEC-047, the port answers
nobody, so this passes for a weaker reason than intended — it shows there is no plaintext fallback,
not that a TLS session is established.

### SEC-049 — PASS
```
$ ... --pravaha.flight.tls.certificate=.../nope.pem --pravaha.flight.tls.key=.../key.pem
Caused by: PravahaException: PRV-6104  the TLS certificate .../nope.pem is not a readable file
$ ... --pravaha.flight.tls.certificate=.../cert.pem --pravaha.flight.tls.key=.../nokey.pem
Caused by: PravahaException: PRV-6104  the TLS private key .../nokey.pem is not a readable file
```
**Verdict:** refused at startup, naming the path, for both halves of the pair. No plaintext fallback.

### SEC-050 — PASS
```
$ chmod 000 locked.pem ; ls -l locked.pem
---------- 1 ashutosh ashutosh 1151 locked.pem
$ ... --pravaha.flight.tls.certificate=.../locked.pem --pravaha.flight.tls.key=.../key.pem
== REFUSED
Caused by: PravahaException: PRV-3010  cannot start the Flight SQL server on 0.0.0.0:19206:
  .../locked.pem (Permission denied)
Caused by: java.io.FileNotFoundException: .../locked.pem (Permission denied)
```
**Verdict:** the node refuses; it never starts in plaintext. **Note:** `encryptedWith` checks
`isFile()`, which is true for a file the process cannot read, so the failure arrives later and
arrives as `PRV-3010 LANE_FAILED` ("cannot start the Flight SQL server") rather than
`PRV-6104 TLS_UNREADABLE`. The outcome is right, the error code points an operator at the wrong
thing. Low severity.

### SEC-051 — PASS
```
$ ... --pravaha.security.authentication=token   (no TLS)
WARN c.a.m.pravaha.server.PravahaNode : authentication is on and Flight is serving plaintext, so
credentials travel in the clear; set pravaha.flight.tls.certificate and .key unless something in
front of this node is terminating TLS
security: authentication=token, policy=permissive, audit=memory, flight transport=PLAINTEXT
```
**Verdict:** the compromise is warned about, and the single-line security summary says `PLAINTEXT`.

---

## Group G — secrets in logs and on disk

### SEC-052 — PASS
Run at `--logging.level.root=DEBUG` (the "tired operator debugging a 401" case), with one successful
and one failed call on each of HTTP and Flight:
```
=== DEBUG log, 1528 lines ===
  sek-ret-token          matches=0
  WRONG-MARKER-9999      matches=0
  Bearer                 matches=1
$ grep -n "Bearer" logs/sec052.log
73: DEBUG c.a.m.p.s.security.BearerTokenFilter : Filter 'pravahaAuthentication' configured for use
```
**Verdict:** no token reaches a log line, even at root DEBUG. The single "Bearer" hit is the filter's
own registration message. Not vacuous: the same grep finds `MARKER-LITERAL-4242` in the same file,
so the log genuinely contains request-derived content.

### SEC-053 — PASS
The rejected token `WRONG-MARKER-9999` appears neither in the log (above) nor in the 401 body — the
refusal message is a constant (SEC-015, SEC-020). Zero matches.

### SEC-054 — PASS (with a note)
```
$ grep -n "MARKER-LITERAL-4242" logs/sec052.log
201: DEBUG org.apache.calcite.sql.parser : Reduced `employee` = 'MARKER-LITERAL-4242'
1526:  LogicalFilter(condition=[=($0, 'MARKER-LITERAL-4242')])
```
At the default level (INFO) the literal does not appear anywhere. **Note:** at root DEBUG, Calcite's
own parser and plan logging print query literals — the account numbers and customer ids the config
file says to permission like data. Pravaha's own logging does not. Worth a line in the runbook:
root DEBUG turns the application log into data. Not tested for *bound* parameters (`?` values)
because neither the CLI nor the HTTP surface can register a parameterised continuous query; the
journal path was exercised in-process instead (SEC-055).

### SEC-055 — PASS
```
$ ls -l reg.journal
-rw------- 1 ashutosh ashutosh 139 reg.journal
$ cat -v reg.journal
^@^@^@M-^GPRVH^A...marked^@^@^@SSELECT employee, region, salary FROM payroll WHERE employee =
'MARKER-LITERAL-4242'^@^@^@^A0^@^@^@^Cann^@^@^@^H86400000
(and, in-process, a second journal: mode=rw-------)
```
**Verdict:** created 0600, owner only. The content is exactly what the configuration warns it is —
query text and values in the clear — which is why the mode matters, and the mode is right.

### SEC-056 — PASS
```
toString: Principal[ann in acme, roles=[analyst]]          (ann's claims include email=ann@acme.example)
denial message: PRV-7002  carol may not register 'x' because it reads 'payroll', which they may not
  read: carol is not entitled to payroll. ...
audit events: 27, denials: 5
  DENY ... carol query payroll_view (carol is not entitled to payroll)
  DENY ... anonymous register anon_q (anonymous callers may not register)
  DENY ... carol register:source payroll (carol is not entitled to payroll)
audit dump contains 'ann@acme.example'? false
```
**Verdict:** claims never appear — not in `toString`, not in denial messages, not in the audit
records that carry the principal. Non-vacuous: the principal under test genuinely has a claim.
Also confirms the audit sink records allows (27) as well as denials (5).

---

## Added during execution

### SEC-057 — read-time authorization sees only the view name — **FAIL (HIGH)**
**Not in the case file**: found while executing SEC-043. Recorded here rather than silently folded
into another case.

`SecurityPolicy.mayRead(principal, view)` receives the *registered view name* and nothing else.
Registration correctly authorizes a query's **source streams** (SEC-037) — but reads do not. So a
principal who may read a restricted stream can register a view over it under a name the policy's
rules do not recognise, and a principal denied that stream then reads the derived view freely.
```
control — carol is denied payroll under its own name:
$ bin/pravaha query --sql 'SELECT employee, region, salary FROM payroll_view' --token carol-token-cccc
PRV-1041  PRV-7002  carol may not read 'payroll_view': carol is not entitled to payroll

ann (entitled) registers the same data as "hr_summary":
    SELECT employee, region, salary FROM payroll WHERE salary >= 0

carol reads it:
$ bin/pravaha query --sql 'SELECT employee, region, salary FROM hr_summary' --token carol-token-cccc
employee  region  salary
e1        EU      99000
e2        US      250000
2 rows

ann reads the same view, for comparison:
e1  EU  99000
e2  US  250000
2 rows
```
**Verdict:** carol obtained salary rows she is explicitly not entitled to. The registry knows each
query's source streams — it computes them at registration to run exactly this check — but discards
that knowledge, so a policy author has nothing to key a read decision on but a name a client chose.
Under `AuthenticatedOnlyPolicy` every authenticated caller may register, so every authenticated
caller can perform the laundering. A deny-by-default name allow-list mitigates it only in a
deployment where clients do not choose their own view names, which is not this product's model.
Requirement 3 ("users receive only the data they are authorized for") does not hold across a
registration boundary. Severity HIGH.

*Related and smaller, same evidence path:* registering an existing computation under a second name
returns the **first** name (`registered payroll_view` when `--name hr_summary` was asked for) and
the second name is not queryable — `Object 'hr_summary' not found` for every principal. That is a
registry defect rather than a security one; flagged for the REG area.

---

## Summary

| Verdict | Count |
|---|---|
| PASS | 48 |
| FAIL | 9 |
| BLOCKED | 0 |
| NOT RUN | 0 |
| **Total** | **57** (56 written + 1 added during execution) |

### FAILs in priority order

| # | Severity | What |
|---|---|---|
| SEC-047 | **CRITICAL** | Flight TLS is unusable — `netty-transport-native-unix-common` missing; the port accepts no connection. The only control against plaintext credentials. |
| SEC-008 (part 1) / SEC-007 | **CRITICAL** | `pravaha.security.policy=authenticated` can never start a node with Flight enabled: `hosting()` runs `requireOnePolicy()` before `authorizedBy()` sets the policy. One of the three documented ways to close the server is dead. |
| SEC-034 | **CRITICAL** | Flight `DROP`/`PAUSE`/`RESUME` are unauthorized. An anonymous caller dropped every query on a `policy=authenticated` node; an authenticated-but-denied principal dropped another's payroll query. Data loss plus outage. |
| SEC-043 | **CRITICAL** | Flight `LIST` is unauthorized and returns full SQL text. A principal denied `payroll` received `SELECT ... FROM payroll WHERE employee = 'ACC-0007-SECRET-CUSTOMER'`. |
| SEC-033 | **HIGH** | The same `LIST` gap, reached with **no credential at all** on a node configured to serve only verified callers. |
| SEC-057 | **HIGH** | Read-time authorization is by view name only. A view derived from a restricted stream is readable by a principal denied that stream. Demonstrated leak of salary rows. |
| SEC-008 (part 2) / SEC-028 | **HIGH** | No HTTP controller consults the principal or the policy. `pravaha.security.policy` has no effect on HTTP; with `policy=authenticated` an anonymous caller lists schemas and registers streams. |
| SEC-021 | **MEDIUM** | The 401 body is not an `ApiError` — missing `helpUrl`/`timestamp`/`path`, extra `status`. Two error shapes, which the codebase states three times it will not have. |

### What did NOT fail, and matters

The authorization *engine* is sound. Row filters are ANDed into read plans and honoured
(SEC-038); a filter that cannot be enforced is refused rather than dropped (SEC-039); the subscribe
row-filter leak is genuinely fixed, by refusal (SEC-040); entitlements are in the fingerprint so two
principals with different filters never share a computation (SEC-042); registration authorizes
source streams including aliased ones (SEC-037); journal recovery re-authorizes and refuses revoked
owners (SEC-044/045/046); credentials never reach a log line even at root DEBUG (SEC-052/053); the
journal is 0600 (SEC-055); claims never print (SEC-056). Requirement 1 holds cleanly — `mayRead`
and `mayRegisterQuery` are called only from `pravaha-serving`, `pravaha-flight` and
`pravaha-registry`, and `grep` finds **zero** references to `pravaha.security` in `pravaha-state`,
`pravaha-connect` or any plugin. Nothing is pushed down to persistence.

Every failure above is at a **call site that forgot to ask** (LIST, DROP, PAUSE, RESUME, the HTTP
controllers, read-time lineage), at a **wiring order** (SEC-008), or in a **dependency** (SEC-047) —
not in the policy model itself.

### What I could not cover, and why

* **A real TLS session.** SEC-047 makes it impossible to establish one against this build, so
  SEC-048 proves only that there is no plaintext fallback, and "does an authenticated call work over
  TLS" is untested. It should be re-run once the netty dependency is added.
* **`policy=authenticated` on a real server.** SEC-008 part 1 prevents the node from starting, so
  SEC-033 and SEC-034 were executed against a harness that configures the identical objects
  (`AuthenticatedOnlyPolicy`, no verifier, same `QueryRegistry` and `PravahaFlightServer` from the
  released jars) in the order that works. The code paths are the product's; the assembly is mine.
* **Bound parameter values in logs (SEC-054).** Neither the CLI nor the HTTP surface can register a
  parameterised continuous query, so only literal values in query text were exercised. The journal
  side was covered in-process.
* **Token rotation, expiry and revocation.** `StaticTokenVerifier` has none by design and says so;
  there is no other `TokenVerifier` in the repository to test.
* **Concurrency around authorization** — e.g. a policy that revokes access while a subscription is
  open, or a `drop` racing a `register` on the same name. Out of time; worth a pass of its own,
  particularly given that `SecurityPolicy` explicitly promises no decision caching.
* **The Python SDK and the console** as clients. Only the Java CLI, raw Flight clients and `curl`
  were used.

---

# Re-QA 2026-09-12

Verification pass against the rebuilt `pravaha-server-0.1.0-SNAPSHOT-app.jar` (now containing
`netty-transport-native-unix-common-4.1.135.Final`) and `pravaha-cli-0.1.0-SNAPSHOT-cli.jar`.
Ports 18200–18219 / 19200–19219. Harness rebuilt against the new jars; **no production code was
modified**. Every server started here was killed by PID.

Two harness sources were added to the QA scratch directory for this pass: `SecTlsFull` (a full
Flight SQL round trip — register, `getFlightInfo`, `getStream` — over a chosen transport) and
`SecLeakProbe` (every other Flight route that could disclose what `LIST` now hides).

A node with real rules is still not reachable through configuration — see **SEC-061** — so the
rule-bearing cases are again run against `QaPolicy` on the released jars. The `AuthenticatedOnlyPolicy`
cases now run against a **real node** (18207/19208), which is new: that configuration could not start
before.

| principal | rule (unchanged from the original pass) |
|---|---|
| `ann` | reads everything |
| `bob` | reads everything **with row filter `region = 'EU'`** |
| `carol` | **denied** any view whose name contains `payroll` |

---

## Re-tested: the nine FAILs

### SEC-047 — TLS on the Flight transport — **VERIFIED-FIXED**

The dependency is present and a client completes real Flight calls over the socket, data included.

```
$ unzip -l pravaha-server-0.1.0-SNAPSHOT-app.jar | grep unix-common
    44327  BOOT-INF/lib/netty-transport-native-unix-common-4.1.135.Final.jar

$ bin/pravaha-server --server.port=18205 --pravaha.flight.port=19206 \
      --spring.config.additional-location=file:.../tls-tokens.yaml \
      --pravaha.flight.tls.certificate=.../cert.pem --pravaha.flight.tls.key=.../key.pem
security: authentication=token, policy=permissive, audit=memory, flight transport=TLS
sources bound: [payroll <- filesystem[path, schema]]
Flight SQL listening on 0.0.0.0:19206

$ SecTlsFull 19206 tls/cert.pem <valid-token>        (grpc+tls, cert as the ONLY trust anchor)
channel: grpc+tls, trust anchor = tls/cert.pem
  register -> PRVH|...|tlsview|...|RUNNING|...|3d0031e7df37
  getFlightInfo -> schema=[employee: Utf8, region: Utf8, salary: Int(64, true)] endpoints=1
  row: e1 EU 99000
  row: e2 US 250000
  row: e3 EU 55000
TLS END-TO-END OK, rows=3

$ openssl s_client -connect 127.0.0.1:19206 -alpn h2 -servername localhost
SSL handshake has read 1312 bytes and written 1640 bytes
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
ALPN protocol: h2
```
**Verdict:** not a port that merely answers a handshake — a `doAction` control call, a
`getFlightInfo` plan and a `getStream` carrying three real rows, all over one TLS channel with the
server's certificate as the only trust anchor. Not vacuous: the same probe run with a *different*
self-signed certificate as the trust anchor fails, and the same probe in plaintext mode fails
(below), so the session is genuinely negotiated and genuinely verified.

Negatives, all still holding:
```
$ SecTlsFull 19206 cert.pem <token> plaintext      -> PLAINTEXT FAILED: Network closed for unknown reason
$ bin/pravaha queries --url grpc://127.0.0.1:19206 --token <valid>
PRV-1041  Network closed for unknown reason
$ SecTlsProbe 19206 other-selfsigned.pem <token>   -> TLS call FAILED: ... SslHandler#0 ...
$ ... --pravaha.flight.tls.certificate=.../nope.pem     -> PRV-6104 the TLS certificate ... is not a readable file
$ ... --pravaha.flight.tls.certificate=.../<a directory> -> PRV-6104 the TLS certificate ... is not a readable file
$ chmod 000 locked.pem; ... --certificate=.../locked.pem -> PRV-3010 cannot start the Flight SQL server ... (Permission denied)
$ ... --certificate=<the KEY file> --key=<the CERT file>  -> IllegalArgumentException: Input stream not contain valid certificates
$ ss -ltn | grep -E '18206|19207'                        -> nothing bound on 18206/19207
```
An unreadable certificate refuses the start and leaves nothing listening; a plaintext client against
a TLS port is dropped; there is no plaintext fallback **when both halves of the pair are set**.
SEC-050's note stands: `chmod 000` still arrives as `PRV-3010 LANE_FAILED` rather than `PRV-6104`.

**But the pair is not checked as a pair.** Setting only one half is a new defect in each direction —
see **SEC-059** (key without certificate: silent plaintext) and **SEC-060** (certificate without key:
raw NPE). SEC-059 is the security-relevant one.

Also new, and operationally serious given that this feature now works: the shipped client cannot use
it against anything but a publicly-trusted certificate — see **SEC-063**.

### SEC-048 — no plaintext fallback on a TLS port — **PASS, and now for the right reason**
The original PASS carried a caveat: the port answered nobody, so "no plaintext fallback" was proved
vacuously. With SEC-047 fixed the same port now answers a TLS client with rows (above) and still
drops a plaintext one. The caveat is discharged.

### SEC-007 / SEC-008 part 1 — `policy=authenticated` cannot start a node — **VERIFIED-FIXED**

```
$ bin/pravaha-server --server.port=18207 --pravaha.flight.port=19208 \
      --spring.config.additional-location=file:.../authpol.yaml
      (authentication: token, policy: authenticated, two tokens, Flight enabled)
== authpol: STARTED (pid 383314)
security: authentication=token, policy=authenticated, audit=memory, flight transport=PLAINTEXT
Flight SQL listening on 0.0.0.0:19208
```
`PravahaNode.start` now calls `.authorizedBy(securityPolicyOf(registry), auditSink())` **before**
`.hosting(registry)`, so `requireOnePolicy()` compares the registry's real policy against itself
rather than against the constructor's `PERMISSIVE` initialiser. The node serves:
```
$ bin/pravaha register --name secret_pay --sql-file secret.sql --keys 0 --url grpc://127.0.0.1:19208 --token <ann>
registered secret_pay  state=RUNNING  fingerprint=0f3582b08599
$ bin/pravaha queries --url grpc://127.0.0.1:19208 --token <ann>
NAME        STATE    FINGERPRINT   ROWS IN
secret_pay  RUNNING  0f3582b08599  3
```
Not vacuous: the identical command line with `policy=permissive` was never the thing under test, and
the node's own summary line reports `policy=authenticated` while Flight is listening.

Case-insensitivity (SEC-007's original spelling `AUTHENTICATED`) also starts — see the bypass table,
which shows `AUTHENTICATED` reaching the *contradiction* refusal rather than the policy-name refusal,
i.e. the match is case-insensitive as intended.

### The new refusal: `policy=authenticated` + `authentication=none` — **works, and I could not bypass it**

```
$ bin/pravaha-server --server.port=18208 --pravaha.flight.port=19209 --pravaha.security.policy=authenticated
Caused by: PravahaException: PRV-7002  pravaha.security.policy=authenticated with
pravaha.security.authentication=none is a node nobody can use: the policy serves only verified
callers and nothing here can verify one. Set pravaha.security.authentication=token and configure
pravaha.security.tokens, or choose a policy that admits anonymous callers.
```
Eleven attempts to reach the contradictory state anyway:

| attempt | result |
|---|---|
| `--pravaha.security.policy=authenticated` | REFUSED (contradiction) |
| `--pravaha.security.policy=AUTHENTICATED` | REFUSED (contradiction) — casing does not evade |
| `--pravaha.security.policy=" Authenticated "` | REFUSED — leading/trailing space is stripped |
| `--pravaha.security.policy=authenticated-only` (the alias) | REFUSED |
| `+ --pravaha.security.allow-anonymous=true` | REFUSED — the acknowledgement does **not** unlock it |
| `+ --spring.profiles.active=dev` | REFUSED — the dev profile does not unlock it |
| `PRAVAHA_SECURITY_POLICY=authenticated` (environment) | REFUSED — relaxed binding is covered |
| `+ --pravaha.security.authentication=tokens` (typo → none) | REFUSED |
| `+ --pravaha.flight.enabled=false` | REFUSED — the old workaround is correctly closed too |
| `--pravaha.security.policy=com.ash...SecurityPolicy$1` (a class name) | REFUSED: *"is not a policy this node knows"* |
| `+ --pravaha.security.authentication=TOKEN` (no tokens configured) | STARTED — and refuses every caller (SEC-009 behaviour), so not a hole |

**Verdict:** the refusal is on the value *after* normalisation and is checked independently of
`allow-anonymous`, the active profile and whether Flight is enabled. I found no way through it.
The last row is the one worth noticing for the right reason: a *class name* is refused, which is
correct given the parser — but it is also the only thing an operator could have typed to install
rules of their own, and the message invites exactly that. See **SEC-061**.

### SEC-033 — unauthenticated `LIST` on an `AuthenticatedOnlyPolicy` node — **VERIFIED-FIXED**

Run against the harness in the original configuration (`AuthenticatedOnlyPolicy`, no `TokenVerifier`):
```
$ bin/pravaha queries --url grpc://127.0.0.1:19204            (NO credential)
no continuous queries are registered
$ bin/pravaha drop   --name sales_view --url grpc://127.0.0.1:19204
PRV-7002  anonymous may not drop 'sales_view': this server serves data only to authenticated
callers, and this call presented no credential Pravaha could verify
$ bin/pravaha pause  --name sales_view --url ...  -> same refusal
$ bin/pravaha resume --name sales_view --url ...  -> same refusal
```
And on a **real** node with a verifier (18207/19208, `policy=authenticated`), `PrincipalMiddleware`
still refuses every verb before the producer runs:
```
$ bin/pravaha queries|drop|pause|resume --url grpc://127.0.0.1:19208     (no credential)
PRV-1041  PRV-7001  this server requires a credential: send it as the header 'authorization: Bearer <token>'
$ bin/pravaha queries --url grpc://127.0.0.1:19208 --token <ann>          (control)
NAME        STATE    FINGERPRINT   ROWS IN
secret_pay  RUNNING  0f3582b08599  3
```
**Verdict:** fixed, twice over. Two caveats, neither of which changes the verdict:
1. The configuration this case describes is now refused at startup by design, so the fix is
   defence-in-depth rather than the live control.
2. The *names* it hides are still obtainable from a single error message — **SEC-058**.

### SEC-034 — `DROP`/`PAUSE`/`RESUME` unauthorized — **PARTIALLY-FIXED**

The reported symptom is gone. An explicitly denied principal is refused, and a permitted one works:
```
$ bin/pravaha drop   --name payroll_view --url grpc://127.0.0.1:19203 --token carol-token-cccc
PRV-1041  PRV-7002  carol may not drop 'payroll_view': carol is not entitled to payroll
$ bin/pravaha pause  --name payroll_view --url ... --token carol-token-cccc   -> ... may not pause ...
$ bin/pravaha resume --name payroll_view --url ... --token carol-token-cccc   -> ... may not resume ...

$ bin/pravaha pause  --name hr_summary --url ... --token ann-token-aaaa   -> pauseped hr_summary
$ bin/pravaha resume --name hr_summary --url ... --token ann-token-aaaa   -> resumeped hr_summary
$ bin/pravaha drop   --name hr_summary --url ... --token ann-token-aaaa   -> dropped hr_summary
```

**Now the attack on the default.** `mayAdminister` defaults to `mayRead`, so *may read* means *may
destroy*. Two principals who plainly should not be administrators are:

```
(1) bob — a principal the policy restricts to a row filter, region = 'EU'.
    He has never been shown a whole row set of sales_view in his life.
$ bin/pravaha pause --name sales_view --url grpc://127.0.0.1:19203 --token bob-token-bbbb
pauseped sales_view
$ bin/pravaha drop  --name sales_view --url grpc://127.0.0.1:19203 --token bob-token-bbbb
dropped sales_view

(2) carol — denied 'payroll' — against a payroll query whose NAME does not say payroll.
$ bin/pravaha drop --name secret_pay --url grpc://127.0.0.1:19203 --token carol-token-cccc
dropped secret_pay
    (secret_pay is  SELECT employee, region, salary FROM payroll
                    WHERE employee = 'ACC-0007-SECRET-CUSTOMER' , registered by ann)

$ bin/pravaha queries --url ... --token ann-token-aaaa       (the owner, afterwards)
NAME          STATE    FINGERPRINT   ROWS IN
payroll_view  RUNNING  3d0031e7df37  1
hr_summary    RUNNING  57ed47d2dabf  2
```
And on the **shipped** `policy=authenticated` node, where every authenticated caller may read
everything and therefore administer everything:
```
$ bin/pravaha queries --url grpc://127.0.0.1:19208 --token <carol>
NAME        STATE    FINGERPRINT   ROWS IN
secret_pay  RUNNING  0f3582b08599  3          <- ann's query, with its SQL on the wire
$ bin/pravaha pause --name secret_pay --url grpc://127.0.0.1:19208 --token <carol>   -> pauseped secret_pay
$ bin/pravaha drop  --name secret_pay --url grpc://127.0.0.1:19208 --token <carol>   -> dropped secret_pay
$ bin/pravaha queries --url grpc://127.0.0.1:19208 --token <ann>                     -> no continuous queries are registered
```

**The exposure, precisely, for the owner to decide on.**
* `mayAdminister` is consulted, so the hook is there and works. What it inherits is a policy that
  answers a *read* question.
* Consequence A — **read access is destroy access.** Any principal a policy grants read on a view,
  including one granted only a filtered slice of it, may `DROP` that view: its accumulated state,
  for every other principal holding a name for it. There is no ownership test anywhere: the
  registration's owning `Principal` is recorded (SEC-044/045 prove it survives a journal replay) and
  `requireAdministrable` does not look at it.
* Consequence B — **the shipped non-permissive policy makes the hook a no-op.**
  `AuthenticatedOnlyPolicy.mayRead` allows every authenticated caller, so on `policy=authenticated`
  — the only closed configuration a server node can actually be given — `mayAdminister` allows every
  authenticated caller too. Any token holder can drop any other token holder's continuous query.
  That is the whole fleet's exposure today, because a custom policy cannot be installed (SEC-061).
* Consequence C — the deny that *does* work is keyed on the view **name** (Consequence of SEC-057's
  root cause): `carol` was refused on `payroll_view` and permitted on `secret_pay`, which reads the
  same stream.
* An override exists (`mayAdminister` is a default method), and the javadoc calls it "the weakest
  rule that is not wrong" — but no shipped policy overrides it, and no server deployment can supply
  one. The documentation should state Consequence A as the contract, and `AuthenticatedOnlyPolicy`
  should almost certainly override `mayAdminister` to compare against the registration's owner.

*(Still cosmetic, still there: the CLI prints "pauseped" / "resumeped".)*

### SEC-043 — `LIST` discloses SQL text to a principal denied the underlying stream — **STILL-FAILING (CRITICAL)**

`LIST` is now filtered — by **view name**, which is not the thing that was leaking.

```
$ bin/pravaha register --name secret_pay --url grpc://127.0.0.1:19203 --token ann-token-aaaa \
      --sql-file <<< "SELECT employee, region, salary FROM payroll
                      WHERE employee = 'ACC-0007-SECRET-CUSTOMER'"
registered secret_pay  fingerprint=0f3582b08599
$ bin/pravaha register --name payroll_eu  ... "SELECT employee, region, salary FROM payroll WHERE region = 'EU'"

$ SecListProbe 19203 carol-token-cccc          (carol is denied 'payroll' by the policy)
result 1: PRVH|...|sales_view|...|RUNNING|...|SELECT order_id, region, amount FROM sales|...
result 2: PRVH|...|hr_summary|...|RUNNING|...|SELECT employee, region, salary FROM payroll WHERE salary >= 0|...
result 3: PRVH|...|secret_pay|...|RUNNING|...|SELECT employee, region, salary FROM payroll WHERE employee = 'ACC-0007-SECRET-CUSTOMER'|...|0f3582b08599|...|0
results=3

$ SecListProbe 19203 ann-token-aaaa            (control)
results=4                                      (the same three, plus payroll_view)
```
**Verdict:** the filter calls `policy.mayRead(principal, name)` on the *registered view name*, so it
hides a query only when the policy's rule happens to match the name a client chose. `carol` is
denied `payroll`; `payroll_view` is hidden; `secret_pay` is not — and `secret_pay` is a payroll query
whose SQL carries the literal `'ACC-0007-SECRET-CUSTOMER'`. **The exact string from the original
SEC-043 evidence is still delivered to the exact principal who was not supposed to see it.** The
severity is unchanged.

This is the same root cause as SEC-057: an authorization decision taken on a client-chosen label
rather than on the query's known source streams. The registry computes the source set at
registration to run precisely this check and then discards it. Until `mayRead` is given the lineage,
the `LIST` filter cannot be made correct — it can only be made to look correct on the cases whose
names happen to match.

A second, independent way through the same filter — names only, but complete — is **SEC-058**.

### SEC-057 — read-time authorization sees only the view name — **STILL-FAILING (HIGH)**

Unchanged, re-demonstrated on the current build:
```
control — carol denied under the stream's own name:
$ bin/pravaha query --sql 'SELECT employee, region, salary FROM payroll_view' --url grpc://127.0.0.1:19203 --token carol-token-cccc
PRV-1041  PRV-7002  carol may not read 'payroll_view': carol is not entitled to payroll

ann (entitled) has registered the same payroll data as "hr_summary":
    SELECT employee, region, salary FROM payroll WHERE salary >= 0

$ bin/pravaha query --sql 'SELECT employee, region, salary FROM hr_summary' --url ... --token carol-token-cccc
employee  region  salary
e1        EU      99000
e2        US      250000
2 rows
$ ... --token ann-token-aaaa           (identical output — carol is not getting a filtered view)
```
**The exposure, restated.** `SecurityPolicy.mayRead(principal, view)` is handed a string a client
chose. Registration is authorized correctly against the query's real source streams — and that
knowledge is then thrown away, so every subsequent read of the resulting view is judged on a label.
A policy author has nothing to key a read decision on except a name that the data's subject does not
control. Requirement 3 ("users receive only the data they are authorized for") does not survive a
registration boundary.

What bounds it — and this is the honest limit of the severity:
```
$ bin/pravaha register --name carol_clean --url ... --token carol-token-cccc \
      --sql-file <<< "SELECT employee, region, salary FROM payroll"
PRV-1041  PRV-7002  carol may not register 'carol_clean' because it reads 'payroll', which they may
not read: carol is not entitled to payroll. A registration is a standing read of everything the
query names, so it is refused here rather than at the first row.

$ bin/pravaha register --name carol_chain --url ... --token carol-token-cccc \
      --sql-file <<< "SELECT employee, region, salary FROM hr_summary"
PRV-1041  PRV-2002  Object 'hr_summary' not found. Known streams: [sales, payroll]
```
So carol cannot launder the data *herself*, and cannot chain off a view either. The leak needs an
entitled principal to register the derived view — which under `AuthenticatedOnlyPolicy` is every
authenticated caller, and in any real deployment is a routine act nobody would think of as a grant.
HIGH, not CRITICAL, and still the most structurally important item on the list: SEC-043's fix is
already wrong *because* of it, and SEC-034's working deny is keyed on the same string.

### SEC-008 part 2 / SEC-028 — HTTP does not consult the policy — **PARTIALLY-FIXED (HIGH)**

The refusal closes the **anonymous** version of this and nothing else.

```
$ grep -rn "SecurityPolicy|principalOf|pravaha.principal|Principal" \
      pravaha-server/src/main/java/com/ash/messaging/pravaha/server/api/
(count: 0 across QueryController, StreamController, StatusController, ApiExceptionHandler)
```
`BearerTokenFilter` still parks a `Principal` on the request as `pravaha.principal` and still exposes
`principalOf(request)`; nothing on the HTTP side calls it.

What is genuinely closed: with `authentication: none` the node can no longer be given
`policy=authenticated`, so the previous demonstration — an anonymous caller listing every stream
schema and registering streams on a node whose configuration read "only verified callers see
anything" — is unreachable. Verified:
```
$ curl -o /dev/null -w '%{http_code}' http://127.0.0.1:18207/api/v1/status    -> 401
$ curl ... /api/v1/streams                                                     -> 401
$ curl -X POST -d '{"name":"evil","schema":"a:STRING"}' ... /api/v1/streams    -> 401
$ curl -H 'Authorization: Bearer <ann>' ... /api/v1/streams                     -> 200
```

**What remains exposed, exactly.** With `authentication: token` and a principal the policy denies:
*every valid token is a full administrator of the HTTP surface, whatever the policy says.* On the
`policy=authenticated` node, as `carol`:
```
$ curl -H 'Authorization: Bearer <carol>' http://127.0.0.1:18207/api/v1/streams
[{"name":"payroll","version":1,"fieldCount":3,"fields":[{"name":"employee",...},{"name":"salary",...}]}]

$ curl -X POST -H 'Authorization: Bearer <carol>' -d '{"name":"carol_injected","schema":"a:STRING,b:INT64"}' \
       http://127.0.0.1:18207/api/v1/streams
HTTP 201  {"name":"carol_injected",...}
$ curl -H 'Authorization: Bearer <carol>' http://127.0.0.1:18207/api/v1/streams
['payroll', 'carol_injected']

$ curl -X POST -H 'Authorization: Bearer <carol>' -d '{"sql":"SELECT employee, salary FROM payroll"}' \
       'http://127.0.0.1:18207/api/v1/queries/explain?level=physical'
{"level":"physical","plan":"Project[employee, salary]\n  Scan(payroll)\n","outputFields":[...]}
```
So, precisely: **read** the name, version and full field list of every configured stream; **plan and
validate arbitrary SQL** over any of them, receiving the physical plan and output schema;
**register** a stream. No `mayRead`, no `mayRegisterQuery`, no principal. On Flight the same token
would be judged; on HTTP it is not. Two surfaces of one node, two rule sets.

Three things bound it, and the owner should know all three:
1. The HTTP surface has **no read path to data** — no endpoint returns rows. The disclosure is
   schemas, plans and stream names, not tenant data.
2. `POST /api/v1/streams` turns out not to reach the engine at all — see **SEC-062**. The write is
   a lie rather than a mutation, which makes it an integrity problem on the API's own view of the
   world and not an engine compromise.
3. The scenario "a custom policy denying a principal" cannot be constructed on a server node at all
   today (**SEC-061**). Today's real exposure is therefore (1)+(2) for any token holder; the moment
   a policy becomes installable, HTTP will ignore it wholesale.

### SEC-021 — the 401 body is not an `ApiError` — **PARTIALLY-FIXED (MEDIUM, unchanged)**

The four missing fields are there. An extra one that `ApiError` does not have is still there, so the
two shapes are still two shapes.
```
$ curl http://127.0.0.1:18207/api/v1/streams                                  (401, BearerTokenFilter)
{"code":"PRV-7001","message":"this server requires a credential; send it as 'Authorization: Bearer <token>'",
 "helpUrl":"https://docs.pravaha.io/errors/PRV-7001","timestamp":"2026-09-13T01:48:45.569862379Z",
 "path":"/api/v1/streams","status":401}

$ curl -H 'Authorization: Bearer <ann>' .../api/v1/streams/nosuch            (404, ApiExceptionHandler)
{"code":"PRV-2003","message":"PRV-2003  no stream named 'nosuch'. Registered: [payroll]",
 "helpUrl":"https://docs.pravaha.io/errors/PRV-2003","timestamp":"...","path":"/api/v1/streams/nosuch"}

401 keys: ['code', 'helpUrl', 'message', 'path', 'status', 'timestamp']
404 keys: ['code', 'helpUrl', 'message', 'path',           'timestamp']
only in 401: ['status']
```
Deserialised into the server's own `ApiError` record with a default Jackson mapper (`SecErrShape`):
```
401 (BearerTokenFilter): FAILED -> UnrecognizedPropertyException: Unrecognized field "status"
    (class ApiError), not marked as ignorable (5 known properties: "code","helpUrl","message","path","timestamp")
404 (ApiExceptionHandler): parsed as ApiError OK
```
**Verdict:** field for field it is `ApiError` **plus `status`**, and `status` is precisely the field
the original defect called out as extra. The client this defect was written about — one with a
strict error deserialiser — still breaks, on the same response, for the same reason. Also unchanged:
```
401: Content-Type: application/json;charset=ISO-8859-1
404: Content-Type: application/json
```
Cause is unchanged too: `refuse()` still builds the JSON by string concatenation instead of
serialising an `ApiError`. Adding four fields to a hand-built string is treating the symptom; the
defect is that there are two writers of one shape. Drop `status`, set UTF-8, or better, serialise
the record.

---

## Regression hunt

Chosen because they exercise the code the fixes touched: `refuseAccidentalOpenServer` and
`securityPolicy()` (SEC-001..006), the `PravahaFlightSqlProducer.doAction` switch that gained
`requireAdministrable` and the `LIST` filter (SEC-032), and the read path that the `LIST` filter now
shares a policy call with (SEC-035/038/040).

| case | what it guards | result |
|---|---|---|
| SEC-001 | shipped defaults still refuse to start | **PASS** — `PRV-7002 ... accept unauthenticated callers`, nothing bound |
| SEC-002 | the documented first-run path still works | **PASS** — `--spring.profiles.active=dev` starts open, `/api/v1/status` 200 |
| SEC-003 | `allow-anonymous=true` still starts | **PASS** |
| SEC-005 | boolean binding still strict | **PASS** — `allow-anonymous=enabled` → `Invalid boolean value 'enabled'` |
| SEC-006 | unknown policy name still fails closed | **PASS** — `'authentcated' ... is not a policy this node knows` |
| SEC-032 | a verifier refuses every verb before the producer | **PASS** — queries/drop/pause/resume with no credential all `PRV-7001` on 19208 |
| SEC-035 | deny on read still enforced | **PASS** — carol refused `payroll_view`, ann gets the row |
| SEC-038 | row filter still ANDed into the plan | **PASS** — ann sees `EU` and `US`; bob's distinct regions are `EU` only |
| SEC-040 | subscribe refuses a filtered principal rather than leaking | **PASS** — full `PRV-7002 ... a subscription cannot enforce a filter` message intact |

**No regressions.** The authorization engine is as sound as it was; every failure remains at a call
site, in wiring, or in what the policy is asked.

---

## New defects

### SEC-058 — a "not found" error enumerates every view the `LIST` filter just hid — **HIGH**

The `LIST` filter is undone by one call, by any authenticated principal, on three different verbs.

```
$ bin/pravaha queries --url grpc://127.0.0.1:19203 --token carol-token-cccc     (the filtered LIST)
NAME        STATE    FINGERPRINT   ROWS IN
secret_pay  RUNNING  0f3582b08599  0

$ SecLeakProbe 19203 carol-token-cccc                                            (getFlightInfo, absent name)
getFlightInfo(absent): FlightRuntimeException -- PRV-2002  Object 'view_that_does_not_exist_zz'
    not found. Known streams: [secret_pay, payroll_eu, payroll_view]

$ bin/pravaha drop --name zzz_nope --url ... --token carol-token-cccc
PRV-1041  PRV-8002  no query named 'zzz_nope' is registered; this node has
    [sales_view, payroll_view, hr_summary]

$ bin/pravaha subscribe --view zzz_nope --url ... --token carol-token-cccc
PRV-8002  no query named 'zzz_nope' is registered; this node has [sales_view, payroll_view, hr_summary]

$ bin/pravaha register --name x --sql-file <<< 'SELECT ... FROM hr_summary' --token carol-token-cccc
PRV-1041  PRV-2002  Object 'hr_summary' not found. Known streams: [sales, payroll]
```
**Verdict:** carol's filtered listing shows one view. A single request for a name that does not exist
hands her all three, `payroll_view` included — the one the filter exists to hide. `PRV-2002` names
every view or base stream on the read and register paths; `PRV-8002` names every registered query on
the drop, pause, resume and subscribe paths. Both messages are built from the full registry with no
principal in scope.

There is also a plain existence oracle even without the enumeration: `carol may not read
'payroll_view'` and `Object 'zzz' not found` are distinguishable answers, so a name can be probed
one at a time.

`PrincipalMiddleware`'s javadoc — quoted in the original SEC-033 — says "the set of view names is a
map of what this deployment does … worth a refusal". The `LIST` fix acted on that; these four verbs
did not. The fix is to build the "did you mean" list from what the principal may read, and to answer
a denied *existing* name and an absent name identically.

This is why SEC-043's verdict is CRITICAL on the SQL text and this one is HIGH: names leak here,
names **and query text including literal account identifiers** leak there.

### SEC-059 — `tls.key` without `tls.certificate` starts the node in plaintext — **HIGH**

```
$ bin/pravaha-server --server.port=18206 --pravaha.flight.port=19207 \
      --spring.config.additional-location=file:.../tokens.yaml \
      --pravaha.flight.tls.key=.../key.pem                       # certificate NOT set
== STARTED
WARN  authentication is on and Flight is serving plaintext, so credentials travel in the clear;
      set pravaha.flight.tls.certificate and .key unless something in front of this node is
      terminating TLS
security: authentication=token, policy=permissive, audit=memory, flight transport=PLAINTEXT
Flight SQL listening on 0.0.0.0:19207

$ SecTlsFull 19207 cert.pem <valid-token> plaintext
channel: grpc (plaintext)
  register -> PRVH|...|fbview|...|RUNNING|...
  getFlightInfo -> schema=[employee: Utf8, region: Utf8, salary: Int(64, true)] endpoints=1
PLAINTEXT END-TO-END OK, rows=0

$ SecTlsFull 19207 cert.pem <valid-token>            (a TLS client against the same port)
TLS FAILED: FlightRuntimeException -- io exception Channel Pipeline: [SslHandler#0, ...]
```
**Verdict:** an operator who sets half the pair — a typo in the `certificate` key, a templating
mistake, a secret that failed to mount — gets a running node that accepts bearer tokens over
cleartext, and the only signal is the *same generic warning* every plaintext node prints. The two
halves are never compared: `encryptedWith` is simply not called when the certificate is null, and the
node reports `flight transport=PLAINTEXT` as though nobody had asked for TLS. Contrast SEC-049,
where a certificate pointing at a missing file correctly refuses the start — the failure closed when
the operator got the path wrong and opens when they got the property name wrong.

The mirror case refuses, so the asymmetry is not deliberate: see SEC-060. The fix is to treat "one
of `certificate`/`key` set" as a configuration error, and to say *which* half is missing.

### SEC-060 — `tls.certificate` without `tls.key` throws a raw NullPointerException — **LOW**

```
$ bin/pravaha-server ... --pravaha.flight.tls.certificate=.../cert.pem      # key NOT set
== REFUSED
security: authentication=token, policy=permissive, audit=memory, flight transport=TLS
Caused by: java.lang.NullPointerException: Cannot invoke "java.io.File.isFile()" because "privateKey" is null
$ ss -ltn | grep -E '18206|19207'   -> nothing bound
```
**Verdict:** fails closed, which is the important half, but with no `PRV-` code, no named property
and no guidance — `encryptedWith` dereferences the key before checking it. An operator debugging
this sees a Java stack trace where every other configuration error in this node produces a sentence.
Same root cause as SEC-059, opposite symptom; one fix closes both.

### SEC-061 — a server node cannot be given a `SecurityPolicy` — **HIGH**

```
$ bin/pravaha-server ... --pravaha.security.policy=com.ash.messaging.pravaha.security.SecurityPolicy$1
Caused by: PravahaException: PRV-7002  pravaha.security.policy is 'com.ash...SecurityPolicy$1', which
is not a policy this node knows. Use 'permissive' or 'authenticated', or implement SecurityPolicy for
rules of your own.

$ sed -n '281,295p' PravahaNode.java
    return switch (configured.toLowerCase(Locale.ROOT)) {
        case "permissive" -> SecurityPolicy.PERMISSIVE;
        case "authenticated", "authenticated-only" -> new AuthenticatedOnlyPolicy();
        default -> throw new PravahaException(...);
    };

$ grep -rn "@Bean" pravaha-server/.../PravahaServerApplication.java
(three beans: pravahaEngine, pravahaAuthentication, pravahaLifecycle — none of them a SecurityPolicy,
 and PravahaNode's constructor takes no SecurityPolicy)
```
**Verdict:** `bin/pravaha-server` can run exactly two policies, `PERMISSIVE` and
`AuthenticatedOnlyPolicy`, and **neither denies any authenticated principal anything.** There is no
property that names a class, no bean an operator can contribute, no `ObjectProvider<SecurityPolicy>`.
The error message above ends "or implement SecurityPolicy for rules of your own", and
`docs/SECURITY.md`'s "Setting it up" shows `.authorizedBy(myPolicy, myAuditSink)` — both describe the
**embedded** path only. An operator who follows either instruction on a server has nowhere to put the
result.

Why HIGH rather than a documentation nit: it is the ceiling on what this node can enforce.
Requirement 3 ("users receive only the data they are authorized for") is not merely unimplemented on
HTTP (SEC-028) — on a server node it is **unachievable by configuration on either surface**, because
no configurable policy ever returns a deny or a row filter for an authenticated caller. Every
per-tenant, row-filtered or entitlement-based deployment this engine advertises requires code the
server has no seam for. It also means SEC-034's "the operator can override `mayAdminister`" is not
an available remedy for a server deployment.

### SEC-062 — `POST /api/v1/streams` never reaches the engine — **MEDIUM**

```
$ curl -X POST -H 'Authorization: Bearer <carol>' -d '{"name":"carol_injected","schema":"a:STRING,b:INT64"}' \
       http://127.0.0.1:18207/api/v1/streams
HTTP 201  {"name":"carol_injected","version":1,"fieldCount":2,...}
$ curl -H 'Authorization: Bearer <carol>' http://127.0.0.1:18207/api/v1/streams
['payroll', 'carol_injected']

$ bin/pravaha register --name ci_view --sql-file <<< 'SELECT a, b FROM carol_injected' \
      --url grpc://127.0.0.1:19208 --token <carol>
PRV-1041  PRV-2002  Object 'carol_injected' not found. Known streams: [payroll]
```
**Verdict:** `StreamController.register` writes into `StreamCatalog`; `PravahaNode.start` copies
`streams.all()` into the `QueryRegistry` **once**, at startup. A stream registered afterwards over
HTTP is visible on `GET /api/v1/streams` and invisible to every query, and is lost on restart. The
API reports a success that changed nothing.

Security-relevant because it is an unauthenticated-by-policy write (SEC-028) into the surface an
operator, the console and the OpenAPI-driven clients read: any token holder can inject arbitrary
stream names and schemas into the node's self-description. It also *bounds* SEC-028 — the write is
not an engine mutation. Primarily a functional defect; flagged here because it changes SEC-028's
severity and belongs to whoever owns the HTTP API.

### SEC-063 — the shipped client cannot present a TLS trust anchor — **MEDIUM**

```
$ bin/pravaha queries --url grpc+tls://localhost:19206 --token <valid>
PRV-1041  io exception
Channel Pipeline: [ProtocolNegotiators$ClientTlsHandler#0, WriteBufferingAndExceptionHandler#0, ...]

$ grep -rn "trustedCertificates|--tls|tls-ca" pravaha-cli/src/main/java/  -> 0 hits
$ sed -n '142,145p' sdk/pravaha-sdk-java-flight/.../PravahaFlightClient.java
    Location location = options.endpoint().tls() ? Location.forGrpcTls(...) : Location.forGrpcInsecure(...);
    FlightClient transport = FlightClient.builder(allocator, location).build();       // system trust store only
```
**Verdict:** `ClientOptions` understands `grpc+tls://` and refuses to send a token over plaintext
without `allowInsecureToken`, which is good — and neither it nor the CLI has any way to supply a CA
or a certificate, so the client trusts only the JVM's default store. A node with a private-CA or
self-signed certificate — the normal case for an internal deployment, and the case SEC-047's own
repro uses — cannot be reached by `bin/pravaha` at all, and the failure is an unexplained
`io exception` with a netty pipeline dump rather than "the server's certificate is not trusted".
My probe reaches the same node because it calls `FlightClient.builder(...).trustedCertificates(...)`,
which the SDK does not expose. The practical effect is that turning TLS on breaks the shipped CLI,
which is the pressure that turns it back off — the same failure mode SEC-047 was filed about, one
layer out.

---

## Re-QA summary

| original FAIL | verdict |
|---|---|
| SEC-047 (TLS unusable) | **VERIFIED-FIXED** |
| SEC-007 / SEC-008 part 1 (`policy=authenticated` cannot start) | **VERIFIED-FIXED** |
| SEC-033 (anonymous `LIST`) | **VERIFIED-FIXED** |
| SEC-034 (`DROP`/`PAUSE`/`RESUME` unauthorized) | **PARTIALLY-FIXED** |
| SEC-021 (401 body not an `ApiError`) | **PARTIALLY-FIXED** |
| SEC-008 part 2 / SEC-028 (HTTP ignores the policy) | **PARTIALLY-FIXED** (anonymous case closed; principal-blind HTTP unchanged) |
| SEC-043 (`LIST` leaks SQL text) | **STILL-FAILING** |
| SEC-057 (read-time authorization by name) | **STILL-FAILING** |

3 verified fixed, 3 partially fixed, 2 still failing. 0 regressions. 6 new defects.

### Everything open, re-ranked by real-world exposure

| # | Severity | Exposure |
|---|---|---|
| SEC-043 | **CRITICAL** | A principal denied a stream still receives the full SQL of queries over it, literals included. The `LIST` filter keys on a client-chosen view name, so it hides only queries whose names happen to match the policy's rule. Verbatim reproduction of the original leak. |
| SEC-034 | **HIGH** | `mayAdminister` defaults to `mayRead`: read access is destroy access. A row-filtered principal dropped a view whole; on `policy=authenticated` — the only closed configuration a server can be given — every token holder can drop every other's continuous query, state and all. No ownership test exists anywhere. |
| SEC-061 | **HIGH** | A server node can run only `PERMISSIVE` or `AuthenticatedOnlyPolicy`, neither of which denies an authenticated caller anything. Per-tenant rules and row filters are unreachable by configuration, on both surfaces. The ceiling on everything above. |
| SEC-057 | **HIGH** | Read-time authorization sees a client-chosen label, not the query's sources. A view derived from a restricted stream is readable by a principal denied that stream. The root cause SEC-043 and SEC-034's deny both inherit. |
| SEC-058 | **HIGH** | One request for a name that does not exist returns every view name on the node, to a principal the `LIST` filter hides them from — on `getFlightInfo`, `register`, `drop`, `pause`, `resume` and `subscribe`. |
| SEC-059 | **HIGH** | `tls.key` without `tls.certificate` starts a node that serves bearer tokens in cleartext, warning only what every plaintext node warns. Half a TLS pair is not an error. |
| SEC-028 / SEC-008 p2 | **HIGH** *(today: MEDIUM in practice)* | No HTTP controller reads the principal. Any valid token lists every stream schema, plans arbitrary SQL and registers streams. Bounded today because HTTP serves no rows and its writes never reach the engine (SEC-062) — and unbounded the moment SEC-061 is fixed. |
| SEC-063 | **MEDIUM** | The shipped CLI/SDK cannot supply a TLS trust anchor, so a private-CA node is unreachable by `bin/pravaha`; the pressure is to turn TLS back off. |
| SEC-062 | **MEDIUM** | `POST /api/v1/streams` returns 201 and changes nothing the engine uses; any token holder can inject phantom streams into the node's self-description. |
| SEC-021 | **MEDIUM** | The 401 body is `ApiError` **plus `status`**. Still two shapes; a default Jackson mapper still fails on it, on the response a misconfigured deployment returns most. Content-Type still ISO-8859-1. |
| SEC-060 | **LOW** | `tls.certificate` without `tls.key` refuses the start with a raw NPE instead of a `PRV-` message. |

### On whether the fixes address causes

Three do. TLS was a missing dependency and is now a working transport, proved with rows on the wire.
The `authorizedBy`/`hosting` ordering was a wiring bug and is fixed with a comment explaining why the
order is not stylistic. The `policy=authenticated` + `authentication=none` refusal is a genuine
guard, and I could not get round it in eleven tries.

Two do not. **`LIST` filtering by view name treats the symptom**: the original evidence was a payroll
query's SQL reaching a principal denied payroll, and that exact evidence still reproduces — the
filter hides `payroll_view` because the string matches and discloses `secret_pay`, which reads the
same stream, because it does not. Worse, it is a fix that *looks* right in the obvious test, which
makes it the kind that stops being re-examined. The same applies to `requireAdministrable`: the hook
is correct and the default it inherits answers a different question. Both are downstream of SEC-057,
which is unfixed, and neither can be made correct until `mayRead` is given the lineage the registry
already computes and discards.

**The 401 body was fixed by adding fields to a hand-built string** rather than by serialising the
record, so the extra `status` field — the specific thing the defect named — survived the fix.

### What this pass could not cover

* **A deployment with real rules on a real node.** SEC-061 makes it impossible: the rule-bearing
  cases still run against `QaPolicy` in the QA harness on the released jars. `AuthenticatedOnlyPolicy`
  is now testable on a real node and was.
* **Token rotation, expiry, revocation** — still no `TokenVerifier` in the repository that has them.
* **Concurrency around authorization** — a revocation during an open subscription, a `drop` racing a
  `register`. Still not covered, and `mayAdminister` has now added a second decision point on the
  same objects, so this is more worth a pass of its own than it was.
* **The Python SDK and the console as clients**, and **TLS against a publicly-trusted certificate**
  (SEC-063 means the shipped CLI can only be exercised that way).
