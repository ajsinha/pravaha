# DEPLOY — deployment, lifecycle and recovery

Surface under test: the built artefacts, the two launchers, the Dockerfile, process lifecycle
(start / stop / crash / restart), the registry journal, checkpoints, port binding, the health and
metrics endpoints, and resource behaviour under repeated registration.

Ports reserved for this area: HTTP **18300–18319**, Flight **19300–19319**.
Scratch directory: `/tmp/.../scratchpad/qa-deploy` (outside the repository tree).

Environment assumed by every case unless it says otherwise:

    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
    cd /home/ashutosh/IdeaProjects/pravaha

---

## Artefacts and launchers

## DEPLOY-001 — the server artefact is a self-contained runnable jar
**Intent:** The release claims the build now produces something installable. A `-app.jar` that is
not executable standalone, or that has no `Main-Class`, means every downstream instruction in the
documentation is false.
**Setup:** `pravaha-server/target/pravaha-server-0.1.0-SNAPSHOT-app.jar` exists.
**Steps:** Read `META-INF/MANIFEST.MF` from the jar; check `Main-Class` and `Start-Class`; check
`BOOT-INF/lib` is populated; run `java -jar <app.jar> --help`-equivalent (start it and stop it).
**Expected:** Manifest names a Spring Boot launcher as `Main-Class` and the application class as
`Start-Class`; the jar contains its dependencies; `java -jar` on it starts a JVM that gets as far as
Spring Boot banner and startup without a `no main manifest attribute` error.

## DEPLOY-002 — the CLI artefact runs standalone and reports a version
**Intent:** `pravaha version` is the cheapest possible proof that the shaded CLI jar is complete.
**Setup:** `pravaha-cli/target/pravaha-cli-0.1.0-SNAPSHOT-cli.jar` exists.
**Steps:** `java -jar <cli.jar> version`, then `bin/pravaha version`.
**Expected:** Both print the same version string, exit 0, no stack trace, no
`NoClassDefFoundError`.

## DEPLOY-003 — `bin/pravaha` with no jar anywhere fails with a usable message
**Intent:** The commonest first-run failure. It must say what to do, not `null`.
**Setup:** Copy `bin/pravaha` to a scratch directory with no sibling `lib/` or `../pravaha-cli/target`.
**Steps:** Run the copy with `PRAVAHA_CLI_JAR` unset.
**Expected:** Exit status 2; stderr names the build command and the `PRAVAHA_CLI_JAR` override;
nothing on stdout.

## DEPLOY-004 — `PRAVAHA_CLI_JAR` override, valid and invalid
**Intent:** The documented escape hatch must actually be honoured, and must not silently fall back
to a jar in the tree when the operator pointed at one that is not there — falling back would run a
different build than the one asked for.
**Setup:** A copy of the CLI jar at a scratch path.
**Steps:** (a) `PRAVAHA_CLI_JAR=<scratch copy> bin/pravaha version`. (b)
`PRAVAHA_CLI_JAR=/nonexistent.jar bin/pravaha version`.
**Expected:** (a) exit 0, version printed, and the scratch copy is provably the jar used.
(b) exit 2 with the "cannot find" message — **not** a silent fallback to the tree jar.

## DEPLOY-005 — `PRAVAHA_SERVER_JAR` override, valid and invalid
**Intent:** Same contract on the server launcher; an installed layout depends on it.
**Steps:** (a) `PRAVAHA_SERVER_JAR=<copy> bin/pravaha-server --spring.profiles.active=dev` with the
assigned ports, confirm it starts, stop it. (b) `PRAVAHA_SERVER_JAR=/nope.jar bin/pravaha-server`.
**Expected:** (a) starts from the named jar; (b) exit 2, "cannot find the server jar".

## DEPLOY-006 — launchers work from an unrelated working directory
**Intent:** An operator runs `pravaha` from `$HOME`, or from `/`. A launcher that resolves its jar
relative to `$PWD` instead of to itself breaks for everybody who is not standing in the repository.
**Steps:** From `/` and from the scratch directory, run the absolute path
`/home/ashutosh/IdeaProjects/pravaha/bin/pravaha version`.
**Expected:** Exit 0 and the version, identical to running it from the repository root.

## DEPLOY-007 — launcher invoked through a symlink
**Intent:** `ln -s .../bin/pravaha /usr/local/bin/pravaha` is the normal way this ends up on a PATH.
`dirname "${BASH_SOURCE[0]}"` does not follow symlinks, so this is the case that usually breaks.
**Steps:** Create `<scratch>/pravaha -> <repo>/bin/pravaha`, add `<scratch>` to PATH, run
`pravaha version` with `PRAVAHA_CLI_JAR` unset.
**Expected:** Ideally exit 0. If it exits 2 because the symlink's directory has no sibling jar, that
is a defect of severity proportional to how the documentation tells people to install it — record it
either way, with the message the user sees.

## DEPLOY-008 — `JAVA_HOME` unset falls back to `java` on PATH
**Intent:** `${JAVA_HOME:+$JAVA_HOME/bin/}java` is meant to degrade to a bare `java`. Under
`set -u` an unset variable in the wrong expansion aborts the script instead.
**Steps:** `env -u JAVA_HOME bin/pravaha version` with a `java` on PATH.
**Expected:** Exit 0, version printed. Not `unbound variable`.

## DEPLOY-009 — `JAVA_HOME` pointing somewhere wrong fails legibly
**Intent:** A tired operator exports a stale `JAVA_HOME`. The failure should name the path.
**Steps:** `JAVA_HOME=/nonexistent bin/pravaha version`.
**Expected:** Non-zero exit and a message naming `/nonexistent/bin/java`. No silent success from a
different JVM, no `set -e` swallowing the error.

## DEPLOY-010 — `PRAVAHA_JAVA_OPTS` reaches the JVM
**Intent:** It is the only way to set heap or a system property, and it is unquoted in the launcher,
so it needs both to work for the normal case and to be checked for what happens with a value
containing spaces.
**Steps:** `PRAVAHA_JAVA_OPTS="-Xmx512m -Dpravaha.qa.marker=deploy" bin/pravaha version`, and then
observe the JVM command line of a server started the same way via `/proc/<pid>/cmdline`.
**Expected:** Both options appear on the JVM command line; the process starts.

---

## Container image

## DEPLOY-011 — `docker build` produces an image
**Intent:** The Dockerfile is part of the release. If it does not build, the containerised
deployment story is fiction.
**Steps:** `docker build -t pravaha-qa-deploy:test .` from the repository root, timed.
**Expected:** Exit 0, an image produced, and the build time recorded honestly.

## DEPLOY-012 — `.dockerignore` keeps build output and git history out of the context
**Intent:** A context containing every `target/` and `.git/` makes builds slow and can bake stale
jars or credentials into an image.
**Steps:** Compare `du -sh` of the tree against the context size docker reports; confirm the built
image contains no `.git` and no stale host-built jar.
**Expected:** Context materially smaller than the tree; no `/src/.git` and no host `target/` content
in the final image.

## DEPLOY-013 — the image's `HEALTHCHECK` command exists in the runtime image
**Intent:** The healthcheck runs `wget`. `eclipse-temurin:21-jre` is a slim base and may not have
it. A healthcheck that always fails makes every container permanently `unhealthy`, which is the
same failure as having no healthcheck plus an outage.
**Steps:** `docker run --rm --entrypoint sh <image> -c 'command -v wget || echo MISSING'`.
**Expected:** `wget` is present. If missing, FAIL.

## DEPLOY-014 — the container runs as the non-root user and can write its volume
**Intent:** The Dockerfile makes a point of `USER pravaha` and a `/var/lib/pravaha` volume. If the
volume is not writable by uid 10001 the journal cannot be written and the whole durability story
fails inside a container.
**Steps:** `docker run --rm --entrypoint sh <image> -c 'id; touch /var/lib/pravaha/probe && echo WRITABLE'`.
**Expected:** uid=10001(pravaha); `WRITABLE`.

## DEPLOY-015 — the container refuses to start open, and says why
**Intent:** The Dockerfile deliberately omits the dev profile. The image must therefore fail closed
with the security message, not start open and not crash obscurely.
**Steps:** `docker run --rm <image>` with no profile; capture exit code and last lines of output.
**Expected:** Non-zero exit, output containing the `allow-anonymous` guidance.

---

## Process lifecycle

## DEPLOY-016 — a dev-profile server starts and both ports listen
**Intent:** The baseline every later case builds on, and a check that the two ports are genuinely
independent settings.
**Steps:** Start with `--spring.profiles.active=dev --server.port=18300
--pravaha.flight.port=19300`; wait for readiness; `ss -ltnp` for both ports; `GET /actuator/health`.
**Expected:** Both ports listening on the recorded PID; health `UP`; log lines naming
`Flight SQL listening on 0.0.0.0:19300`.

## DEPLOY-017 — SIGTERM is a graceful shutdown
**Intent:** `server.shutdown: graceful` and `PravahaNode.stop()` claim an ordered teardown. An
orchestrator sends SIGTERM; if the process ignores it or dies dirtily, rolling restarts lose data.
**Steps:** Start as DEPLOY-016; `kill -TERM <pid>`; time to exit; inspect the log for the ordered
stop; confirm both ports released; confirm exit status.
**Expected:** Process exits within a few seconds with status 0 or 143; log shows the Flight server
and registry closed; `ss` shows neither port bound afterwards.

## DEPLOY-018 — SIGKILL leaves nothing that prevents a restart
**Intent:** Power loss equivalent. The journal is appended with `force(true)` and checkpoints are
published by rename, so an abrupt kill should leave a startable node.
**Steps:** With a journal configured and queries registered, `kill -9 <pid>`; confirm ports free;
start a new node on the same ports and journal.
**Expected:** New node starts; journal replays; no `.compacting` or `.tmp` leftovers that break
replay; no manual cleanup needed.

## DEPLOY-019 — a second node on an occupied HTTP port fails cleanly
**Intent:** The commonest operator mistake. It must be a legible error and a non-zero exit, not a
hang and not a stack trace with no cause.
**Steps:** With node A on 18300, start node B on the same HTTP port and a free Flight port.
**Expected:** B exits non-zero within the startup timeout; message identifies the port; A is
unaffected and still serving.

## DEPLOY-020 — a second node on an occupied Flight port fails cleanly
**Intent:** The Flight server binds after Spring is up, so this failure happens later in startup and
is the one most likely to leave a half-started process listening on HTTP.
**Steps:** Node A on 19301; node B with a free HTTP port and `--pravaha.flight.port=19301`.
**Expected:** B exits non-zero; B's HTTP port is **not** left listening after the failure.

## DEPLOY-021 — `server.port=0` and `pravaha.flight.port=0` bind ephemeral ports and report them
**Intent:** Port 0 is how a test harness or a sidecar-per-pod deployment avoids collisions. The node
must report the port it actually got, or the setting is useless.
**Steps:** Start with both ports 0; read the log for the actual ports; connect to each.
**Expected:** Two non-zero ports logged, both reachable.

## DEPLOY-022 — a privileged port fails cleanly rather than crashing
**Intent:** A tired operator sets `server.port=80`. As a non-root process this must be a clean
refusal with the port named.
**Steps:** Start with `--server.port=80`, then separately with `--pravaha.flight.port=443`.
**Expected:** Non-zero exit, a message naming the port and permission; no JVM crash, no hang, no
half-bound state.

## DEPLOY-023 — a config file that does not exist
**Intent:** `--spring.config.location` pointing at a typo'd path must not start with silent
defaults, because the defaults are not what the operator asked for.
**Steps:** `bin/pravaha-server --spring.config.location=/nonexistent/application.yaml`.
**Expected:** Non-zero exit naming the missing resource.

## DEPLOY-024 — unparseable YAML
**Intent:** A half-edited config file. Must fail at startup with the file and line, not later.
**Steps:** Write a file with a tab-indent / unclosed quote; start with it.
**Expected:** Non-zero exit; message identifies the file and the parse problem.

## DEPLOY-025 — a config value the node does not understand
**Intent:** `pravaha.security.policy` and `.audit` both throw a `PravahaException` for an unknown
value. That must surface as a clean startup failure carrying the guidance text, not as a
`BeanCreationException` wall with the cause buried.
**Steps:** Start with `--pravaha.security.policy=strict` and separately `--pravaha.security.audit=syslog`.
**Expected:** Non-zero exit; the guidance sentence ("Use 'permissive' or 'authenticated'") is
visible in the output.

## DEPLOY-026 — the default configuration refuses to start
**Intent:** The headline security fix. Must hold for the jar as shipped, with no profile.
**Steps:** `bin/pravaha-server --server.port=18302 --pravaha.flight.port=19302` (no dev profile).
**Expected:** Non-zero exit; the `allow-anonymous` message; nothing left listening.

---

## Registry journal

## DEPLOY-027 — registrations survive a restart
**Intent:** The core durability claim. Register, stop, start, and the queries are there.
**Setup:** `pravaha.registry.journal` set to a scratch file; a stream declared so a query can plan.
**Steps:** Start; `pravaha register` two queries; `pravaha queries` lists them; SIGTERM; start again
on the same journal; `pravaha queries`.
**Expected:** Both names present after restart with the same SQL. **Non-vacuity:** the same
procedure with the journal unset must lose them — run that too, so the pass cannot be an artefact of
something else keeping state.

## DEPLOY-028 — a dropped query does not come back
**Intent:** A journal that only ever appends registrations would resurrect dropped queries, which is
worse than losing them: the operator dropped it for a reason.
**Steps:** Register A and B; drop A; restart.
**Expected:** After restart, B is present and A is not.

## DEPLOY-029 — the journal file is not world-readable
**Intent:** It holds query text and bound parameter values — account numbers, customer ids. The
comment in the source says permission it like data; `SensitiveFiles` claims to enforce it.
**Steps:** `stat -c '%a %n'` on the journal file and its parent directory after a registration.
**Expected:** File mode `600`; parent directory `700`. Anything group- or world-readable is a FAIL.

## DEPLOY-030 — a truncated final record is tolerated, earlier records are kept
**Intent:** The expected result of a crash mid-append. The documented behaviour is to keep
everything before it.
**Steps:** Register two queries; stop; truncate the journal by a few bytes; restart.
**Expected:** The first registration recovers; the node logs what it recovered; it does not throw.

## DEPLOY-031 — a corrupt record is refused, not skipped
**Intent:** The brief's specific concern. Silently skipping a record loses a view a client expects,
with no error anywhere.
**Steps:** Register two queries; stop; overwrite bytes inside the **first** record (leaving its
length prefix intact) so it will not decode; restart.
**Expected:** Startup fails, or the node reports `PRV` journal-unreadable naming the record number.
A start that quietly comes up with one query and no error is a FAIL.

## DEPLOY-032 — a journal that cannot be written refuses the registration
**Intent:** Acknowledging a registration that was not durably recorded is the failure the journal
exists to prevent.
**Steps:** Point the journal at a path inside a directory made read-only (`chmod 500`); start;
attempt a registration.
**Expected:** The registration fails with `PRV` journal-unwritable; it is not acknowledged to the
client; the node itself stays up or fails at startup, but does not acknowledge-and-forget.

## DEPLOY-033 — no journal configured is warned about at startup
**Intent:** The configuration file promises "the node warns about this at startup rather than
leaving it to be discovered at the next restart". A promise in a comment is worth checking.
**Steps:** Start with `pravaha.registry.journal` unset; grep the log.
**Expected:** A WARN line saying registered queries live only in memory.

---

## Checkpoints

## DEPLOY-034 — checkpoints appear, one directory per query
**Intent:** The release claims a checkpointer is now constructed. Files on disk or it did not
happen.
**Setup:** `pravaha.checkpoint.directory` set; `pravaha.checkpoint.interval=2s` so the test is not
a minute long; a bound source so the query has state.
**Steps:** Register two queries; feed rows; wait several intervals; `find` the checkpoint root.
**Expected:** One subdirectory per query name; `checkpoint-<n>.bin` files inside each; file size
non-trivial.

## DEPLOY-035 — pruning keeps exactly `keep`, and never fewer than one
**Intent:** A pruning bug either grows without bound or deletes the last fallback. Both are silent.
**Steps:** With `keep=2` and a 1s interval, let a query run for well over ten intervals; count the
files. Separately, start with `keep=0`.
**Expected:** Exactly 2 files per query directory, and they are the two highest-numbered.
`keep=0` is refused (the code claims `at least one checkpoint must be kept`) rather than silently
keeping none.

## DEPLOY-036 — a query name cannot choose where a checkpoint lands
**Intent:** The name comes from a client. `QueryRegistry` sanitises with
`replaceAll("[^A-Za-z0-9_.-]", "_")`, which keeps `.` — so the literal name `..` survives
sanitisation and `root.resolve("..")` is the parent directory.
**Steps:** Attempt to register queries named `../escape`, `..`, `a/b`, and a name with a newline.
Inspect the checkpoint root's parent afterwards.
**Expected:** Either the name is refused at registration, or the checkpoint directory stays strictly
under the configured root. Any file written outside the root is a FAIL.

## DEPLOY-037 — what a restart actually restores
**Intent:** The configuration file says checkpoints are "what a query had accumulated, so a restart
recovers answers and not only questions". This is a release claim; it must be verified, not assumed.
**Steps:** With journal **and** checkpoints configured, register a query, feed rows until its view
has a known non-zero size, wait for at least one checkpoint, SIGTERM, restart on the same journal
and checkpoint directory, and read the view's size immediately at startup before any new rows
arrive.
**Expected:** If the claim holds, the view is non-empty at startup with the pre-restart contents. If
it comes back empty, that is a FAIL against the documented claim and must be reported precisely —
including whether anything in the process even reads a checkpoint back.

## DEPLOY-038 — checkpoint files are not world-readable
**Intent:** They hold serialised operator state — the aggregated customer data itself.
**Steps:** `stat -c '%a %n'` on the checkpoint files and their directories.
**Expected:** `600` for files. Record the directory modes too.

## DEPLOY-039 — a failing checkpoint is visible to an operator
**Intent:** `PeriodicCheckpointer` says failures are "reported every time, not once". The registry
constructs it with `message -> {}`. If that is a no-op, a node that has not checkpointed for six
hours looks exactly like one that has.
**Steps:** Make the checkpoint directory unwritable after startup (`chmod 500`); wait several
intervals; grep the server log for any mention of a checkpoint failure.
**Expected:** Something in the log, or a metric, tells an operator checkpoints are failing. Silence
is a FAIL.

---

## Health, readiness and metrics

## DEPLOY-040 — the three health endpoints answer
**Intent:** An orchestrator needs liveness and readiness separately; the Dockerfile's HEALTHCHECK
depends on `/actuator/health/liveness` existing.
**Steps:** `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
**Expected:** All three return 200 with a `status` field. Readiness reports `UP` only once the node
has finished starting.

## DEPLOY-041 — health reports honestly when the engine is not serving
**Intent:** The sharp end. A health check that is `UP` regardless of whether the process can do its
job is worse than none — it converts an outage into a silent one.
**Steps:** Start a node whose Flight endpoint is disabled (`pravaha.flight.enabled=false`) and, as a
second probe, a node whose `PravahaNode` has been stopped while HTTP stays up (via the actuator
shutdown path if exposed, otherwise by comparing health before and after a Flight failure). Read
health each time.
**Expected:** Health distinguishes "serving" from "process alive". If `/actuator/health` is `UP`
with no Flight listener and no registry, that is a FAIL of this case regardless of Spring Boot
defaults, because the release advertises liveness/readiness as meaningful.

## DEPLOY-042 — Prometheus publishes query metrics and they move
**Intent:** The metrics component exists; the question is whether a scrape actually shows a
registered query and whether the numbers change when rows arrive.
**Steps:** `GET /actuator/prometheus` before registering (baseline), after registering, after
feeding rows and waiting past the 15s sync, and after dropping the query.
**Expected:** `pravaha_query_rows_in`, `pravaha_query_view_size`, `pravaha_query_running` appear
tagged with the query name; `rows_in` is non-zero after rows arrive; the meters disappear after the
drop. **Non-vacuity:** the baseline scrape must not contain them, so their appearance is caused by
the registration.

## DEPLOY-043 — metric removal on drop is not merely eventual
**Intent:** Meters are reconciled on a 15-second timer. A deployment that registers and drops
quickly would accumulate meters between syncs; the case checks the steady state after the sync
window.
**Steps:** Register and drop 10 distinct query names; wait 20s; scrape.
**Expected:** No `pravaha_query_*` series for any dropped name.

---

## Resource behaviour

## DEPLOY-044 — repeated register/drop does not leak threads
**Intent:** Each registration starts a lane, a checkpointer and an ingest pump. If any of them
survives the drop, a long-running node accumulates threads until it cannot create one.
**Steps:** `jcmd <pid> Thread.print` baseline; 20 register/drop cycles; force a sync and GC; thread
count again, grouped by thread-name prefix.
**Expected:** Thread count returns to within a small constant of the baseline. A monotonic increase
of one or more threads per cycle is a FAIL, with the leaking thread name reported.

## DEPLOY-045 — repeated register/drop does not leak heap
**Intent:** The metrics component documents exactly this failure (a gauge holding a dropped query
alive). Worth measuring rather than trusting.
**Steps:** `jcmd <pid> GC.heap_info` baseline; 20 cycles; `jcmd <pid> GC.run` twice; heap info
again.
**Expected:** Used heap after a full GC is within a modest factor of the baseline, not growing
proportionally to the number of cycles.

---

## Added during execution

The three cases below were written *after* DEPLOY-032 and DEPLOY-018 exposed code paths the
original set did not cover. They are marked as additions so the record stays honest about what was
planned and what was discovered.

## DEPLOY-046 — a second name for an already-running computation is journalled
**Intent:** `QueryRegistry.register` returns early when a registration's fingerprint matches a
running query: it adds the name and returns *before* `journalRegistration` is reached. If that is
what happens, a client is told a registration succeeded and it is not written down — which is
exactly the "acknowledged and forgotten" failure the journal exists to prevent.
**Setup:** A journal configured and writable.
**Steps:** Register `alpha` with some SQL; register `beta` with byte-identical SQL (same
fingerprint); register `gamma` with different SQL. Inspect the journal. Restart. List queries.
**Expected:** All three names appear in the journal and all three come back after a restart. Any
name acknowledged with exit 0 and absent after the restart is a FAIL.

## DEPLOY-047 — the journal does not grow without bound
**Intent:** `RegistryJournal.compact` exists and rewrites the journal with only what is live. A
node that registers and drops queries all day appends a record each time; if nothing ever compacts,
the file grows for the life of the deployment and every restart replays all of it.
**Steps:** Run 50 register/drop cycles against a journalled node; measure the journal size; check
whether anything in the shipped code calls `compact`.
**Expected:** Either the journal is compacted automatically, or there is an operator-facing way to
compact it. Unbounded growth with no mechanism is a defect.

## DEPLOY-048 — a dropped query does not leave its checkpoint directory behind for ever
**Intent:** `FileCheckpointStore`'s constructor creates the query's directory at registration.
Nothing deletes it at drop. On a node that cycles queries this accumulates directories — and each
one that did take a checkpoint keeps the customer data in it after the query is gone.
**Steps:** Run 50 register/drop cycles with a checkpoint directory configured; count the
directories left behind.
**Expected:** Directories for dropped queries are cleaned up, or their retention is a documented
decision. Silent unbounded accumulation of directories holding operator state is a defect.
