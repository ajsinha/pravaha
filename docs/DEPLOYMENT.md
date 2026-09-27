# Deployment

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

How to ship a node: the image, the chart, the volumes, the ports, the environment, an upgrade,
a release — and the things this deliberately does not do.

This page is about **getting a node running somewhere**. What to do once it is running — what
bounds its memory, what to watch, what a restart costs, what is not solved — is
[`OPERATIONS.md`](OPERATIONS.md), and everything here assumes it.

---

## The short version

```bash
# 1. build the artefacts (offline; drop -o on a machine with an empty ~/.m2)
./mvnw -o -pl pravaha-server -am package -DskipTests

# 2. build the image from them
deploy/docker/build.sh --tag pravaha/pravaha-server:0.1.0-SNAPSHOT

# 3. prove it serves, end to end, against a real container
deploy/docker/smoke.sh --image pravaha/pravaha-server:0.1.0-SNAPSHOT

# 4. install it
helm install pravaha deploy/helm/pravaha \
  --set image.tag=0.1.0-SNAPSHOT \
  --set auth.existingSecret=pravaha-tokens
```

Step 3 is not ceremony. It registers a query over Flight, reads the view back, appends to a live
source and watches the view move, restarts onto the same volume and checks the registration came
back — and it fails if readiness is green while the engine cannot serve. An image that has not
been through it is an image nobody has run.

---

## One root, /opt/pravaha

Every path the product touches is under `/opt/pravaha`, in the image and on a host, and nothing
outside it is the product's (the owner's decision, 2026-09-26):

| Path | Holds | Written by |
|---|---|---|
| `bin/`, `lib/` | the launcher and the server jar | the image |
| `defaults/application.yaml` | the image's own defaults: where the journal and checkpoints go | the image |
| `conf/application.yaml` | **the deployment's engine configuration**, and `conf/tls/`, `conf/schemas/` beside it | the operator |
| `secrets/` | key material a chart mounts (`secrets/tls`, `secrets/auth`) | Kubernetes |
| `data/` | the volume: registry journal, checkpoints, dead letters, spill | the engine |
| `logs/` | the engine's log file and the audit trail | the engine |
| `console/` | the console, in its own image; `console/conf/application.yaml` is **the deployment's console configuration** | the operator |

The image's defaults moved from `conf/` to `defaults/` so that `conf/` is entirely the operator's: a
directory mounted there hides nothing the image needs. Mount each directory at the same path it
has inside the container, so a path in a log line is a path on the host.

## A QA host: two images, two files

[`deploy/qa/`](../deploy/qa) runs one engine and one console on one Linux machine with Docker, from
files, with no registry:

```bash
deploy/release/release.sh --version 0.1.1 --next 0.1.2-SNAPSHOT   # builds and tests both images
deploy/qa/bundle.sh --version 0.1.1        # target/qa-bundle/pravaha-qa-0.1.1.tar.gz
# on the host:
sudo ./install.sh --host qa-vm.example && cd /opt/pravaha && sudo docker compose up -d
```

| Image | Built by | Configured by |
|---|---|---|
| `pravaha/pravaha-server:<v>` | `deploy/docker/build.sh` | `/opt/pravaha/conf/application.yaml` |
| `pravaha/pravaha-console:<v>` | `deploy/docker/console/build.sh` (python:3.13-slim, uid 10001, 481 MB) | `/opt/pravaha/console/conf/application.yaml` |

The console reads its product defaults from the image first and the deployment's file second, key
by key, so the deployment's file names only what it changes and cannot pin a stale version.
`install.sh` generates the console password, the session secret and two engine tokens into the two
files (mode 0600, uid 10001), prints them once, and never overwrites either file on a re-run.
[`deploy/qa/README.md`](../deploy/qa/README.md) is the page to hand the QA team.

---

## The image

| | |
|---|---|
| Built by | [`deploy/docker/Dockerfile`](../deploy/docker/Dockerfile), staged by [`deploy/docker/build.sh`](../deploy/docker/build.sh) |
| Base | `eclipse-temurin:21-jre` (Ubuntu, glibc, 459 MB). Not Alpine: Parquet's Snappy codec is glibc-only ([ADR-053](adr/053-native-code-only-where-java-cannot.md)) |
| Size | **436,555,582 bytes** (~437 MB) as `docker image inspect` reports it; 77 MB of that is the application jar |
| User | uid **10001**, non-root, numeric — a Kubernetes `runAsUser` and a `docker --user` both take a number |
| Entrypoint | `/__cacert_entrypoint.sh bin/pravaha-server` |
| Ports | 18080 HTTP, 19090 Flight SQL |
| Volume | `/opt/pravaha/data` |
| Healthcheck | `wget --spider /actuator/health/liveness`, every 30s after a 45s start period |

It does **not** build the project. `build.sh` stages an ~80 MB context — the launcher, the jar and
the image's own `application.yaml` — so the daemon is never sent `.git`, `target/` or the
worktrees. Why not Jib, why not distroless, why not a Maven stage:
[ADR-047](adr/047-the-image-is-a-dockerfile-over-built-artefacts.md).

The **root `Dockerfile` is a different thing** and is kept: clone the repository, `docker build .`,
wait while Maven resolves the world inside the daemon. It is the convenience build for someone who
has only a clone. `deploy/docker/` is what a release publishes.

### The Arrow flags

They are on `bin/pravaha-server`'s `exec` line, **ahead of** `PRAVAHA_JAVA_OPTS`:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
```

That placement is the point. Setting `PRAVAHA_JAVA_OPTS` cannot lose them, which is exactly what
would happen if they lived in it. Without them a Flight server fails *inside* `putNext` and the
client sees `RST_STREAM` with nothing explaining why ([`OPERATIONS.md`](OPERATIONS.md), "JVM
flags"). On Java 24+ add `--sun-misc-unsafe-memory-access=allow`; it is **not** a valid option on
21, where the JVM refuses to start rather than ignoring it — so it is not in the image.

### What the image sets, and what it refuses to set

`PRAVAHA_JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError`. Fifty, not
the seventy-five a mostly-heap service would use: this engine's state is off-heap, Arrow and Netty
allocate direct buffers for every Flight call, and the spill tier is only fast while the page cache
holds its index. A heap at three quarters of the limit leaves that nothing, and then the kernel
decides what gives way rather than the JVM.

The image's own `application.yaml` sets **two** keys, both of which are *location*, not meaning:

```yaml
pravaha:
  registry:   { journal:   /opt/pravaha/data/registry.journal }
  checkpoint: { directory: /opt/pravaha/data/checkpoints }
```

It deliberately leaves these unset although it creates the directories:

| Key | Why the image will not decide it |
|---|---|
| `pravaha.dlq.directory` | Set, a record the engine cannot decode is written there and the source keeps reading. Unset, the source stops, loudly. Which is right is the deployment's call — and if it is set and unwritable the node refuses to start (`PRV-4090`) |
| `pravaha.state.spill.directory` | A directory alone switches the overflow tier on ([ADR-044](adr/044-no-rocksdb-the-mapped-tier-is-l1.md)). On, a query past its ceiling slows down; off, it dies with `PRV-4001`. And spilling onto whatever the volume happens to be — a tmpfs, say — only moves the out-of-memory to the kernel |
| `pravaha.node.id` | Defaults to `pravaha-node-01` from the jar. Two containers on one volume under one id is the case `PRV-4003` refuses. The chart sets it from the pod name |
| `pravaha.security.*` | The shipped defaults **refuse to start**, on purpose. No credential is in this image and none will be |

### Configuration: three layers, and which wins

```
  /opt/pravaha/defaults/application.yaml   the image's defaults           (lowest)
  /opt/pravaha/conf/application.yaml       a mounted file or a ConfigMap
  PRAVAHA_* / SPRING_* in the environment                                 (highest)
```

Spring Boot gives a location named later in `spring.config.additional-location` precedence over one
named earlier, and an environment variable precedence over both. The image sets the first two, both
`optional:`, so a container run with neither still starts.

Relaxed binding means every key has an environment spelling: `pravaha.flight.enabled` is
`PRAVAHA_FLIGHT_ENABLED`, `pravaha.state.spill.max-bytes` is `PRAVAHA_STATE_SPILL_MAX_BYTES`. One
`-e` is the right tool for one override; a file is the right tool for a deployment.

### Running one by hand

```bash
docker run -d --name pravaha \
  -p 18080:18080 -p 19090:19090 \
  -v pravaha-data:/opt/pravaha/data \
  -v "$PWD/application.yaml:/opt/pravaha/conf/application.yaml:ro" \
  --read-only --tmpfs /tmp:rw,size=64m \
  pravaha/pravaha-server:0.1.0-SNAPSHOT
```

`--read-only` works and is tested: the node needs nothing writable but the volume and `/tmp`.

The CLI is **not** in the image — 49 MB, a second copy of the whole engine, to run a client that
belongs on the operator's machine. Point it at the container instead:

```bash
pravaha queries --url grpc://127.0.0.1:19090
```

### What is on the volume, and who can read it

Three files hold what a customer would call their data, and all three are created **owner-only**
(`rw-------`) by uid 10001 before the first byte is written: the registry journal (query text and
the values clients filtered on), the checkpoints (the aggregated data itself) and the dead-letter
queue (the raw bytes of every record that failed). The user who started the container cannot read
them, and that is intended — [`OPERATIONS.md`](OPERATIONS.md), "Files that hold data". To look,
mount the volume into a throwaway container.

**Nothing is encrypted at rest.** If that is required, put the volume on an encrypted one.

---

## Native code

Pravaha is Java, and runs wherever a JDK 21 does, TLS included: TLS uses the JDK's own engine, not
BoringSSL. The build refuses native libraries on any compile or runtime path
([ADR-053](adr/053-native-code-only-where-java-cannot.md), `enforce-portable-native-code` in the root
POM), with one exception. Parquet's Snappy and zstd codecs have no Java implementation Parquet can
use, so they stay:

| Codec | Library | Built for |
|---|---|---|
| Snappy | `snappy-java` 1.1.10 | Linux with glibc (x86_64, aarch64, arm, ppc64le, s390x, riscv64 and others), macOS (x86_64, aarch64), Windows (x86, x86_64), FreeBSD x86_64, SunOS |
| zstd | `zstd-jni` 1.5.0 | Linux with glibc (amd64, aarch64, arm, i386, ppc64le, s390x, mips64), macOS (x86_64, aarch64), Windows (x86, amd64), FreeBSD |

They matter only for reading or writing Parquet (the `feedfile` and `delta` sources, `delta-sink`)
compressed with them. Elsewhere, including musl Linux such as Alpine, the node says at startup
which codec cannot load; uncompressed and gzip Parquet still work. Both codecs unpack into
`java.io.tmpdir` and load from there, so that directory must allow executing files. A Kubernetes
`emptyDir` does. With Docker's `--read-only`, mount `--tmpfs /tmp:rw,exec`, because Docker's tmpfs
is `noexec` by default.

## The chart

`deploy/helm/pravaha`. One node.

```
StatefulSet          the node; replicas is 1 and anything else is REFUSED at render time
StatefulSet          the standby, when standby.enabled
Service              the client one -- endpoints follow READINESS
Service (headless)   stable per-pod DNS, published before the pod is ready
ConfigMap            /opt/pravaha/conf/application.yaml, from .Values.config
volumeClaimTemplate  /opt/pravaha/data -- journal, checkpoints, DLQ, and spill unless separated
volumeClaimTemplate  the spill tier's own claim, when spill.separateVolume
ServiceAccount       an identity for cloud IAM; no token mounted, the node calls no API
PodDisruptionBudget  off by default, and read "What the chart does not do" before enabling it
ServiceMonitor       off by default; the CRD may not be installed
Pod (helm test)      readiness, /api/v1/status, and the Flight port accepting a connection
```

Every value is documented one line each in
[`deploy/helm/pravaha/values.yaml`](../deploy/helm/pravaha/values.yaml).

### Why a StatefulSet and not a Deployment

Not convention — the engine's own behaviour. A node **claims** the directories it writes durable
state into, by writing a `.pravaha-owner` marker naming its node id and refreshing it on a
30-second lease ([ADR-035](adr/035-wave-8-is-survival-not-distribution.md),
[`OPERATIONS.md`](OPERATIONS.md) "Who owns the state, and the standby"). Two things follow:

- **The identity has to be stable.** The state directory is namespaced by `pravaha.node.id`, *not*
  by address, precisely so a node restarting on a new pod IP finds its own checkpoints. A
  Deployment's pod names are random per pod, so the node would either look for state under a name
  nothing wrote, or need a hardcoded id — and then a rolling update runs two pods under one id and
  the new one meets `PRV-4003`.
- **The storage has to follow the identity.** A `volumeClaimTemplate` gives pod-0 the same claim
  every time it is rescheduled. A Deployment with one PVC gives it to whichever pod is running,
  which during a rolling update is two of them.

`podManagementPolicy: OrderedReady` with one replica means the old pod is gone before the new one
starts. If it were not, the new pod would refuse to start — which is the engine being right and the
chart being wrong.

### The probes

Three, and conflating any two of them is a restart loop.

| Probe | Path | Question |
|---|---|---|
| startup | `/actuator/health/liveness` | Has it finished coming up? Five minutes by default, which is a recovery from a large checkpoint and not a hang. Liveness does not run until this passes |
| liveness | `/actuator/health/liveness` | Is the JVM alive? Deliberately blind to whether the engine can serve — one failed query is not a node to restart |
| readiness | `/actuator/health/readiness` | **Can a client be served?** |

Readiness is the one that matters here, and it is not decoration. The engine indicator is *in* that
group (the server's own `application.yaml` puts it there), so a node whose Flight server is not
listening — unreachable by any client, by either SDK, or by the CLI — is **red**, and a Service
sends nobody to it. Before that indicator existed such a node reported UP on all three probes and
an orchestrator kept it in rotation.

`deploy/docker/smoke.sh` proves it rather than asserting it: it starts a container with
`PRAVAHA_FLIGHT_ENABLED=false` and requires liveness 200 **and** readiness 503. If that step ever
passes readiness, the chart's `readinessProbe` is decoration and the chart is wrong.

A `DEGRADED` node — every view still served, at least one source stopped (FEED-1) — answers 200 and
stays in rotation, on purpose. Alert on `pravaha_query_feed_stopped`, do not take the node out.

### Secrets

The chart **references** secrets and never creates one. There is no `Secret` template, and
`tls.enabled` with no `tls.existingSecret` is refused at render time.

```bash
kubectl create secret generic pravaha-tokens --from-file=tokens.yaml=./tokens.yaml
kubectl create secret tls pravaha-flight-tls --cert=tls.crt --key=tls.key
```

`tokens.yaml` is an ordinary Spring config file:

```yaml
pravaha:
  security:
    tokens:
      "a-long-random-string": { id: ann, tenant: acme, roles: [reader] }
```

It is projected into the pod **as `application.yaml`** — a directory in
`spring.config.additional-location` is searched for that name and nothing else, so the Secret's own
key name would be ignored in silence — and its location is named **last**, so the ConfigMap cannot
override the tokens. The TLS pair is mounted `0400` at `/opt/pravaha/secrets/tls` and the chart writes
`pravaha.flight.tls.certificate` and `.key` to point at the mounted files. Both paths are asserted
by `deploy/helm/test.sh`, along with the fact that no credential reaches the ConfigMap.

`StaticTokenVerifier` is named that so nobody mistakes it for an identity system. A real deployment
implements `TokenVerifier` against whatever issues its credentials — see
[`SECURITY.md`](SECURITY.md).

### Storage, and how much

One claim holds the journal, the checkpoints and the dead-letter queue. They are one filesystem in
the image and one blast radius in an incident, and splitting them would separate the ownership
marker from the state it guards. **Deleting the release does not delete the claim** — Kubernetes'
default, and the right one.

The spill tier can have a claim of its own (`spill.separateVolume`), which is the case worth the
extra complexity: spilled state wants a fast node-local disk and is rebuilt from the checkpoint if
it is lost, while the checkpoints want a durable one.

Sizing follows [`OPERATIONS.md`](OPERATIONS.md) rather than a guess:

| | Default | Where it comes from |
|---|---|---|
| `resources.requests.memory` | 2Gi | A single spilling query with a 64 MiB ceiling held ~480 MiB of anonymous memory in the measurement, and a 512 MiB cgroup limit OOM-killed it. 2Gi is a node with room for a few |
| `resources.limits.memory` | 4Gi | The heap is 50% of *this*; the rest is off-heap state, Arrow direct buffers and page cache for the spill index |
| `resources.requests.cpu` | 2 | A lane per query by default, plus the control plane. Raise it with the number of lanes really running |
| `resources.limits.cpu` | 4 | Above the request, not at it: `BACKOFF_PARK` still spins, and a CPU limit throttles lane threads |
| `persistence.size` | 20Gi | The journal is tiny; the checkpoints are the whole state, `keep: 3` deep |

**Size the page cache for the spill index**: roughly 100 bytes per distinct key per join side and
about 340 bytes per windowed accumulator. Inside the cache, spilled state runs at 0.44–1.15× of the
same load in RAM; past it, random lookups fall to a few thousand a second.
[`OPERATIONS.md`](OPERATIONS.md), "Disk", has both measurements.

### The standby

Off by default. It holds no lanes and serves nothing, polls the primary's ownership marker every
two seconds, and promotes itself when the claim has gone unrefreshed for the lease.

**What a takeover buys is recovery time, not continuity.** It resumes from the newest checkpoint,
so whatever the primary processed after that is replayed from the source offsets that checkpoint
carries, and anything the source can no longer supply is gone. The node says so in its promotion
log line; this page says it so nobody deploys it expecting a hot standby.

It needs two things the chart refuses to guess:

- an **explicit `nodeId`** — the primary's, deliberately (`StateOwnership` already distinguishes
  "our node id, claim expired" from "another node", so there is one mechanism deciding who owns the
  state rather than two that can disagree);
- a **`persistence.existingClaim` on ReadWriteMany** storage — both pods mount one claim, because a
  standby watching its own empty directory would wait for ever.

Both refusals name the reason. Nothing has to switch over at promotion: the client Service selects
on name and instance only, so the standby is already a member of it and its readiness is what keeps
it out of the endpoints until it is promoted.

### Upgrading a node

A node upgrade is a **stop and a start**. There is no clustering to roll through.

```bash
helm upgrade pravaha deploy/helm/pravaha --set image.tag=0.2.0
```

What that costs, in order:

1. The old pod is stopped. Shutdown reverses startup: stop accepting, let go of the queries,
   release the claim. `terminationGracePeriodSeconds` is 60 for that reason — cut it short and the
   ownership marker expires on its lease instead, and the next start waits the lease out.
2. The new pod starts, takes the same claim under the same node id, replays the journal — which
   **re-authorizes every registration against the policy as it is now**; an owner who has lost
   access does not quietly come back — and restores from the newest checkpoint.
3. Between those, the Service has no endpoints and clients get a connection refused. That is
   correct; a wrong answer would not be.

**A checkpoint written by an older engine may be refused.** Formats inside a checkpoint carry a
version and a different one is refused with `PRV-4002`, never guessed at. A refused query resumes
from the start of its sources — reprocessing, visible while it catches up, never a double count in
its answers, though a non-transactional sink is written the replayed rows again. Plan an upgrade
across such a change for a time when replaying the sources is affordable.
[`OPERATIONS.md`](OPERATIONS.md), "Upgrades", lists which format versions changed and when.

A `helm upgrade` that changes only `values.config` **does** roll the pod: the StatefulSet carries a
checksum of the rendered `application.yaml`. Without that, the ConfigMap would be replaced and the
node would go on running the old one — an upgrade reporting success and changing nothing.

### What the chart deliberately does not do

- **No clustering.** One replica, and more is refused at render time naming
  [ADR-045](adr/045-cluster-mode-assigns-queries-not-rows.md). Multi-node execution is on hold: a
  node owns whole computations, not rows, and nothing consumes partition ownership yet, so a node
  refuses to serve `PARTITIONED` with `PRV-9002`. If you need several nodes, run several releases,
  each with its own storage and its own queries.
- **No horizontal autoscaler.** Design §27.3 wants one driven by `lane_backpressure_ratio`. That
  metric is not published — engine-internal metrics are the gap [`OPERATIONS.md`](OPERATIONS.md)
  names — and scaling out is the clustering that does not exist. An HPA here would add replicas
  that refuse to start.
- **No pod anti-affinity across zones.** There is one pod.
- **No Secret, ever.** Referenced, never created. A chart that templates a credential puts it in
  `helm get values` and in whatever holds the release.
- **No Ingress.** Neither port belongs on the internet, and Flight is gRPC — an HTTP/1 ingress in
  front of it silently does not work. Use a Gateway or a mesh that speaks HTTP/2 end to end.
- **No console.** It is a separate process and its own artefact
  ([ADR-024](adr/024-console-as-a-separate-process.md),
  [ADR-033](adr/033-the-ui-ships-as-its-own-artefact.md)), and it reaches the engine only through
  the public API. It is not in this chart and not in this image.
- **No PodDisruptionBudget by default.** On one replica `minAvailable: 1` is unsatisfiable by
  definition and `kubectl drain` blocks for ever; the chart refuses that setting outright.
  `maxUnavailable: 1` is honest: it says nothing is protected, because with one node nothing can be.
- **Every connector ships inside the jar; there is no plugin directory.** Since 2026-09-26 the
  server jar carries all eight connector modules (`filesystem`, `feedfile`, `delta`, `jdbc`,
  `kafka`, `postgres-cdc`, `aerospike`, `cassandra`: fourteen plugins in all, lookups and sinks
  included) and the PostgreSQL JDBC driver, so `java -jar` binds any of them with nothing
  installed. The jar is about 163 MB, up from 74. Spring Boot's nested layout keeps each plugin jar
  whole, so no plugin's `META-INF/services` file is merged over by another's.
  Two consequences to know. Spring Boot's Cassandra auto-configuration is excluded, because with
  the driver present it opened a session to `localhost:9042` at startup and a node with no
  Cassandra refused to start: connections belong to plugins, never to the framework. And the
  launcher still reads only what is inside the jar (`-Dloader.path` is not honoured), so a JDBC
  driver for a database other than PostgreSQL cannot be added at deployment time. The container
  image is built from this jar, so it carries the connectors too.

---

## Release

### How a version is set today

Every one of the reactor's **37 poms** carries the version as a literal — the root as
`<project><version>`, each child as `<parent><version>`. There is no `${revision}` property, no
`flatten-maven-plugin`, and no `<distributionManagement>`. Two wheels
(`sdk/python/pyproject.toml`, `console/pyproject.toml`) and the chart's `appVersion` carry it too.
Forty files, and they have to agree or the reactor will not resolve.

```bash
deploy/release/set-version.sh --check     # do they?
deploy/release/set-version.sh 0.2.0       # make them
```

`versions-maven-plugin` does exactly this job and is **not in the local repository**, so it cannot
run in the offline build this project is gated on. That is why the script exists and why it
re-checks its own work.

PEP 440 has no snapshot, so a Python version is the Maven one with `-SNAPSHOT` removed:
`0.2.0-SNAPSHOT` and `0.2.0` both give `0.2.0`. That is why the repository is consistent today at
`0.1.0-SNAPSHOT` / `0.1.0` rather than wrong — and why **a snapshot wheel is never published**, as
it would claim to be the release. The **chart's own `version`** is not the engine's; it moves when
the chart changes, under `--chart-version`.

### What a release does

```bash
deploy/release/release.sh --version 0.2.0 --next 0.2.1-SNAPSHOT --dry-run
deploy/release/release.sh --version 0.2.0 --next 0.2.1-SNAPSHOT
```

1. Refuse a dirty tree (`--porcelain`, so untracked files count), a branch that is not a release
   branch, or a tag that already exists.
2. Set the version across all forty files.
3. `./mvnw -o clean verify` — the whole reactor, offline, tests and all.
4. Build the image and tag it with the release version.
5. Run `deploy/docker/smoke.sh` against **the image it just built**.
6. `deploy/helm/test.sh`, then `helm package`.
7. Commit, and write an **annotated tag** `v0.2.0`.
8. Set the tree to `0.2.1-SNAPSHOT` and commit that.

### What a release still needs, and does not have

Written down rather than automated, because none of it exists in this repository and inventing it
in a script would let a release report success having shipped nothing.

| Step | What is missing |
|---|---|
| `mvn deploy` | No `<distributionManagement>` in the root pom, and no repository to deploy to. Adding one is a decision about where the jars live and who may publish them |
| Push the tag and the commits | Deliberate. `release.sh` makes a release reproducible; the owner decides when it is public |
| Push the image | No registry configured, no credential in the repository. One command once there is one: `deploy/docker/build.sh --push --tag <registry>/pravaha-server:0.2.0` |
| Push the chart | Likewise: `helm push target/pravaha-*.tgz oci://<registry>/charts` |
| Publish the wheels | `python -m build` in `sdk/python` and `console` produces them. No index, no credential |
| Sign anything | No GPG key, no keyless signing, no provenance attestation, no SBOM |

Existing tags are `M1`, `M2`, `M7`, `wave-7-complete` — milestones, not versions. `v<version>` is
the shape a release tag takes from here.

---

## CI

| Workflow | Job | Has it ever run? |
|---|---|---|
| `fast` | build, format, unit and property tests | Its **command** runs constantly on the development machine. Never as a GitHub Actions workflow |
| `verify` | integration tests under `-Pit` | **No.** Listed in [`REMAINING.md`](REMAINING.md) B12 as never run |
| `matrix` | JDK 21 | Command yes, workflow no |
| `matrix` | JDK 25 | **No** — there is no 25 on the development machine |
| `matrix` | Spring Boot 3.5 | Command yes |
| `matrix` | Spring Boot 3.2, 3.3, 3.4 | Command yes (2026-09-27, 41 tests each). Workflow no |
| `suites` | Python SDK | Command yes (`make -C sdk/python test`). Workflow no |
| `suites` | console, headless Chrome | Command yes (`make -C console test`). Workflow no |
| `packaging` | versions, CI helpers, chart, image | Every command green on the development machine; **no** workflow run |

**This machine's CI is not exercised.** Each workflow says so in its own header, with what is
likeliest to break on a first run.

Three of the jobs exist because a green tick had been meaning nothing:

- `verify` runs `deploy/ci/assert-suite-ran.sh failsafe 20` after the build. `-Pit` exits 0 having
  run zero integration tests if the profile did not activate, if the Docker daemon was unreachable,
  or if the module left the reactor.
- `suites` runs the console's browser-marked tests **alone** and fails if any skipped. They skip
  themselves when no Chrome is found, so without that the job is a tick meaning "we did not look".
- `packaging` runs `deploy/ci/check-workflows.py`, which parses every workflow and holds it to
  having a trigger, jobs, `runs-on`, `timeout-minutes` and pinned action refs. GitHub validates
  workflows on push, after the commit; on a machine whose CI has never run, an unparsed workflow
  looks fine.

The `packaging` image job builds **online**, deliberately and with a comment saying so where it
does it: the offline gate is a property of a machine with a populated `~/.m2`, and `-o` on a fresh
runner fails with a message about the cache rather than about the commit.

---

## Tests

Everything here has one, and it can be run now.

| | Command | What it proves |
|---|---|---|
| Image | `deploy/docker/smoke.sh --image <tag>` | Ten steps against a real container, two of them seeds: readiness goes red when Flight is taken away, and the node serves under `--read-only` |
| Chart | `deploy/helm/test.sh` | 18 checks; `helm lint` and `helm template` over three scenarios, six seeds that must be refused |
| Release | `deploy/release/test.sh` | 8 checks on a throwaway copy of the tree; four seeds, including one pom left at another version |
| CI helpers | `deploy/ci/test.sh` | 15 checks; eleven seeds, from malformed YAML to a suite that skipped every test |

Helm is **not installed on the development machine**. Every chart run recorded here used a
helm 3.16.3 binary fetched into a scratch directory for the purpose and removed afterwards; the
`packaging` workflow installs the same version with `azure/setup-helm`.

---

## Where to go next

| | |
|---|---|
| [Operations](OPERATIONS.md) | What bounds a node, what to watch, what a restart costs, what is not solved |
| [Security](SECURITY.md) | Authentication, authorization, row filters, audit |
| [TLS](CONNECTOR_TLS.md) | Every encrypted connection Pravaha makes or accepts |
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code, including the ones this page names |
| [ADR-047](adr/047-the-image-is-a-dockerfile-over-built-artefacts.md) | Why the image is a Dockerfile over built artefacts |
| [ADR-053](adr/053-native-code-only-where-java-cannot.md) | Native code only where Java cannot do the job; why the base is glibc |
| [ADR-035](adr/035-wave-8-is-survival-not-distribution.md) | Node ownership of state, and the standby the chart deploys |
| [ADR-045](adr/045-cluster-mode-assigns-queries-not-rows.md) | The clustering this chart does not do |
