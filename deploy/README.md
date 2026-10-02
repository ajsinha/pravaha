# deploy/

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

Everything a deployment needs and nothing the engine needs. **The page to read is
[`../docs/operations/DEPLOYMENT.md`](../docs/operations/DEPLOYMENT.md)**; this is the map of the directory.

```
docker/     the container images and the compose stack (docs/operations/RUNNING_IN_DOCKER.md)
  Dockerfile          the engine: glibc JRE 25, the only one (ADR-053, ADR-061), PRAVAHA_HOME=/opt/pravaha, any uid, over
                      artefacts the reactor already built (ADR-047)
  build.sh            stage the launcher and the jar and build.   --tag, --push
  smoke.sh            eleven steps against a REAL container.     --image, --keep
  console/            the console's image (python:3.13-slim) and its build.sh
  compose/            engine + console + Kafka as the invoking user; profiles seed, cdc, stores,
                      observability, tools. tools/docker-env.sh prepares it
  test/               the test runner image tools/docker-test.sh uses (Maven, JDK 25, Python 3)

helm/       the Kubernetes chart
  pravaha/            one node, as a StatefulSet. Values documented one line each in values.yaml
  pravaha/ci/         scenario values: everything on, the standby, and a node with nothing
  pravaha/files/      pravaha-rules.yaml, a copy of observability/'s, for prometheusRule.enabled
  test.sh             22 checks: helm lint + template, eight refusals, HELMNAME-1 names. Needs helm

observability/  watching a node: import, load, point at the node
  grafana/            four dashboards -- node overview, query drill-down, alerts and catalogue,
                      the assistant. Each has a datasource variable; the node ones $node and $query
  prometheus/         pravaha-rules.yaml: the ten query rules and the alert, catalogue and assistant
                      ones. Identical to the "Metrics and alerts" help topic's (a test says so)

release/    versioning, and as much of a release as this repository can run
  dist.sh             the engine as a distribution: a PRAVAHA_HOME when unpacked (distribution/)
  version.sh          print the reactor's version. One reader, so three cannot disagree
  set-version.sh      set or --check it across 40 poms, 2 wheels and the chart
  release.sh          the procedure, --dry-run first. Publishes NOTHING, and says why
  test.sh             8 checks on a throwaway copy of the tree. Never edits this one

ci/         things the workflows call
  check-workflows.py  every workflow parses and is wired (trigger, timeouts, pinned actions)
  assert-suite-ran.sh a suite that "passed" having executed nothing must not be green
  test.sh             15 checks, eleven of them seeds
```

Run every test here in one line:

```bash
deploy/ci/test.sh && deploy/release/test.sh && deploy/helm/test.sh \
  && deploy/docker/build.sh --tag pravaha:local && deploy/docker/smoke.sh --image pravaha:local
```

The last two need a Docker daemon; `deploy/helm/test.sh` needs `helm`, and says so rather than
skipping if it is absent.

The chart's `serviceMonitor.enabled` and `prometheusRule.enabled` (both off: the Prometheus
Operator's CRDs may not be installed) scrape the node and install the rules. Structured logs
(`pravaha.logging.format: json`) and OpenTelemetry traces (`pravaha.tracing.*`) are set in the node's
configuration -- `config.pravaha` in the chart's values. The console's help topic *Observability* has
the scrape config, the Loki and collector snippets, and what each span is.
