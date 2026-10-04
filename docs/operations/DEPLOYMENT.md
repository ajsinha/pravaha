# Deployment

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

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

# 2. build the image from them (a Java 21 JRE)
deploy/docker/build.sh --tag pravaha/pravaha-server:2.3.0

# 3. prove it serves, end to end, against a real container
deploy/docker/smoke.sh --image pravaha/pravaha-server:2.3.0

# 4. install it
helm install pravaha deploy/helm/pravaha \
  --set image.tag=2.3.0 \
  --set auth.existingSecret=pravaha-tokens
```

Step 3 is not ceremony. It registers a query over Flight, reads the view back, appends to a live
source and watches the view move, restarts onto the same volume and checks the registration came
back — and it fails if readiness is green while the engine cannot serve. An image that has not
been through it is an image nobody has run.

---

## One root, /opt/pravaha

Every path the product touches is under one root, `PRAVAHA_HOME` — `/opt/pravaha` in both images,
on a QA host, and in an unpacked distribution — and nothing outside it is the product's (the owner's
decisions, 2026-09-26 and 2026-09-29). `bin/pravaha-server` makes it so: from a home it points the
JVM's temporary directory, `user.home`, heap dumps and crash files under it and names the jar's
`pravaha-home.yaml`, which places the journal, checkpoints, identity store, audit trail and log.
[`RUNNING_IN_DOCKER.md`](RUNNING_IN_DOCKER.md), "The layout", has every path, with the setting that
places it:

| Path | Holds | Written by |
|---|---|---|
| `bin/`, `lib/` | the launcher and the server jar | the image / distribution |
| `conf/application.yaml` | **the deployment's engine configuration**, read after the layout so it wins; `conf/console.yaml` the console's | the operator |
| `secrets/` | key material: the initial admin password, tokens, TLS keys (a chart mounts `secrets/tls`, `secrets/auth`) | the operator / Kubernetes |
| `plugins/` | jars added to the engine's classpath (`loader.path`) | the operator |
| `data/` | the volume: registry journal, checkpoints, identity store, catalogue, alerts, dead letters, spill | the engine |
| `logs/` | `pravaha-server.log`, `audit.jsonl`, `pravaha-console.log`, heap dumps | the engine, the console |
| `tmp/` | `java.io.tmpdir`: native codecs, Tomcat's work directory | the engine, the console |
| `console/` | the console's code, in its own image | the image |

The image's old `defaults/application.yaml` is gone: the layout now travels inside the jar, so the
image, a distribution and a developer's `PRAVAHA_HOME` all get the same one. Mount each directory
at the same path it has inside the container, so a path in a log line is a path on the host.

## A QA host: two images, two files

[`deploy/qa/`](../../deploy/qa) runs one engine and one console on one Linux machine with Docker, from
files, with no registry:

```bash
deploy/release/release.sh --version 0.1.1 --next 0.1.2-SNAPSHOT   # builds and tests both images
deploy/qa/bundle.sh --version 0.1.1        # target/qa-bundle/pravaha-qa-0.1.1.tar.gz
# on the host:
sudo ./install.sh --host qa-vm.example && cd /opt/pravaha && sudo docker compose up -d
```

| Image | Built by | Configured by |
|---|---|---|
| `pravaha/pravaha-server:<v>` | `deploy/docker/build.sh` (`eclipse-temurin:21-jre`) | `/opt/pravaha/conf/application.yaml` |
| `pravaha/pravaha-console:<v>` | `deploy/docker/console/build.sh` (python:3.13-slim, uid 10001 or any, 497 MB) | `/opt/pravaha/console/conf/application.yaml` |

The console reads its product defaults from the image first and the deployment's file second, key
by key, so the deployment's file names only what it changes and cannot pin a stale version.
`install.sh` generates `admin`'s first password into `conf/initial-admin-password`, the console's session
secret, and a legacy engine token for the CLI and SDKs (mode 0600, uid 10001). It prints them once and
never overwrites a file on a re-run. The engine keeps people, passwords and API keys itself (ADR-052),
so the console holds no credential: people sign in as themselves.
[`deploy/qa/README.md`](../../deploy/qa/README.md) is the page to hand the QA team.

---

## The image

| | |
|---|---|
| Built by | [`deploy/docker/Dockerfile`](../../deploy/docker/Dockerfile), staged by [`deploy/docker/build.sh`](../../deploy/docker/build.sh) |
| Base | `eclipse-temurin:21-jre` (Ubuntu, glibc), the one engine image: the jar's classes are Java 21 class files, so any JRE from 21 up would run it ([ADR-062](../design/adr/062-java-21-or-later.md)), and there is no per-Java tag. Not Alpine: Parquet's Snappy codec is glibc-only ([ADR-053](../design/adr/053-native-code-only-where-java-cannot.md)) |
| Size | **822 MB** on disk as `docker images` reports it on 2026-10-01 (282 MB content); 176 MB of that is the application jar |
| User | uid **10001** by default, non-root, numeric; **any** `--user` works over bind mounts ([`RUNNING_IN_DOCKER.md`](RUNNING_IN_DOCKER.md), "Any uid") |
| Entrypoint | `/__cacert_entrypoint.sh bin/pravaha-server`, which adds `--enable-native-access=ALL-UNNAMED` (and `--sun-misc-unsafe-memory-access=allow` on a JVM 23 or later), so the node starts without a JVM warning, and refuses a JVM older than 21 |
| Ports | 18080 HTTP, 19090 Flight SQL |
| Volumes | `/opt/pravaha/data`, `/opt/pravaha/logs` |
| Healthcheck | `bin/pravaha-health /actuator/health/liveness` (bash only; the JRE image carries no `wget` or `curl`), every 30s after a 45s start period |

It does **not** build the project. `build.sh` stages a context of the launcher and the jar (~190
MB), so the daemon is never sent `.git`, `target/` or the worktrees. Why not Jib, why not distroless, why not a Maven stage:
[ADR-047](../design/adr/047-the-image-is-a-dockerfile-over-built-artefacts.md).

The **root `Dockerfile` is a different thing** and is kept: clone the repository, `docker build .`,
wait while Maven resolves the world inside the daemon. It is the convenience build for someone who
has only a clone and Docker — no JDK — and builds just `pravaha-server` and `pravaha-cli`, into the
same runtime layout. `deploy/docker/` is what a release publishes.

### The Arrow flags

They are on `bin/pravaha-server`'s `exec` line, **ahead of** `PRAVAHA_JAVA_OPTS`:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
```

That placement is the point. Setting `PRAVAHA_JAVA_OPTS` cannot lose them, which is exactly what
would happen if they lived in it. Without them a Flight server fails *inside* `putNext` and the
client sees `RST_STREAM` with nothing explaining why ([`OPERATIONS.md`](OPERATIONS.md), "JVM
flags"). `--enable-native-access=ALL-UNNAMED` is on the same exec line, always (valid from Java 21);
`--sun-misc-unsafe-memory-access=allow` is added only when the JVM is 23 or later, because older JVMs
refuse the option. The image runs on 21, so it carries the first only.

### What the image sets, and what it refuses to set

`PRAVAHA_JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError`. Fifty, not
the seventy-five a mostly-heap service would use: this engine's state is off-heap, Arrow and Netty
allocate direct buffers for every Flight call, and the spill tier is only fast while the page cache
holds its index. A heap at three quarters of the limit leaves that nothing, and then the kernel
decides what gives way rather than the JVM.

The image sets `PRAVAHA_HOME=/opt/pravaha` and nothing else of the engine's configuration. The
launcher then reads the jar's `pravaha-home.yaml`, which sets only *locations* — the node's log, the
registry journal, the checkpoints, the identity store and the audit file, all under `/opt/pravaha`.

Neither the image nor the layout sets these, although the layout has a place for each:

| Key | Why the image will not decide it |
|---|---|
| `pravaha.dlq.directory` | Set, a record the engine cannot decode is written there and the source keeps reading. Unset, the source stops, loudly. Which is right is the deployment's call — and if it is set and unwritable the node refuses to start (`PRV-4090`) |
| `pravaha.state.spill.directory` | A directory alone switches the overflow tier on ([ADR-044](../design/adr/044-no-rocksdb-the-mapped-tier-is-l1.md)). On, a query past its ceiling slows down; off, it dies with `PRV-4001`. And spilling onto whatever the volume happens to be — a tmpfs, say — only moves the out-of-memory to the kernel |
| `pravaha.node.id` | Defaults to `pravaha-node-01` from the jar. Two containers on one volume under one id is the case `PRV-4003` refuses. The chart sets it from the pod name |
| `pravaha.security.*` | The shipped defaults **refuse to start**, on purpose. No credential is in this image and none will be |

### Configuration: the layers, and which wins

```
  the jar's application.yaml                  the engine's defaults           (lowest)
  the jar's pravaha-home.yaml                 where things go under /opt/pravaha
  /opt/pravaha/conf/application.yaml          a mounted file or a ConfigMap
  PRAVAHA_* / SPRING_* in the environment                                     (highest)
```

`bin/pravaha-server` names the middle two in `spring.config.additional-location`, both `optional:`,
so a container run with no mount still starts; a location named later wins. An explicit
`SPRING_CONFIG_ADDITIONAL_LOCATION` replaces them — the chart sets one, naming the layout first,
then `conf/`, then its secret mount.

Relaxed binding means every key has an environment spelling: `pravaha.flight.enabled` is
`PRAVAHA_FLIGHT_ENABLED`, `pravaha.state.spill.max-bytes` is `PRAVAHA_STATE_SPILL_MAX_BYTES`. One
`-e` is the right tool for one override; a file is the right tool for a deployment.

### Running one by hand

```bash
mkdir -p pravaha-home/{conf,data,logs,tmp}          # yours, before Docker can make them root's
docker run -d --name pravaha --user "$(id -u):$(id -g)" --read-only \
  -p 127.0.0.1:18080:18080 -p 127.0.0.1:19090:19090 \
  -v "$PWD/pravaha-home/conf:/opt/pravaha/conf:ro" \
  -v "$PWD/pravaha-home/data:/opt/pravaha/data" \
  -v "$PWD/pravaha-home/logs:/opt/pravaha/logs" \
  -v "$PWD/pravaha-home/tmp:/opt/pravaha/tmp" \
  pravaha/pravaha-server:2.3.0
```

`--read-only` works and is tested: the node needs nothing writable outside `/opt/pravaha`
(`deploy/docker/smoke.sh` runs it with only a tmpfs at `/opt/pravaha/tmp`, and the compose stack runs
that way every time). For the whole stack — console, Kafka, databases, monitoring — use
[`deploy/docker/compose`](../../deploy/docker/compose); [`RUNNING_IN_DOCKER.md`](RUNNING_IN_DOCKER.md)
is its reference.

The CLI is **not** in the engine image — 49 MB, a second copy of the whole engine, to run a client
that belongs on the operator's machine. Point it at the container, or use the console image, which
carries the Python `pravaha` command:

```bash
pravaha queries --url grpc://127.0.0.1:19090
```

### What is on the volume, and who can read it

Three files hold what a customer would call their data, and all three are created **owner-only**
(`rw-------`) by the user the node runs as, before the first byte is written: the registry journal
(query text and the values clients filtered on), the checkpoints (the aggregated data itself) and the
dead-letter queue (the raw bytes of every record that failed). Run the container as yourself over
bind mounts and they are yours to read; run it as the default uid 10001 over a named volume and
they are 10001's — [`OPERATIONS.md`](OPERATIONS.md), "Files that hold data".

**Nothing is encrypted at rest.** If that is required, put the volume on an encrypted one.


---

## Without Docker: the same layout

The container is not required. A node runs the same way from any directory you choose as its
`PRAVAHA_HOME`, with the same layout, the same configuration file and the same guarantee that
nothing is written outside it — and every file belongs to the user who runs it.

**A distribution.** `deploy/release/dist.sh` assembles one from the built jars (it does not run
Maven), as a directory and a tarball that is a home the moment it is unpacked:

```bash
./mvnw -o -pl pravaha-server,pravaha-cli -am package -DskipTests
deploy/release/dist.sh
#   dist.sh: .../target/dist/pravaha-2.3.0
#   dist.sh: .../target/dist/pravaha-2.3.0.tar.gz (185M)
```

```
pravaha-2.3.0/
  bin/pravaha-server  bin/pravaha-engine
  lib/pravaha-server.jar  lib/pravaha-engine.jar
  conf/application.yaml.example  conf/pravaha-server.service
  data/ (0700)  logs/  plugins/  secrets/ (0700)  tmp/
  LICENSE  VERSION
```

Unpacked anywhere and started from anywhere, it finds its home from `bin/`:

```bash
tar xzf pravaha-2.3.0.tar.gz && cd pravaha-2.3.0
cp conf/application.yaml.example conf/application.yaml     # open, for a first run; edit it
cd / && /path/to/pravaha-2.3.0/bin/pravaha-server
```

Run that way on 2026-09-29 (from `/`, `PRAVAHA_HOME` unset), the node came ready and wrote only
`conf/application.yaml` (the copy), `data/.pravaha-owner`, `data/checkpoints/.pravaha-owner`,
`logs/pravaha-server.log` and, in `tmp/`, the Snappy library and Tomcat's work directories — all
inside the unpacked directory.

**A home of your own, from a checkout.** `PRAVAHA_HOME=~/pravaha-home bin/pravaha-server` does the same
with the jar in `pravaha-server/target`: see [`OPERATIONS.md`](OPERATIONS.md), "Where a node keeps its
files: PRAVAHA_HOME". With `PRAVAHA_HOME` unset, a checkout behaves as it always has.

**systemd.** The distribution carries
[`conf/pravaha-server.service`](../../deploy/release/distribution/pravaha-server.service), for a home at
`/opt/pravaha` owned by a `pravaha` system user: `PRAVAHA_HOME=/opt/pravaha`, a 60-second stop
timeout for the last checkpoint, and `ProtectSystem=strict` with only `data/`, `logs/` and `tmp/`
writable, so the kernel enforces the layout too. Its header has the four commands that install it.
It passes `systemd-analyze verify` (checked with the paths pointed at the unpacked distribution
above); it has not been run under systemd on this machine.

**The console without Docker** runs from a checkout (`console/`, `make run`) or its wheel, with the
same convention if you want it: `--config console/config/application.yaml,$PRAVAHA_HOME/conf/console.yaml`,
`CONSOLE_LOG_FILE=$PRAVAHA_HOME/logs/pravaha-console.log` and `PRAVAHA_CONFIG_DIR=$PRAVAHA_HOME/data/console`.
[`../development/setup/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md`](../development/setup/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md) walks the whole
no-Docker route, build and tests included.
---

## Native code

Pravaha is Java, and runs wherever a JDK 21 or later does, TLS included: TLS uses the JDK's own engine, not
BoringSSL. The build refuses native libraries on any compile or runtime path
([ADR-053](../design/adr/053-native-code-only-where-java-cannot.md), `enforce-portable-native-code` in the root
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
[`deploy/helm/pravaha/values.yaml`](../../deploy/helm/pravaha/values.yaml).

### Why a StatefulSet and not a Deployment

Not convention — the engine's own behaviour. A node **claims** the directories it writes durable
state into, by writing a `.pravaha-owner` marker naming its node id and refreshing it on a
30-second lease ([ADR-035](../design/adr/035-wave-8-is-survival-not-distribution.md),
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

A `DEGRADED` node — every view still served, at least one source stopped (FEED-1: a file gone, a
Kafka topic deleted, a CDC slot dropped) or a journalled registration refused at recovery
(RECOVERYHEALTH-1) or an audit trail that cannot be written (AUDITROTATE-1) — answers 200 and stays
in rotation, on purpose. Alert on
`pravaha_query_feed_stopped`, `pravaha_registry_recovery_refused` and `pravaha_audit_failing`, do not
take the node out.

### Secrets

The chart **references** secrets and never creates one. There is no `Secret` template, and
`tls.enabled` with no `tls.existingSecret` is refused at render time.

Names: the release base name is cut to **52** characters (a StatefulSet's pods carry a
`controller-revision-hash` label of its name plus 11, which may not pass 63), and every derived name
— `-headless`, `-standby`, `-test-probes` — shortens the base and keeps its suffix, so a long
`fullnameOverride` still renders names Kubernetes accepts (HELMNAME-1). An empty `image.repository`
is refused at render time rather than rendered as `:<tag>`.

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
  [ADR-045](../design/adr/045-cluster-mode-assigns-queries-not-rows.md). Multi-node execution is on hold: a
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
  ([ADR-024](../design/adr/024-console-as-a-separate-process.md),
  [ADR-033](../design/adr/033-the-ui-ships-as-its-own-artefact.md)), and it reaches the engine only through
  the public API. It is not in this chart and not in this image.
- **No PodDisruptionBudget by default.** On one replica `minAvailable: 1` is unsatisfiable by
  definition and `kubectl drain` blocks for ever; the chart refuses that setting outright.
  `maxUnavailable: 1` is honest: it says nothing is protected, because with one node nothing can be.
- **Every connector ships inside the jar, and `plugins/` adds to it.** Since 2026-09-26 the
  server jar carries all its connector modules and the PostgreSQL JDBC driver, so `java -jar` binds
  any of them with nothing installed; the jar is about 176 MB. Spring Boot's nested layout keeps
  each plugin jar whole, so no plugin's `META-INF/services` file is merged over by another's.
  Spring Boot's Cassandra auto-configuration is excluded, because with the driver present it opened
  a session to `localhost:9042` at startup: connections belong to plugins, never to the framework.
  Since 2026-09-29 the jar's launcher is `PropertiesLauncher` (the `ZIP` layout) and
  `bin/pravaha-server` passes `-Dloader.path=$PRAVAHA_HOME/plugins`, so a jar dropped in `plugins/`
  is on the classpath at the next start — a JDBC driver for a database other than PostgreSQL, found
  by `DriverManager` like a bundled one. Proved in the compose stack: a `jdbc` source against MySQL
  was refused with `PRV-5070 ... No suitable driver found`, then answered after
  `mysql-connector-j-9.1.0.jar` was copied into `plugins/` and the node restarted. The chart does not
  mount a `plugins/` volume; add one with `extraConfigMounts`-style values if a cluster needs it.

---

## Release

### How a version is set today

Every one of the reactor's **40 poms** (2026-10-02) carries the version as a literal — the root as
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
`0.2.1-SNAPSHOT` / `0.2.1` rather than wrong — and why **a snapshot wheel is never published**, as
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
4. Build the engine image (a Java 21 JRE) and tag it with the release version;
   build the console image.
5. Run `deploy/docker/smoke.sh` against **the engine image it just built**.
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
| `verify` | integration tests under `-Pit` | **No.** Listed in [`../development/REMAINING.md`](../development/REMAINING.md) B12 as never run |
| `matrix` | Agrona memory path, flagged | Workflow no. `fast` is the whole-reactor run, a matrix of JDK 21 and 25 |
| `matrix` | Spring Boot 3.5 | Command yes |
| `matrix` | Spring Boot 3.4 | Command yes (on JDK 25, 2026-10-01, 41 tests). Workflow no. 3.2 and 3.3 are not tested: out of open-source support, and not brought back ([ADR-062](../design/adr/062-java-21-or-later.md)) |
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
| Image | `deploy/docker/smoke.sh --image <tag>` | Eleven steps against a real container, two of them seeds: readiness goes red when Flight is taken away, and the node serves under `--read-only`; the image's own `HEALTHCHECK` passes inside it, and Parquet's native codecs load |
| Chart | `deploy/helm/test.sh` | 22 checks; `helm lint` and `helm template` over three scenarios, eight seeds that must be refused, and the names a 78-character `fullnameOverride` renders |
| Release | `deploy/release/test.sh` | 8 checks on a throwaway copy of the tree; four seeds, including one pom left at another version |
| CI helpers | `deploy/ci/test.sh` | 15 checks; eleven seeds, from malformed YAML to a suite that skipped every test |

The chart's 22 checks pass under helm 3.16.3 (the version the `packaging` workflow installs with
`azure/setup-helm`) and, since 2026-09-30, under helm 4.3.0 installed on the development machine,
including the `/opt/pravaha` layout in `_pod.tpl`.

---

## Where to go next

| | |
|---|---|
| [Operations](OPERATIONS.md) | What bounds a node, what to watch, what a restart costs, what is not solved |
| [Security](SECURITY.md) | Authentication, authorization, row filters, audit |
| [TLS](../guides/CONNECTOR_TLS.md) | Every encrypted connection Pravaha makes or accepts |
| [Troubleshooting](../guides/TROUBLESHOOTING.md) | Every `PRV-` code, including the ones this page names |
| [ADR-047](../design/adr/047-the-image-is-a-dockerfile-over-built-artefacts.md) | Why the image is a Dockerfile over built artefacts |
| [ADR-053](../design/adr/053-native-code-only-where-java-cannot.md) | Native code only where Java cannot do the job; why the base is glibc |
| [ADR-035](../design/adr/035-wave-8-is-survival-not-distribution.md) | Node ownership of state, and the standby the chart deploys |
| [ADR-045](../design/adr/045-cluster-mode-assigns-queries-not-rows.md) | The clustering this chart does not do |
