# deploy/

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

Everything a deployment needs and nothing the engine needs. **The page to read is
[`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md)**; this is the map of the directory.

```
docker/     the engine node's container image
  Dockerfile          glibc JRE 21 (ADR-053), uid 10001, over artefacts the reactor already built (ADR-047)
  conf/               the image's own application.yaml -- the lowest of three config layers
  build.sh            stage an ~80 MB context and build.   --tag, --push
  smoke.sh            ten steps against a REAL container.  --image, --keep

helm/       the Kubernetes chart
  pravaha/            one node, as a StatefulSet. Values documented one line each in values.yaml
  pravaha/ci/         scenario values: everything on, the standby, and a node with nothing
  test.sh             helm lint + template + 18 assertions, six of them refusals. Needs helm

release/    versioning, and as much of a release as this repository can run
  version.sh          print the reactor's version. One reader, so three cannot disagree
  set-version.sh      set or --check it across 37 poms, 2 wheels and the chart
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
