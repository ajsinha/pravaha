#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The chart's test. `helm lint`, `helm template` over every scenario in pravaha/ci/, and then the
# assertions that matter -- because a chart that renders is not a chart that is right.
#
#   deploy/helm/test.sh                 # needs helm on the PATH
#   HELM=/path/to/helm deploy/helm/test.sh
#
# Six of the checks are SEEDS: they render a values file that must be REFUSED, and fail if it
# renders. A guard nobody has watched refuse is a guard nobody knows works.
#
# Helm is not installed on the development machine this was written on. Every run recorded in
# docs/DEPLOYMENT.md used a helm 3.16.3 binary fetched for the purpose; the CI job installs it
# the same way.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
chart="$here/pravaha"
helm="${HELM:-helm}"

if ! command -v "$helm" >/dev/null 2>&1; then
  cat >&2 <<EOF
test.sh: no helm on the PATH.

  Install it, or point at a binary:  HELM=/path/to/helm deploy/helm/test.sh

  This is not a skip. A chart is text until something parses it, and a test that passes by not
  running is the reason this file says so out loud rather than exiting 0.
EOF
  exit 2
fi

pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok:   $*"; pass=$((pass + 1)); }

render() {
  local name="$1"; shift
  "$helm" template "$name" "$chart" "$@" 2>&1
}

# A scenario that MUST be refused, and the fragment of the refusal that must be in the message --
# because "it failed" is not the same as "it failed for the reason the guard exists for".
refuses() {
  local why="$1"; local expect="$2"; shift 2
  local out
  if out="$("$helm" template refused "$chart" "$@" 2>&1)"; then
    fail "$why -- it RENDERED. The guard in _helpers.tpl is not doing anything:
$(head -20 <<<"$out")"
  fi
  grep -q "$expect" <<<"$out" || fail "$why -- refused, but not for the stated reason. Wanted '$expect', got:
$out"
  ok "refused: $why"
}

has()     { grep -q -- "$2" <<<"$1" || fail "$3"; }
hasnt()   { grep -q -- "$2" <<<"$1" && fail "$3"; return 0; }

echo "test.sh: $("$helm" version --short)"
echo

# ------------------------------------------------------------------ lint

"$helm" lint "$chart" >/dev/null || fail "helm lint"
ok "helm lint (defaults)"

for values in "$chart"/ci/*-values.yaml; do
  "$helm" lint "$chart" --values "$values" >/dev/null \
    || fail "helm lint --values $(basename "$values")"
  ok "helm lint $(basename "$values")"
done

# ------------------------------------------------------------------ defaults

d="$(render pravaha)"
has "$d" 'kind: StatefulSet'      "the node is not a StatefulSet. It has to be: a node claims its state
directories by node id and a Deployment gives it a new name every restart (ADR-035)."
hasnt "$d" 'kind: Deployment'     "something renders as a Deployment"
has "$d" 'replicas: 1'            "replicas is not 1"
has "$d" 'volumeClaimTemplates'   "no volumeClaimTemplate: the state would live in the pod"
has "$d" 'runAsNonRoot: true'     "the pod may run as root"
has "$d" 'runAsUser: 10001'       "runAsUser is not the uid the image builds"
has "$d" 'readOnlyRootFilesystem: true' "the root filesystem is writable"
has "$d" 'fsGroup: 10001'         "no fsGroup: a fresh volume would not be writable by the node"
has "$d" '/actuator/health/liveness'  "no liveness probe"
has "$d" '/actuator/health/readiness' "no readiness probe"
has "$d" 'checksum/config'        "no config checksum: an upgrade that only changes config would not roll the pod"
has "$d" 'publishNotReadyAddresses: true' "the headless Service hides a starting pod"
has "$d" 'automountServiceAccountToken: false' "the ServiceAccount token is mounted into a node that calls no API"
ok "defaults: StatefulSet, one replica, non-root 10001, read-only rootfs, both probes, a volume"

# The client Service must not be wired to readiness-blind endpoints, and the two probes must be
# different paths. If they were the same, a node still restoring state would be restarted.
liveness_count="$(grep -c 'health/liveness' <<<"$d")"
readiness_count="$(grep -c 'health/readiness' <<<"$d")"
[[ "$liveness_count" -ge 2 && "$readiness_count" -ge 1 ]] \
  || fail "startup+liveness and readiness are not both wired: $liveness_count / $readiness_count"
ok "startup and liveness on the liveness probe, readiness on its own"

# The image's default heap share must survive when javaOpts is not set: the chart must not set
# PRAVAHA_JAVA_OPTS to empty, which would blank it.
hasnt "$d" 'name: PRAVAHA_JAVA_OPTS' "the chart sets PRAVAHA_JAVA_OPTS with javaOpts unset, blanking the image's -XX:MaxRAMPercentage"
ok "javaOpts unset leaves the image's own JVM defaults alone"

# ------------------------------------------------------------------ the full scenario

f="$(render pravaha --values "$chart/ci/full-values.yaml")"
has "$f" 'secretName: pravaha-tokens'      "the token Secret is not mounted"
has "$f" 'path: application.yaml'          "the token Secret is not projected as application.yaml, so Spring will not read it"
has "$f" 'secretName: pravaha-flight-tls'  "the TLS Secret is not mounted"
has "$f" '/etc/pravaha-tls/tls.crt'        "pravaha.flight.tls.certificate does not point at the mounted file"
has "$f" '/etc/pravaha-tls/tls.key'        "pravaha.flight.tls.key does not point at the mounted file"
has "$f" 'directory: /var/lib/pravaha/dlq' "dlq.enabled did not set pravaha.dlq.directory"
has "$f" 'max-bytes: 20GB'                 "spill.maxBytes did not reach pravaha.state.spill.max-bytes"
has "$f" 'storageClassName: local-nvme'    "the separate spill claim did not take its StorageClass"
has "$f" 'kind: PodDisruptionBudget'       "no PodDisruptionBudget"
has "$f" 'maxUnavailable: 1'               "the PDB is not maxUnavailable: 1"
has "$f" 'kind: ServiceMonitor'            "no ServiceMonitor"
has "$f" '/actuator/prometheus'            "the ServiceMonitor does not scrape the Prometheus endpoint"
has "$f" 'pravaha/pravaha-server:1.2.3'    "image.tag was ignored"
has "$f" 'MaxRAMPercentage=60'             "javaOpts was ignored"
has "$f" 'PRAVAHA_LANE_MULTIPLEX_ENABLED'  "extraEnv was ignored"
has "$f" 'name: regcred'                   "the image pull secret was ignored"
has "$f" 'event-time: event_time'          "a stream declared in config.pravaha did not reach the ConfigMap"
ok "full scenario: secrets referenced, TLS paths wired, spill and DLQ on, PDB, ServiceMonitor"

# The secret's config location must come AFTER /etc/pravaha, or the ConfigMap would win over it.
loc="$(grep -A1 'SPRING_CONFIG_ADDITIONAL_LOCATION' <<<"$f" | grep 'value:' | head -1)"
[[ "$loc" == *"/etc/pravaha/,optional:file:/etc/pravaha-auth/"* ]] \
  || fail "the token Secret's location is not last, so the ConfigMap would override the tokens: $loc"
ok "the mounted Secret has the last word in the config location list"

# And no credential may end up in the ConfigMap, which anything that can read ConfigMaps can
# read. A PATH to the key is not a credential and is supposed to be here (asserted above); PEM
# material, a token table, or the Secret's own contents are not.
cm="$(awk '/kind: ConfigMap/,/^---/' <<<"$f")"
hasnt "$cm" 'BEGIN '          "PEM material is in the ConfigMap"
hasnt "$cm" 'pravaha-tokens'  "the token Secret's contents or name reached the ConfigMap"
hasnt "$cm" 'tokens:'         "a pravaha.security.tokens table is in the ConfigMap; it belongs in a Secret"
ok "the ConfigMap carries paths, never credentials"

# ------------------------------------------------------------------ the standby

s="$(render pravaha --values "$chart/ci/standby-values.yaml")"
has "$s" 'name: pravaha-standby'      "the standby StatefulSet was not rendered"
has "$s" 'PRAVAHA_STANDBY_ENABLED'    "the standby is not told it is one"
has "$s" 'claimName: pravaha-state'   "the standby does not mount the primary's claim"
hasnt "$s" 'volumeClaimTemplates'     "the standby got a claim of its own, so it would watch an empty directory"
ids="$(grep -A1 'PRAVAHA_NODE_ID' <<<"$s" | grep 'value:' | sort -u | wc -l)"
[[ "$ids" == "1" ]] || fail "the standby and the primary do not share one node id; the standby would never promote"
ok "standby: same node id as the primary, on the primary's claim, no claim of its own"

# ------------------------------------------------------------------ minimal

m="$(render pravaha --values "$chart/ci/minimal-values.yaml")"
hasnt "$m" 'volumeClaimTemplates'  "persistence.enabled=false still rendered a claim"
hasnt "$m" 'kind: ServiceAccount'  "serviceAccount.create=false still rendered one"
has "$m" 'kind: StatefulSet'       "the node disappeared"
ok "minimal: no claim, no ServiceAccount, still a node"

# ------------------------------------------------------------------ the seeds

refuses "two replicas"  "ADR-045"            --set replicaCount=2
refuses "TLS on with no Secret named" "never creates them" --set tls.enabled=true
refuses "a standby with no shared claim"  "ReadWriteMany" \
  --set standby.enabled=true --set nodeId=n1
refuses "a standby with no explicit node id" "SAME pravaha.node.id" \
  --set standby.enabled=true --set persistence.existingClaim=c
refuses "spilling with nowhere to spill" "gone when the pod is" \
  --set spill.enabled=true --set persistence.enabled=false
refuses "a PDB with minAvailable on one replica" "block for ever" \
  --set podDisruptionBudget.enabled=true --set podDisruptionBudget.minAvailable=1

echo
echo "test.sh: $pass checks PASSED"
