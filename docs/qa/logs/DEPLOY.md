# DEPLOY — execution log

Executed 2026-09-12 on branch `develop`, against the artefacts already in the tree:

    pravaha-server/target/pravaha-server-0.1.0-SNAPSHOT-app.jar
    pravaha-cli/target/pravaha-cli-0.1.0-SNAPSHOT-cli.jar

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`. Ports 18300–18319 (HTTP) and 19300–19319 (Flight).
Scratch: `/tmp/claude-1000/.../scratchpad/qa-deploy`. Nothing was written into the repository except
this file and `docs/qa/cases/DEPLOY.md`; `git status --short` at the end of the run showed only
those two paths.

Paths in the transcripts below are abbreviated to `$SD` for the scratch directory where they would
otherwise be unreadable.

---

## Artefacts and launchers

### DEPLOY-001 — PASS
```
$ unzip -p pravaha-server/target/pravaha-server-0.1.0-SNAPSHOT-app.jar META-INF/MANIFEST.MF
Main-Class: org.springframework.boot.loader.launch.JarLauncher
Start-Class: com.ash.messaging.pravaha.server.PravahaServerApplication
Spring-Boot-Version: 3.5.16
Spring-Boot-Classes: BOOT-INF/classes/
Spring-Boot-Lib: BOOT-INF/lib/

$ unzip -l ...-app.jar 'BOOT-INF/lib/*' | tail -2
 73871162                     151 files
```
**Verdict:** A proper Spring Boot fat jar: launcher, application class, 151 bundled dependencies.

The jar's mtime is `2025-12-31 19:00` while the sources were last touched `2026-09-12`. That is the
reproducible-build `project.build.outputTimestamp`, not a stale artefact — I checked, rather than
assumed:
```
$ unzip -p ...-app.jar BOOT-INF/classes/.../PravahaNode.class > injar.class
$ cmp injar.class pravaha-server/target/classes/.../PravahaNode.class && echo IDENTICAL
IDENTICAL to target/classes
$ strings injar.class | grep -i checkpoint
checkpointingTo
)checkpointing registered queries under {}
```
The jar contains the current source, so every case below tests the code as it stands.

### DEPLOY-002 — PASS
```
$ java -jar pravaha-cli/target/pravaha-cli-0.1.0-SNAPSHOT-cli.jar version
pravaha 0.1.0-SNAPSHOT
exit=0
$ bin/pravaha version
pravaha 0.1.0-SNAPSHOT
exit=0
```
**Verdict:** The shaded CLI jar is complete and the launcher finds it in the development tree.

### DEPLOY-003 — PASS
```
$ cd $SD/isolated && ./pravaha version        # a copy of bin/pravaha with no lib/ or ../pravaha-cli
pravaha: cannot find the CLI jar.
  Build it with:  ./mvnw -pl pravaha-cli -am install -DskipTests
  Or point at it: PRAVAHA_CLI_JAR=/path/to/pravaha-cli-<version>-cli.jar
exit=2

$ cd $SD/isolated && ./pravaha-server
pravaha-server: cannot find the server jar.
  Build it with:  ./mvnw -pl pravaha-server -am install -DskipTests
  Or point at it: PRAVAHA_SERVER_JAR=/path/to/pravaha-server-<version>-app.jar
exit=2
```
**Verdict:** Exit 2, message on stderr, names both the build and the override. Correct.

### DEPLOY-004 — PASS
```
$ PRAVAHA_CLI_JAR=$SD/mycli.jar bin/pravaha version
pravaha 0.1.0-SNAPSHOT
exit=0
$ PRAVAHA_CLI_JAR=/nonexistent.jar bin/pravaha version
pravaha: cannot find the CLI jar. [...]
exit=2
```
**Verdict:** Honoured, and an override at a missing path is refused rather than silently falling
back. **Non-vacuity:** the first half could have passed by falling back to the tree jar, so I
pointed the override at a deliberately broken file — if it were ignored, the version would have
printed:
```
$ echo "not a jar" > $SD/broken.jar
$ PRAVAHA_CLI_JAR=$SD/broken.jar bin/pravaha version
Error: Invalid or corrupt jarfile $SD/broken.jar
exit=1
```
The named jar is the one that runs.

### DEPLOY-005 — PASS
```
$ PRAVAHA_SERVER_JAR=/nope.jar bin/pravaha-server
pravaha-server: cannot find the server jar. [...]
exit=2

$ PRAVAHA_SERVER_JAR=$SD/myserver.jar bin/pravaha-server --spring.profiles.active=dev \
    --server.port=18312 --pravaha.flight.port=19316
$ tr '\0' ' ' < /proc/<pid>/cmdline
/usr/lib/jvm/java-21-openjdk-amd64/bin/java --add-opens=... -jar $SD/myserver.jar \
  --spring.profiles.active=dev --server.port=18312 --pravaha.flight.port=19316
$ curl -s http://127.0.0.1:18312/actuator/health
{"status":"UP","groups":["liveness","readiness"]}
```
**Verdict:** The override reaches the JVM command line, and the server started from it serves. The
`/proc` read is the proof it was that jar and not the tree one.

### DEPLOY-006 — PASS
```
$ cd / && /home/ashutosh/IdeaProjects/pravaha/bin/pravaha version
pravaha 0.1.0-SNAPSHOT
exit=0
$ cd $SD && /home/ashutosh/IdeaProjects/pravaha/bin/pravaha version
pravaha 0.1.0-SNAPSHOT
exit=0
```
**Verdict:** Jar resolution is relative to the script, not to `$PWD`.

### DEPLOY-007 — FAIL (low)
```
$ ln -s /home/ashutosh/IdeaProjects/pravaha/bin/pravaha $SD/mybin/pravaha
$ cd / && PATH=$SD/mybin:$PATH pravaha version
pravaha: cannot find the CLI jar.
  Build it with:  ./mvnw -pl pravaha-cli -am install -DskipTests
  Or point at it: PRAVAHA_CLI_JAR=/path/to/pravaha-cli-<version>-cli.jar
exit=2
```
**Verdict:** Invoked through a symlink the launcher resolves `here` to the symlink's directory, not
the real script's, and finds nothing. Cause: `here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`
in both launchers does not resolve symlinks; the fix is one `readlink -f` (or a `while [ -L ]`
loop).

Severity **low**, and only low because the documented install is
`export PATH="$PWD/bin:$PATH"` (docs/QUICKSTART.md:42), which works, and the container image puts
`bin/` beside `lib/`, which also works. It is nevertheless the single most common way a CLI gets
onto a PATH (`ln -s ... /usr/local/bin/pravaha`), and the error message is actively misleading — it
tells the user to build a jar that is already built.

### DEPLOY-008 — PASS
```
$ env -u JAVA_HOME PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH bin/pravaha version
pravaha 0.1.0-SNAPSHOT
exit=0
$ env -u JAVA_HOME PATH=/usr/bin:/bin bin/pravaha version
pravaha 0.1.0-SNAPSHOT
exit=0
```
**Verdict:** `${JAVA_HOME:+...}` degrades correctly under `set -u`; no `unbound variable`.

### DEPLOY-009 — PASS
```
$ JAVA_HOME=/nonexistent bin/pravaha version
bin/pravaha: line 30: /nonexistent/bin/java: No such file or directory
exit=127
```
**Verdict:** Non-zero, and the message names the exact path. It is a bash message rather than a
Pravaha one, which is acceptable for this failure.

### DEPLOY-010 — PASS
```
$ PRAVAHA_JAVA_OPTS="-Xmx512m -Dpravaha.qa.marker=deploy" bin/pravaha version
pravaha 0.1.0-SNAPSHOT
exit=0
```
**Non-vacuity:** that alone proves nothing — the options might have been dropped. So I passed one
the JVM must reject:
```
$ PRAVAHA_JAVA_OPTS="-Xmx1m" bin/pravaha version
Error occurred during initialization of VM
Too small maximum heap
exit=1
```
**Verdict:** The variable reaches the JVM. Noted for the record: it is unquoted, so a single option
containing a space is word-split (`PRAVAHA_JAVA_OPTS="-Dfoo=a b"` → `Could not find or load main
class b`). That is the normal shell idiom for a `JAVA_OPTS` variable and is not raised as a defect.

---

## Container image

### DEPLOY-011 — PASS
```
$ docker build -t pravaha-qa-deploy:test .
#16 [build 8/8] RUN ./mvnw -B -q -DskipTests package
#16 DONE 83.7s
#21 naming to docker.io/library/pravaha-qa-deploy:test done
#21 DONE 7.3s

$ docker images pravaha-qa-deploy:test --format '{{.Size}}'
712MB
$ docker run --rm --entrypoint sh pravaha-qa-deploy:test -c 'ls -la /opt/pravaha/lib'
-rw-r--r-- 1 root root 48758145 pravaha-cli.jar
-rw-r--r-- 1 root root 74183714 pravaha-server.jar
```
**Verdict:** Builds clean from a cold cache in about 95 seconds end to end, of which 84 is the
in-container Maven build. Both jars land, and the server jar is byte-for-byte the size of the host
one (74183714), so the reproducible build reproduces.

### DEPLOY-011b — PASS (the image actually serves)
```
$ docker run -d --name ... -p 18314:8080 -p 19318:9090 pravaha-qa-deploy:test --spring.profiles.active=dev
$ docker inspect -f '{{.State.Health.Status}}' ...
healthy
$ curl -s http://127.0.0.1:18314/actuator/health
{"status":"UP","groups":["liveness","readiness"]}
$ bin/pravaha queries --url grpc://127.0.0.1:19318
no continuous queries are registered
$ docker exec ... sh -c 'cat /proc/1/cmdline | tr "\0" " "'
/opt/java/openjdk/bin/java --add-opens=... -jar /opt/pravaha/bin/../lib/pravaha-server.jar --spring.profiles.active=dev
$ time docker stop ...   # SIGTERM to PID 1
stop took .625546304s
exit code: 143
```
**Verdict:** The container serves both protocols to the host, the launcher `exec`s so java is PID 1
and receives SIGTERM directly (no 10-second `docker stop` timeout), and the declared HEALTHCHECK
reports healthy.

**Observation, not a case failure:** the Dockerfile's `VOLUME ["/var/lib/pravaha"]` is commented
"The registry journal belongs on a volume", but the image does not set
`pravaha.registry.journal`. The container's own log says so:
```
WARN ... pravaha.registry.journal is not set, so registered queries live only in memory and a
        restart will lose them without saying so
WARN ... pravaha.checkpoint.directory is not set, ...
```
So the shipped image is not durable out of the box; the volume is a place to put a journal that
nothing points at. That is defensible, but the comment reads as though mounting the volume is
sufficient, and it is not.

### DEPLOY-012 — PASS
```
$ grep 'transferring context' docker-build.log
#7 transferring context: 267.65MB 0.8s done
$ du -sh /home/ashutosh/IdeaProjects/pravaha ; du -sh .git console/.venv ; du -shc <all target dirs>
956M  (tree)   30M (.git)   301M (console/.venv)   357M (target dirs)
$ docker run --rm --entrypoint sh <image> -c 'ls -a /opt/pravaha; ls /src'
.  ..  bin  lib
ls: cannot access '/src': No such file or directory
```
**Verdict:** 956M − (30 + 301 + 357) = 268M, which is the context transferred to within rounding —
`.dockerignore` is doing exactly what it claims. The final image carries no `.git`, no build stage,
and no host-built `target/`.

### DEPLOY-013 — PASS
```
$ docker run --rm --entrypoint sh <image> -c 'command -v wget || echo WGET-MISSING; command -v curl || echo CURL-MISSING'
/usr/bin/wget
/usr/bin/curl
```
**Verdict:** `wget` is present in `eclipse-temurin:21-jre`, so the HEALTHCHECK can run. Confirmed
end to end by DEPLOY-011b reporting `healthy`, which is the non-vacuous proof: a missing `wget`
would have shown `unhealthy` after three retries.

### DEPLOY-014 — PASS
```
$ docker run --rm --entrypoint sh <image> -c 'id; touch /var/lib/pravaha/probe && echo WRITABLE; ls -ld /var/lib/pravaha'
uid=10001(pravaha) gid=999(pravaha) groups=999(pravaha)
WRITABLE
drwxr-xr-x 2 pravaha pravaha 4096 /var/lib/pravaha
```
**Verdict:** Non-root as designed, and the volume is writable by that uid.

### DEPLOY-015 — PASS
```
$ docker run --rm pravaha-qa-deploy:test        # no profile
exit=1
Caused by: com.ash.messaging.pravaha.api.PravahaException: PRV-7002  this node is configured to
accept unauthenticated callers and serve them every view (pravaha.security.authentication=none,
policy=permissive). [...] set pravaha.security.allow-anonymous=true to say so on purpose.
```
**Verdict:** The image fails closed with the guidance text. Note that the refusal happens in
`SmartLifecycle.start()`, after Tomcat has bound its port — so an operator sees the HTTP port open
for a fraction of a second before the context unwinds. Nothing is served through it (the failure is
before any view exists) and the port is released, so this is a remark rather than a defect.

---

## Process lifecycle

### DEPLOY-016 — PASS
```
$ bin/pravaha-server --spring.profiles.active=dev --server.port=18300 --pravaha.flight.port=19304
$ ss -ltnp | grep -E '18300|19304'
LISTEN 0 4096 *:19304 *:* users:(("java",pid=4114344,fd=64))
LISTEN 0 100  *:18300 *:* users:(("java",pid=4114344,fd=9))
$ curl -s .../actuator/health
{"status":"UP","groups":["liveness","readiness"]}
... INFO ... Tomcat started on port 18300 (http)
... INFO ... Flight SQL listening on 0.0.0.0:19304
```
**Verdict:** Both ports are independently configurable and both are bound by the one PID.

A note on how these servers had to be started. A server launched with plain `nohup ... &` was killed
at the end of the shell invocation that started it — the first attempt at this case died 7 seconds
after startup with `Commencing graceful shutdown` on the shutdown hook. Every server below is
started with `setsid nohup ... &` so it leaves the caller's process group. That is a property of
this QA harness, not of the product.

### DEPLOY-017 — PASS
```
$ kill -TERM 4129126
elapsed: .534179555s
exited
... INFO [ionShutdownHook] GracefulShutdown : Commencing graceful shutdown. Waiting for active requests to complete
... INFO [tomcat-shutdown] GracefulShutdown : Graceful shutdown complete
$ ss -ltn | grep -E '18304|19308'
(nothing) -- 18304/19308 free
```
**Verdict:** SIGTERM is handled, shutdown takes half a second, both ports are released.

**Observation:** `PravahaNode.stop()` closes the Flight server, the registry and the coordinator and
logs nothing at all while doing it — the only shutdown lines in the log are Tomcat's. There is no
way to tell from a log whether the ordered teardown the class documents actually ran, or whether one
of the three swallowed an exception into `closeQuietly`'s WARN that never fired. Worth one INFO
line each.

### DEPLOY-018 — PASS
```
$ kill -9 <pid>            # node had metq and metq2 registered, checkpoint interval 5s
killed
ports 18311/19315 released
$ ls $SD/j-leak/ ; find $SD/cp-leak -name '*.tmp' -o -name '*.compacting'
-rw------- 1 ashutosh ashutosh 7534 registry.log
(no .tmp or .compacting files)

$ bin/pravaha-server ... (same journal, same checkpoint dir)
... INFO ... registry recovered 2 of 2 queries from $SD/j-leak/registry.log
$ bin/pravaha queries --url grpc://127.0.0.1:19315
NAME   STATE    FINGERPRINT   ROWS IN
metq   RUNNING  c1a081494c59  8
metq2  RUNNING  f50c7f5c997c  8
```
**Verdict:** An abrupt kill leaves a startable node: no partial files to clean up by hand, the
journal replays, the ports are free. The `force(true)` on every journal append and the
rename-to-publish in the checkpoint store both do what they claim.

### DEPLOY-019 — PASS
```
$ bin/pravaha-server --spring.profiles.active=dev --server.port=18300 --pravaha.flight.port=19305
exit=1
APPLICATION FAILED TO START
Description:
Web server failed to start. Port 18300 was already in use.
Action:
Identify and stop the process that's listening on port 18300 or configure this application to listen on another port.
```
**Verdict:** Clean, named, actionable, non-zero.
**Non-vacuity:** an earlier attempt at this case passed for the wrong reason — the first server had
already been killed by the harness, so the "second" server simply started. The transcript above is
from a run where `ss` confirmed PID 4114344 holding 18300 at the moment of the attempt.

### DEPLOY-020 — PASS
```
$ bin/pravaha-server --spring.profiles.active=dev --server.port=18301 --pravaha.flight.port=19304
exit=1
... INFO ... Tomcat started on port 18301 (http) with context path '/'
... ERROR ... Application run failed
Caused by: com.ash.messaging.pravaha.api.PravahaException: PRV-3010  cannot start the Flight SQL
    server on 0.0.0.0:19304: Failed to bind to address /0.0.0.0:19304
Caused by: java.net.BindException: Address already in use
$ ss -ltn | grep 18301
18301 free
$ curl -s http://127.0.0.1:18300/actuator/health
{"status":"UP","groups":["liveness","readiness"]}
```
**Verdict:** A Flight bind failure is a named PRV error, the process exits non-zero, and — the part
that matters — the HTTP port it had already bound is released rather than left half-started. The
node already serving is unaffected.

### DEPLOY-021 — PASS
```
$ bin/pravaha-server --spring.profiles.active=dev --server.port=0 --pravaha.flight.port=0
... INFO ... Tomcat started on port 38287 (http) with context path '/'
... INFO ... Flight SQL listening on 0.0.0.0:46685
$ curl -s http://127.0.0.1:38287/actuator/health
{"status":"UP","groups":["liveness","readiness"]}
$ ss -ltn | grep :46685
LISTEN 0 4096 *:46685 *:*
```
**Verdict:** Both settings accept 0, both report the port actually obtained, and both are reachable.
`PravahaFlightServer.port()` returning the bound port rather than the configured one is what makes
the Flight half work.

### DEPLOY-022 — PASS, with a remark
```
$ bin/pravaha-server --spring.profiles.active=dev --server.port=80 --pravaha.flight.port=19306
exit=1
... Tomcat initialized with port 80 (http)
org.springframework.context.ApplicationContextException: Failed to start bean 'webServerStartStop'
   [ ~20 frames ]
Caused by: java.net.BindException: Permission denied

$ bin/pravaha-server --spring.profiles.active=dev --server.port=18302 --pravaha.flight.port=443
exit=1
Caused by: com.ash.messaging.pravaha.api.PravahaException: PRV-3010  cannot start the Flight SQL
    server on 0.0.0.0:443: Failed to bind to address /0.0.0.0:443
Caused by: java.net.BindException: Permission denied
```
**Verdict:** Both fail cleanly and non-zero; no crash, no hang, nothing left bound. The Flight side
names the port in a PRV error. The HTTP side does not get a Spring failure analyzer for
`Permission denied` the way it does for "already in use", so the operator gets twenty frames and has
to read to the bottom — worth an entry in the troubleshooting docs rather than a code change.

### DEPLOY-023 — PASS
```
$ bin/pravaha-server ... --spring.config.location=file:/nonexistent/application.yaml
exit=1
APPLICATION FAILED TO START
Config data resource 'file [/nonexistent/application.yaml]' via location
'file:/nonexistent/application.yaml' does not exist
```
Same for `--spring.config.additional-location`.
**Verdict:** A typo'd config path is a startup failure naming the path, not a silent start on
defaults.

### DEPLOY-024 — PASS, with a remark
```
$ cat $SD/run/bad.yaml
pravaha:
  security:
    allow-anonymous: true
   node:
      id: "broken
$ bin/pravaha-server ... --spring.config.additional-location=file:$SD/run/bad.yaml
exit=1
while parsing a block mapping
 in 'reader', line 2, column 3:
      security:
      ^
expected <block end>, but found '<block mapping start>'
 in 'reader', line 4, column 4:
       node:
```
**Verdict:** Fails at startup with line and column. The remark: the message says `in 'reader'` and
never names the file. An operator with three config files layered by
`spring.config.additional-location` is told a line number without being told which file it is in.
SnakeYAML behaviour, but Spring can supply the resource description and here it does not.

### DEPLOY-025 — PASS
```
$ bin/pravaha-server ... --pravaha.security.policy=strict
exit=1
PRV-7002  pravaha.security.policy is 'strict', which is not a policy this node knows. Use
'permissive' or 'authenticated', or implement SecurityPolicy for rules of your own.

$ bin/pravaha-server ... --pravaha.security.audit=syslog
exit=1
PRV-7002  pravaha.security.audit is 'syslog'; use 'none' or 'memory'.
```
**Verdict:** Both unknown values are startup failures carrying the guidance text, and the sentence
survives to where `grep PRV-` finds it.

### DEPLOY-026 — PASS
```
$ bin/pravaha-server --server.port=18303 --pravaha.flight.port=19307      # no dev profile
exit=1
PRV-7002  this node is configured to accept unauthenticated callers and serve them every view
(pravaha.security.authentication=none, policy=permissive). [...]
$ ss -ltn | grep -E '18303|19307'
both free
```
**Verdict:** The headline security fix holds for the jar as shipped: default configuration refuses
to start, nothing is left listening.

---

## Registry journal

### DEPLOY-027 — PASS
```
$ bin/pravaha register --name big --sql-file .../big.sql --keys 0 --url grpc://127.0.0.1:19308
registered big  state=RUNNING  fingerprint=d8a13cf760d4
$ bin/pravaha register --name oks ...
registered oks  state=RUNNING  fingerprint=b6af4dfc4770
$ kill -TERM <pid> ; bin/pravaha-server ... (same journal)
... INFO ... registry recovered 2 of 2 queries from $SD/journal/registry.log
$ bin/pravaha queries --url grpc://127.0.0.1:19308
NAME  STATE    FINGERPRINT   ROWS IN
big   RUNNING  d8a13cf760d4  5
oks   RUNNING  b6af4dfc4770  5
```
**Non-vacuity:** the same procedure with no journal configured must lose them, and does:
```
$ bin/pravaha register --name nojrn ... --url grpc://127.0.0.1:19317
registered nojrn  state=RUNNING  fingerprint=d8a13cf760d4
$ kill -TERM <pid> ; restart on the identical configuration minus the journal
$ bin/pravaha queries --url grpc://127.0.0.1:19317
no continuous queries are registered
```
**Verdict:** Registrations survive a restart, and they survive it *because of the journal* and not
because of anything else in the process.

### DEPLOY-028 — PASS
```
before restart: big, oks, doomed    (doomed then dropped)
$ bin/pravaha drop --name doomed --url grpc://127.0.0.1:19308
dropped doomed
after restart:  registry recovered 2 of 2 queries
NAME  STATE    FINGERPRINT   ROWS IN
big   RUNNING  d8a13cf760d4  5
oks   RUNNING  b6af4dfc4770  5
```
**Verdict:** A drop is journalled and replay honours it. `doomed` does not come back.

### DEPLOY-029 — PASS
```
$ umask
0002
$ ls -ld $SD/journal                      # before the server touched it
drwxrwxr-x 2 ashutosh ashutosh
$ stat -c '%a %U:%G %s %n' $SD/journal/registry.log $SD/journal
600 ashutosh:ashutosh 117 .../journal/registry.log
700 ashutosh:ashutosh  60 .../journal
```
**Verdict:** The file is owner-only and so is its directory. **Non-vacuity:** the process umask is
0002, which would have produced 664 and 775; I created the directory at 775 and the engine narrowed
it to 700. `SensitiveFiles` is doing real work, not agreeing with the environment.

One consequence to be aware of: `SensitiveFiles.createOwnerOnly` narrows the journal's **parent
directory** on every append. Pointing the journal at `/var/lib/pravaha/registry.log` will chmod
`/var/lib/pravaha` — the container's shared volume — to 700 on the first registration. That breaks
any sidecar or backup process reading checkpoints from the same volume under a different uid. Worth
documenting; the behaviour itself is the safe direction.

### DEPLOY-030 — PASS, with a defect worth recording
```
$ head -c 160 journal/registry.log > j-trunc/registry.log      # cuts record 2 mid-way
$ bin/pravaha-server ... (journal = j-trunc/registry.log)
... INFO ... registry recovered 1 of 1 queries from $SD/j-trunc/registry.log
$ bin/pravaha queries --url grpc://127.0.0.1:19309
NAME  STATE    FINGERPRINT   ROWS IN
big   RUNNING  d8a13cf760d4  5
```
**Verdict:** The case as written passes: the intact record recovers, the node does not throw, and it
starts. But the log line is **"recovered 1 of 1"** when two registrations had been written and one
was destroyed. `replay()` `break`s out of the loop on a short final record and `recover()` then
counts what it got, so a truncated journal is indistinguishable in the log from a clean one. An
operator restarting after a crash is told everything came back. The count should be against what the
file claims, and a discarded tail should be a WARN naming the byte offset. Low severity, real.

### DEPLOY-031 — PASS
```
$ printf 'XXXX' | dd of=j-corrupt/registry.log bs=1 seek=4 conv=notrunc   # break record 1's magic
$ bin/pravaha-server ... (journal = j-corrupt/registry.log)
exit=1
PRV-8005  record 1 of the registry journal at $SD/j-corrupt/registry.log cannot be decoded. Earlier
records are fine; this one is not, and replaying past it would silently drop whatever it said
$ ss -ltn | grep -E '18305|19309'
ports free
```
**Verdict:** A corrupt record in the body is refused, not skipped, and the error names the record
number. This is the behaviour the brief asked about and it is correct.

### DEPLOY-032 — FAIL (high)
```
$ (journal pointed at /journal-test-qa-deploy/registry.log, an unwritable location)
... INFO ... registry recovered 0 of 0 queries from /journal-test-qa-deploy/registry.log
... INFO ... Started PravahaServerApplication in 4.535 seconds

$ bin/pravaha register --name ro_test --sql-file $SD/sql/big.sql --keys 0 --url grpc://127.0.0.1:19310
PRV-1041  PRV-8006  cannot append to the registry journal at /journal-test-qa-deploy/registry.log.
The registration would be lost at the next restart, so it is refused now rather than acknowledged
and forgotten
exit=1

$ bin/pravaha queries --url grpc://127.0.0.1:19310
NAME     STATE    FINGERPRINT   ROWS IN
ro_test  RUNNING  d8a13cf760d4  5
```
**Verdict:** The registration is refused to the client **and left running on the server.** The
client has an error and no view it believes in; the node has a query consuming a lane, a feed thread
and a checkpointer, serving rows to anyone who asks for `ro_test`, and no journal record of it.

Cause: `QueryRegistry.register` (line ~325) does `start(...)`, `byName.put`, `byFingerprint.put`,
`views.register` and only *then* `journalRegistration(...)`, and the exception from the journal
propagates without unwinding any of it. The class's own javadoc says the ordering is deliberate so
that "a client never gets an acknowledgement for a registration that would vanish" — which it
achieves, at the cost of the opposite failure.

It gets worse on the retry an operator would actually perform:
```
$ bin/pravaha register --name ro_test ...          # same name
PRV-8001  'ro_test' is already registered. Drop it first, or register under another name
$ bin/pravaha register --name ro_test2 --sql-file $SD/sql/big.sql ...   # different name, same SQL
registered ro_test  state=RUNNING  fingerprint=d8a13cf760d4
exit=0
$ ls /journal-test-qa-deploy
ls: cannot access '/journal-test-qa-deploy': No such file or directory
```
The retry under a second name returns **success** with **nothing written to the journal at all** —
see DEPLOY-046, which is the same hole reached without any journal fault. (The CLI also prints
`registered ro_test` when the name registered was `ro_test2`; that is a CLI defect and belongs to
another area's report.)

**Severity high.** The failure mode is exactly the one the journal exists to prevent, reached by the
obvious operator action after the journal's own error message.

**Second, smaller point from the same case:** the node started happily with a journal path it cannot
write. Nothing is checked at startup; the first client to register discovers it. A one-line probe
write during `journalTo` would turn a runtime surprise into a startup failure.

### DEPLOY-033 — PASS
```
$ bin/pravaha-server --spring.profiles.active=dev --server.port=18300 --pravaha.flight.port=19300
WARN ... PravahaNode : pravaha.registry.journal is not set, so registered queries live only in
memory and a restart will lose them without saying so
WARN ... PravahaNode : pravaha.checkpoint.directory is not set, so registered queries keep no
checkpoints: a restart recovers their definitions from the journal and none of their accumulated state
```
**Verdict:** Both warnings are emitted at startup as the configuration file promises.

---

## Checkpoints

### DEPLOY-034 — PASS, with a finding about content
```
$ (checkpoint.directory=$SD/cp/root, interval=2s, keep=2; cpq1 and cpq2 registered)
$ find $SD/cp -type f -printf '%M %s %p\n'
-rw------- 60 $SD/cp/root/cpq1/checkpoint-14.bin
-rw------- 60 $SD/cp/root/cpq1/checkpoint-15.bin
-rw------- 60 $SD/cp/root/cpq2/checkpoint-13.bin
-rw------- 60 $SD/cp/root/cpq2/checkpoint-14.bin
```
**Verdict:** One directory per query, files appear on the configured interval, owner-only. The
mechanism runs.

**But every checkpoint is 60 bytes, and here is what is in one:**
```
$ xxd $SD/cp/root/cpq1/checkpoint-39.bin
00000000: 5052 5643 0000 0001 0000 0000 0000 0027  PRVC...........'
00000010: 0000 f9bb bcc3 33b6 0000 0001 000b 7061  ......3.......pa
00000020: 7274 6974 696f 6e2d 3000 0135 0000 0000  rtition-0..5....
00000030: 0000 0001 0000 0000 5052 5643            ........PRVC
```
Decoded against `FileCheckpointStore.store`: magic, version 1, id 39, timestamp, `offsets.size()=1`
→ `{"partition-0": "5"}`, then **`operatorState.size() = 0`**, then the trailer. The checkpoint
records a source offset and **no operator state whatsoever** — for a query whose view held four
rows at the time. The file size never varied from 60 bytes across every checkpoint of every query
in this run, including the aggregating one. See DEPLOY-037.

### DEPLOY-035 — PASS
```
$ (keep=2, interval=2s, running for several minutes)
$ find $SD/cp -type f | sort
.../cpq1/checkpoint-35.bin   .../cpq1/checkpoint-36.bin
.../cpq2/checkpoint-34.bin   .../cpq2/checkpoint-35.bin
.../.._escape/checkpoint-34.bin  .../.._escape/checkpoint-35.bin
.../.._.._pwned/checkpoint-25.bin .../.._.._pwned/checkpoint-26.bin
```
**Verdict:** Exactly two per directory at every observation, and always the two highest-numbered.
Pruning keeps `keep` and keeps the newest. **Non-vacuity:** ids ran from 13 to 36 over the run, so
34 checkpoints were taken and 32 deleted — the count of 2 is the result of pruning working, not of
nothing having happened.

### DEPLOY-035b — PASS, with a remark
```
$ (pravaha.checkpoint.keep=0)
... INFO ... Started PravahaServerApplication in 4.206 seconds
$ bin/pravaha register --name k0 --sql-file .../big.sql --keys 0 --url grpc://127.0.0.1:19313
PRV-1041  at least one checkpoint must be kept, asked to keep 0. Keeping none means every restart
starts from nothing
exit=1
$ bin/pravaha queries --url grpc://127.0.0.1:19313
no continuous queries are registered
```
**Verdict:** `keep=0` is refused and never silently keeps none, and unlike DEPLOY-032 this failure
leaves nothing behind in the registry. The remark: it is refused at **registration**, not at
startup. A node misconfigured this way starts, reports healthy, and fails every registration it
receives. `keep` should be validated where it is read.

### DEPLOY-036 — FAIL (medium)
```
$ bin/pravaha register --name '..' --sql-file $SD/sql/esc4.sql --keys 0 --url grpc://127.0.0.1:19312
registered ..  state=RUNNING  fingerprint=c00d7d1d2a9b
$ bin/pravaha register --name '.'  --sql-file $SD/sql/esc5.sql ...
registered .   state=RUNNING  fingerprint=f00406de1ec7

$ ls -la $SD/cp/                      # the PARENT of pravaha.checkpoint.directory
drwx------  3 ashutosh ashutosh 100 .
-rw-------  1 ashutosh ashutosh  60 checkpoint-3.bin      <-- written by the query named ".."
-rw-------  1 ashutosh ashutosh  60 checkpoint-4.bin
drwx------  6 ashutosh ashutosh 160 root
$ ls $SD/cp/root/
checkpoint-11.bin  checkpoint-12.bin       <-- written by the query named "."
cpq1/  cpq2/  .._escape/  .._.._pwned/
```
**Verdict:** A query named `..` writes its checkpoints **outside the configured checkpoint
directory**, one level up. A query named `.` writes them into the root itself, mixed in with the
per-query subdirectories and colliding with any other query named `.`.

Cause: `QueryRegistry.startCheckpointing` sanitises with
`name.replaceAll("[^A-Za-z0-9_.-]", "_")`, which **keeps `.`** — so `..` survives intact and
`checkpointRoot.resolve("..")` is the parent. The comment beside it says "requireName already
refuses the obvious"; `requireName` (line 631) checks only for null, blank and duplicate, so it
refuses nothing of the sort. Longer traversals are contained (`../escape` → `.._escape`,
`../../pwned` → `.._.._pwned`), so the escape is exactly one level — but it is one level of
client-controlled write outside the directory an operator configured, and `SensitiveFiles` then
chmods that parent to 700 as a side effect. With `checkpoint.directory=/var/lib/pravaha/checkpoints`
a client can write into `/var/lib/pravaha` and change its mode.

**Severity medium**: bounded, requires a registered client, but it is a path-traversal write and the
code comment asserting it cannot happen is false. Fix is to reject `.`, `..` and empty after
sanitisation, or to use the query fingerprint as the directory name.

### DEPLOY-037 — FAIL (high) — checkpoints are written and never read
Before the restart, with rows fed from the bound file source:
```
$ curl .../actuator/metrics/pravaha.query.view.size?tag=query:cpq1   -> "value":4.0
$ bin/pravaha query --sql "SELECT * FROM cpq1" --url grpc://127.0.0.1:19312
txn_id  user_id  amount
1       alice    150
3       alice    250
4       carol    400
5       bob      120
4 rows
```
I then emptied the source file so that nothing could re-arrive and mask the answer, and restarted on
the same journal and the same checkpoint directory:
```
$ : > $SD/data/txn.csv
$ find $SD/cp/root/cpq1 -type f
checkpoint-65.bin  checkpoint-66.bin
$ bin/pravaha-server ... (same config)
... INFO ... checkpointing registered queries under $SD/cp/root
... INFO ... registry recovered 6 of 6 queries from $SD/j-cp/registry.log
$ bin/pravaha query --sql "SELECT * FROM cpq1" --url grpc://127.0.0.1:19312
0 rows
$ curl .../actuator/metrics/pravaha.query.view.size?tag=query:cpq1   -> "value":0.0
$ curl .../actuator/metrics/pravaha.query.rows.in?tag=query:cpq1     -> "value":0.0
$ grep -ci restor $SD/run/i37.log
0
$ ls $SD/cp/root/cpq1/          # ids resumed above the stored ones
checkpoint-81.bin  checkpoint-82.bin
```
**Verdict:** Nothing is restored. The definitions come back from the journal; the state does not.

Three independent lines of evidence, because this contradicts a claim the release makes in
`application.yaml` ("What a query had accumulated, so a restart recovers answers and not only
questions"):

1. **Empirical.** View size 4 before, 0 after, with `rows_in = 0` proving the emptiness is not
   masked by re-ingestion and is not merely "the source refilled it differently".
2. **Static.** `CheckpointStore.latest()` and `QueryExecution.restore(...)` are called from nowhere
   in the shipped code. `grep -rn "latest()\|\.restore(" --include=*.java` outside `src/test` and
   `target` returns only the declarations themselves and one unrelated
   `PartitionHandoff.target.restore(snapshot)`. There is no code path from startup to a checkpoint.
3. **The checkpoints are empty anyway.** See DEPLOY-034: `operatorState.size() == 0` in every file.
   Even if a restore path existed it would restore a source offset and nothing else.

The checkpoint id resuming at 81 proves the store directory *is* read at startup — but only by
`PeriodicCheckpointer`'s constructor calling `availableIds()` to keep numbering monotonic. The files
are found, counted, and never opened.

**Severity high**, because it is a documented capability of the release that does not exist. The
journal half of the durability story works; the checkpoint half writes files nothing consumes.

### DEPLOY-038 — PASS
```
$ find $SD/cp -printf '%M %p\n' | sort
drwx------ $SD/cp
drwx------ $SD/cp/root
drwx------ $SD/cp/root/cpq1
drwx------ $SD/cp/root/cpq2
-rw------- $SD/cp/root/cpq1/checkpoint-35.bin
-rw------- ... (every file)
```
**Verdict:** Files 600, directories 700, under a 0002 umask. Same non-vacuity argument as
DEPLOY-029.

### DEPLOY-039 — FAIL (medium)
I replaced a query's checkpoint directory with a regular file so every subsequent checkpoint would
fail, and watched the log for 15 seconds at a 2-second interval (seven or eight attempts):
```
$ rm -rf $SD/cp/root/cpq1 && echo "not a directory" > $SD/cp/root/cpq1
$ (15 seconds later, new log lines since the change:)
WARN [ha-checkpointer] c.a.m.pravaha.common.io.SensitiveFiles : could not create
  $SD/cp/root/cpq1/checkpoint-93.tmp with owner-only permissions
  (java.nio.file.FileAlreadyExistsException: $SD/cp/root/cpq1). It holds data rather than
  configuration, so check its mode by hand.
  [x8, one per attempt]
$ grep -c "checkpoint failed" $SD/run/i37.log
0
$ bin/pravaha queries --url grpc://127.0.0.1:19312
cpq1  RUNNING  d8a13cf760d4  0
```
**Verdict:** `PeriodicCheckpointer.checkpointQuietly` catches the failure and reports it through its
`log` consumer — and `QueryRegistry.startCheckpointing` constructs it with `message -> {}`
(QueryRegistry.java:394). Its own comment says "Reported every time, not once: a query that has
silently not checkpointed for six hours looks exactly like one that has", and that is precisely what
happens: `checkpoint failed` appears zero times. Nor is it visible in metrics — `PravahaMetrics`
publishes nothing from `PeriodicCheckpointer.stats()`, which already exposes taken/failed/pruned.

The only reason anything appeared in the log at all is an incidental WARN from `SensitiveFiles`
about file permissions, which does not say checkpointing is failing and would not appear for other
failure modes (a full disk, for instance). The query stays `RUNNING`.

**Severity medium.** Given DEPLOY-037 the practical impact today is low — the checkpoints are not
read back anyway — but the moment restore is implemented this becomes a silent loss of the
fallback. Two lines: pass a real logger, and publish `stats()` as meters.

---

## Health, readiness and metrics

### DEPLOY-040 — PASS
```
$ curl -s -w ' [%{http_code}]' .../actuator/health
{"status":"UP","groups":["liveness","readiness"]} [200]
$ curl -s -w ' [%{http_code}]' .../actuator/health/liveness
{"status":"UP"} [200]
$ curl -s -w ' [%{http_code}]' .../actuator/health/readiness
{"status":"UP"} [200]
```
**Verdict:** All three exist and answer 200, which is what the Dockerfile's HEALTHCHECK needs. What
they *mean* is DEPLOY-041.

### DEPLOY-041 — FAIL (high)
I started a node with the Flight endpoint disabled — a process that is alive and cannot serve a
single client — and asked it how it was:
```
$ bin/pravaha-server --spring.profiles.active=dev --server.port=18310 --pravaha.flight.port=19314 \
    --pravaha.flight.enabled=false --management.endpoint.health.show-details=always
... INFO ... Flight SQL disabled (pravaha.flight.enabled=false); this node serves HTTP only

$ curl -s .../actuator/health
{
    "status": "UP",
    "groups": ["liveness", "readiness"],
    "components": {
        "diskSpace":      {"status": "UP", ...},
        "livenessState":  {"status": "UP"},
        "ping":           {"status": "UP"},
        "readinessState": {"status": "UP"},
        "ssl":            {"status": "UP", "details": {"validChains": [], "invalidChains": []}}
    }
}
$ ss -ltn | grep 19314
(nothing)
$ bin/pravaha queries --url grpc://127.0.0.1:19314
PRV-1041  io exception
```
**Verdict:** `UP`, `UP`, `UP` — liveness, readiness and overall — for a node no client can reach.
Every component listed is a Spring Boot default: disk, ping, ssl, and the two availability states
that Spring flips on context refresh. **There is not one Pravaha contributor.**
`grep -rn "HealthIndicator" --include=*.java` over the whole repository returns nothing, so this is
structural, not a configuration accident: health cannot report on the Flight listener, the registry,
the coordinator, the journal's writability or the feeds, because nothing has been written to let it.

The consequence for the container story is direct: the Dockerfile's HEALTHCHECK and the
comment above it ("Readiness is deliberately separate: conflating them makes an orchestrator restart
a node that is merely still restoring state") describe a distinction the endpoints do not make. Both
probes are "the JVM is up and Tomcat answers".

**Severity high**, on the brief's own standard: a health check that is always UP is worse than none,
because an orchestrator will keep routing to a node that cannot serve. The fix is small — a
`HealthIndicator` that reports DOWN when `PravahaNode.isRunning()` is false or `flightPort()` is
empty while Flight is enabled, and a readiness state that is not set until `PravahaNode.start()`
completes.

### DEPLOY-042 — FAIL (medium-high) for Prometheus; the meters themselves are correct
```
$ curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18308/actuator/prometheus
404
$ curl -s http://127.0.0.1:18308/actuator | python3 -m json.tool
_links: self, health, health-path, info, metrics-requiredMetricName, metrics
$ unzip -l ...-app.jar | grep -i micrometer
micrometer-commons  micrometer-core  micrometer-jakarta9  micrometer-observation
$ grep -n "micrometer\|prometheus" pravaha-server/pom.xml
(nothing -- only spring-boot-starter-actuator)
```
**Verdict:** `/actuator/prometheus` does not exist. `application.yaml` lists `prometheus` in
`management.endpoints.web.exposure.include`, and exposing an endpoint that was never registered is
silent — Spring Boot does not complain about a name it does not recognise. The cause is a missing
`micrometer-registry-prometheus` dependency in `pravaha-server/pom.xml`. Any deployment that
followed the configuration file and pointed a scraper at this node would get 404s.

The meters themselves are fine, through `/actuator/metrics`:
```
baseline, no queries registered:
$ curl .../actuator/metrics/pravaha.query.view.size
(empty -- no such meter)

$ bin/pravaha register --name metq --sql-file ... --url grpc://127.0.0.1:19315
registered metq  state=RUNNING  fingerprint=c1a081494c59
(immediately afterwards: still empty -- the sync is on a 15s timer)
(18 seconds later:)
pravaha.query.rows.in               "value":5.0
pravaha.query.view.size             "value":4.0
pravaha.query.running               "value":1.0
pravaha.query.watermark.lag.seconds "value":"NaN"

$ (source file grown to 8 rows) bin/pravaha register --name metq2 ...
metq2 rows.in  "value":8.0
metq  rows.in  "value":5.0
```
**Non-vacuity:** the baseline scrape contains no `pravaha.query.*` meter at all, so their appearance
is caused by the registration; and the two queries report different values on the same node, so the
gauges read per-query state rather than a constant.

Two smaller observations:
- `pravaha.query.watermark.lag.seconds` is `NaN` for a query that has received five rows.
  `PravahaMetrics.lagSeconds` returns NaN when `query.watermarkNanos()` is empty, and the comment
  says that means "nothing has arrived" — but rows had arrived. Either the watermark is genuinely
  not advancing for a file source (an ingest concern) or the metric cannot see it. Either way the
  one metric that answers "is it keeping up" reads NaN on a working query.
- I could not observe `rows_in` *increasing* on an already-registered query, because appending to
  the bound CSV produced no new rows — the filesystem source reads the file once and does not tail
  it. That is an ingest-area behaviour, not a metrics one; I proved the gauge is live by registering
  a second query after the append and getting 8.

### DEPLOY-043 — PASS
```
$ (22 register/drop cycles of distinct names cyc1..cyc22, then 20s for the sync)
$ bin/pravaha queries --url grpc://127.0.0.1:19315
no continuous queries are registered
$ curl -s .../actuator/metrics/pravaha.query.rows.in
(empty -- the meter no longer exists)
```
**Verdict:** Meters are removed with their queries; no `pravaha_query_*` series survives a drop.
**Non-vacuity:** the same endpoint returned a populated document with an `availableTags` list of
seven query names earlier in the run, so an empty response here is removal and not a broken
endpoint.

---

## Resource behaviour

### DEPLOY-044 — PASS
```
baseline (node started, nothing registered):
$ jcmd <pid> Thread.print | grep -c '^"'
54

after 22 register/drop cycles:
77
$ diff <(baseline histogram) <(after histogram)
>  1 "flight-server-default-executor-#"
>  4 "ForkJoinPool-#-worker-#"
> 24 "grpc-nio-worker-ELG-#-#"
<  9 "C# CompilerThread#"  ->  3   (JIT threads retired)

after 50 cycles total: 72
```
The +24 `grpc-nio-worker-ELG` threads are gRPC's event-loop group filling to its bound as CLI
connections arrive; `nproc` on this machine is 24 and the count stopped there and then fell back to
72 over the next 28 cycles. Not per-query.

**Non-vacuity** — the important half, because a test that counts threads while nothing creates any
proves nothing:
```
$ (register nv91, nv92, nv93)
      3 "pravaha-checkpointer"
      3 "pravaha-feed-nv#"
      3 "pravaha-query-#"
      1 "pravaha-metrics"
total threads with 3 queries: 85
$ (drop all three)
      1 "pravaha-metrics"
total threads after drop: 75
```
**Verdict:** Each registration creates three named threads and a drop reclaims all three. Fifty
register/drop cycles leave none behind. No thread leak.

### DEPLOY-045 — PASS
```
baseline, after 2x GC.run:
 garbage-first heap total 81920K, used 23749K
 Metaspace used 50046K, committed 50816K

after 22 cycles, after 2x GC.run:
 garbage-first heap total 114688K, used 30193K
 Metaspace used 78178K, committed 79104K

after 50 cycles, after 2x GC.run:
 garbage-first heap total 114688K, used 30230K
 Metaspace used 78578K, committed 79488K
$ jcmd <pid> VM.classloader_stats | tail -3
Total = 6 classloaders, 16463 classes
```
**Verdict:** No leak. The interesting number is metaspace: +28MB over the first 22 cycles looks
alarming and is one-off class loading of the SQL/planner/codegen stack — over the next 28 cycles it
moved 400KB, and there are six classloaders in total, so generated query classes are not
accumulating loaders. Live heap after a full GC is flat at ~30MB from cycle 22 to cycle 50. The
gauge-holds-a-dropped-query failure `PravahaMetrics` documents does not occur.

---

## Added during execution

### DEPLOY-046 — FAIL (high) — a shared computation's second name is never journalled
Found while investigating DEPLOY-032. This needs no journal fault at all; the journal below is
healthy and writable.
```
$ bin/pravaha register --name alpha --sql-file $SD/sql/big.sql --keys 0 --url grpc://127.0.0.1:19311
registered alpha  state=RUNNING  fingerprint=d8a13cf760d4
$ bin/pravaha register --name beta  --sql-file $SD/sql/big.sql --keys 0 --url grpc://127.0.0.1:19311
registered alpha  state=RUNNING  fingerprint=d8a13cf760d4         <-- exit 0
$ bin/pravaha register --name gamma --sql-file $SD/sql/oks.sql --keys 0 --url grpc://127.0.0.1:19311
registered gamma  state=RUNNING  fingerprint=b6af4dfc4770

$ bin/pravaha queries --url grpc://127.0.0.1:19311
NAME   STATE    FINGERPRINT   ROWS IN
alpha  RUNNING  d8a13cf760d4  5
beta   RUNNING  d8a13cf760d4  5
gamma  RUNNING  b6af4dfc4770  5

$ strings $SD/j-share/registry.log | grep -E '^(alpha|beta|gamma)$|SELECT'
alpha
:SELECT txn_id, user_id, amount FROM txn WHERE amount > 100
gamma
;SELECT txn_id, user_id, status FROM txn WHERE status = 'ok'
                                            <-- beta is not in the journal

$ kill -TERM <pid> ; bin/pravaha-server ... (same journal)
... INFO ... registry recovered 2 of 2 queries from $SD/j-share/registry.log
$ bin/pravaha queries --url grpc://127.0.0.1:19311
NAME   STATE    FINGERPRINT   ROWS IN
alpha  RUNNING  d8a13cf760d4  5
gamma  RUNNING  b6af4dfc4770  5
```
**Verdict:** `beta` was acknowledged with exit 0 against a healthy journal and is gone after the
restart, with no error, no warning, and a log line reading "recovered 2 of 2" — the node does not
know it lost anything. This is the "acknowledged and forgotten" failure the journal's javadoc says
it prevents, and any client that registers a view whose SQL matches one already running hits it.

Cause, `QueryRegistry.register`:
```java
RegisteredQuery existing = byFingerprint.get(fingerprint);
if (existing != null && !existing.state().isTerminal()) {
    existing.addName(name);
    byName.put(name, existing);
    return existing;          // returns before journalRegistration(...)
}
```
The early return for a shared computation skips the journal append entirely. Note that
`recordDrop` *is* called for such a name on drop, so the journal can also contain a drop for a name
it never recorded a registration for.

**Severity high.** Sharing identical queries is a headline feature ("a query with the same
fingerprint is the same computation, shared", printed by the CLI on every registration), so this is
not an edge case — it is the feature and the durability guarantee contradicting each other. It is a
one-line fix: move the journal append above the early return.

### DEPLOY-047 — FAIL (low) — the journal grows for ever
```
$ (50 register/drop cycles + 2 surviving queries)
$ ls -la $SD/j-leak/registry.log
-rw------- 1 ashutosh ashutosh 7534 registry.log
$ grep -rn "\.compact(" --include=*.java . | grep -v /target/
pravaha-registry/src/test/java/.../RegistryJournalTest.java:352:  writer.compact(writer.replay());
```
**Verdict:** `RegistryJournal.compact` is written, tested, and called from nowhere but its own test —
the same "built and never wired" pattern the release notes describe elsewhere. Every registration
and every drop appends for the life of the deployment, and every restart reads all of it. 7.5KB for
52 short-lived queries is nothing; a node cycling queries for a year is a large file replayed at
every start. There is also no operator command to compact it.

**Severity low** — slow, bounded by disk, no correctness impact — but it is an unbounded growth with
a finished remedy sitting unused.

### DEPLOY-048 — FAIL (low) — dropped queries leave their checkpoint directories behind
```
$ (same 50 cycles, checkpoint.directory configured)
$ ls $SD/cp-leak | head -8
cyc1  cyc10  cyc100  cyc101  cyc102  cyc103  cyc104  cyc105
$ ls $SD/cp-leak | wc -l
52          # for 2 live queries
```
**Verdict:** `FileCheckpointStore`'s constructor creates the directory at registration and nothing
removes it at drop. In this run they were empty, because each cycle was shorter than the 5s
checkpoint interval — a query that lived long enough leaves its checkpoint files, which hold
serialised operator state, in a directory belonging to a query that no longer exists. That is both
unbounded directory growth and customer data outliving the query that produced it, with no retention
rule and nothing that says so.

**Severity low** today for the same reason as DEPLOY-039 (the files contain no operator state), but
it is the wrong default and it is data retention, not tidiness.

---

## Summary

48 cases were written and all 48 were executed. Two of them (DEPLOY-011 and DEPLOY-035) were run as
two distinct halves each and are logged as separate entries, so the table counts 50 verdicts.

| Verdict | Count |
|---|---|
| PASS | 40 |
| FAIL | 10 |
| BLOCKED | 0 |
| NOT RUN | 0 |
| **Total entries** | **50** |

PASS: 001 002 003 004 005 006 008 009 010 011 011b 012 013 014 015 016 017 018 019 020 021 022 023
024 025 026 027 028 029 030 031 033 034 035 035b 038 040 043 044 045
FAIL: 007 032 036 037 039 041 042 046 047 048

Three of the PASSes carry a defect recorded in their own entry without failing the case as written:
DEPLOY-030 (a truncated journal is reported as "recovered 1 of 1", indistinguishable from a clean
one), DEPLOY-035b (`keep=0` is refused at registration rather than at startup, so the node comes up
healthy and fails everything it is asked to do), and DEPLOY-011b (the image declares a volume for a
journal it never configures).

FAILs in priority order:

| Case | Severity | One line |
|---|---|---|
| DEPLOY-037 | High | Checkpoints are written and never read; nothing calls `latest()`/`restore()`, and the files contain zero operator state. A restart recovers questions, not answers — contradicting `application.yaml`. |
| DEPLOY-041 | High | No `HealthIndicator` exists anywhere. Health, liveness and readiness all report UP on a node with no Flight listener that no client can reach. |
| DEPLOY-046 | High | A second name for an already-running computation is never journalled: acknowledged with exit 0, silently absent after restart, and the node logs "recovered 2 of 2". |
| DEPLOY-032 | High | A journal append failure refuses the registration to the client but leaves the query running and serving; retrying under a different name then succeeds with nothing journalled. |
| DEPLOY-042 | Med-high | `/actuator/prometheus` is 404 — `micrometer-registry-prometheus` is not a dependency, though `application.yaml` exposes the endpoint. The meters themselves are correct via `/actuator/metrics`. |
| DEPLOY-036 | Medium | A query named `..` writes checkpoints one directory above the configured root and chmods it 700; the sanitiser keeps `.` and the comment claiming `requireName` refuses this is false. |
| DEPLOY-039 | Medium | Checkpoint failures are invisible: the registry builds `PeriodicCheckpointer` with `message -> {}`, and `stats()` is not published as metrics. |
| DEPLOY-007 | Low | Both launchers break when invoked through a symlink (`BASH_SOURCE` is not resolved), with a message telling the user to build a jar that already exists. |
| DEPLOY-047 | Low | The registry journal is never compacted; `compact()` is called only from a test. |
| DEPLOY-048 | Low | Dropped queries leave their checkpoint directories, and any state in them, behind for ever. |

## What I could not cover, and why

**TLS on the Flight port.** `pravaha.flight.tls.certificate/key` is the difference between a bearer
token that is a control and one that is a formality, and the node warns about plaintext when
authentication is on. Generating a certificate and proving the handshake is a security-area case and
I did not want two agents testing the same surface; I confirmed only that the plaintext warning
path exists in the code.

**Clustered lifecycle.** `pravaha.cluster.mechanism=socket` and `zookeeper`, and the `PRV-9002`
refusal of `PARTITIONED` on a mechanism without consensus, need two or more nodes and a ZooKeeper.
Everything here ran `SINGLE`/`single`. A node that recovers correctly alone may not recover
correctly while a peer believes it owns the same partitions.

**Power loss, as opposed to SIGKILL.** SIGKILL proves the process cannot clean up; it does not prove
the data reached the platter. `channel.force(true)` and `SensitiveFiles.syncDirectory` are the right
calls and I read them, but only a real power cut or a fault-injecting filesystem tests them.

**Journal compaction under concurrency.** `compact()` is never called by the shipped node
(DEPLOY-047), so the atomic-rename path and its `.compacting` cleanup are unreachable from the
outside. I could not exercise them without writing a harness that calls the class directly, which is
a unit test rather than a deployment test.

**Restore behaviour when restore exists.** DEPLOY-037 shows there is no restore path, so every
downstream question — a corrupt newest checkpoint falling back to the previous one, a format-version
mismatch being refused, the interaction between a restored offset and a re-read source — is
untestable through the product. `FileCheckpointStore.load` implements all of it and nothing calls
it.

**A note on the environment.** Another agent's process (`prama`, serving `/tmp/qa-api/app.yaml`) was
listening on `127.0.0.1:19300`, inside my assigned Flight range, for part of this run. I moved to
19304+ rather than contend for it. Nothing in my results depends on 19300 and no case was affected,
but the port assignment was not in fact exclusive.

Every server started during this run was stopped by PID. At the end: `ss -ltn` showed none of
18300–18319 or 19300–19319 bound by me, every recorded PID was gone, the test container was stopped
and removed, and `git status --short` showed only `docs/qa/cases/` and `docs/qa/logs/`.
