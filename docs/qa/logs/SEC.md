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
