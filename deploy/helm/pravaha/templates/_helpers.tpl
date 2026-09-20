{{/*
Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see the LICENSE file in the root of this repository.
*/}}

{{- define "pravaha.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "pravaha.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "pravaha.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "pravaha.labels" -}}
helm.sh/chart: {{ include "pravaha.chart" . }}
{{ include "pravaha.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: pravaha
{{- end -}}

{{- define "pravaha.selectorLabels" -}}
app.kubernetes.io/name: {{ include "pravaha.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "pravaha.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "pravaha.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "pravaha.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}

{{/*
The rendered application.yaml, so the ConfigMap and the checksum annotation are built from one
expression rather than two that can disagree.

Keys the chart OWNS are merged over what the operator wrote, not under it: the mount path, the
spill tier, the dead-letter queue and the TLS paths follow from other values in this file, and a
config: block that quietly disagreed with persistence.mountPath would put the state somewhere the
volume is not.
*/}}
{{- define "pravaha.applicationYaml" -}}
{{- $cfg := deepCopy .Values.config -}}
{{- $pravaha := default (dict) $cfg.pravaha -}}
{{- $mount := .Values.persistence.mountPath -}}
{{- if .Values.dlq.enabled -}}
{{-   $_ := set $pravaha "dlq" (dict "directory" (printf "%s/dlq" $mount)) -}}
{{- end -}}
{{- if .Values.spill.enabled -}}
{{-   $state := default (dict) $pravaha.state -}}
{{-   $_ := set $state "spill" (dict "enabled" true "directory" (printf "%s/spill" $mount) "max-bytes" .Values.spill.maxBytes) -}}
{{-   $_ := set $pravaha "state" $state -}}
{{- end -}}
{{- if .Values.tls.enabled -}}
{{-   $flight := default (dict) $pravaha.flight -}}
{{-   $_ := set $flight "tls" (dict "certificate" (printf "%s/%s" .Values.tls.mountPath .Values.tls.certKey) "key" (printf "%s/%s" .Values.tls.mountPath .Values.tls.keyKey)) -}}
{{-   $_ := set $pravaha "flight" $flight -}}
{{- end -}}
{{- $_ := set $cfg "pravaha" $pravaha -}}
{{- toYaml $cfg -}}
{{- end -}}

{{/*
Everything that must be true before a single manifest is rendered.

A chart that renders a broken deployment and lets the cluster discover it has moved the failure
from `helm template`, where it is one message, to a pod crash loop, where it is an investigation.
*/}}
{{- define "pravaha.validate" -}}
{{- if ne (int .Values.replicaCount) 1 -}}
{{-   fail (printf "pravaha: replicaCount is %v. This chart runs ONE node. Multi-node execution is on hold (ADR-045: a node owns whole computations, not rows) and a node refuses to serve PARTITIONED with PRV-9002; two replicas would also be two nodes claiming one state directory, which is PRV-4003. Run one release per node if you need several, each with its own storage." .Values.replicaCount) -}}
{{- end -}}
{{- if and .Values.tls.enabled (not .Values.tls.existingSecret) -}}
{{-   fail "pravaha: tls.enabled is true and tls.existingSecret is empty. This chart references secrets, it never creates them -- put the kubernetes.io/tls Secret in the namespace and name it here." -}}
{{- end -}}
{{- if and .Values.standby.enabled (not .Values.persistence.existingClaim) -}}
{{-   fail "pravaha: standby.enabled needs persistence.existingClaim. A standby watches the PRIMARY's checkpoint directory and promotes itself from it, so the two pods must mount one ReadWriteMany claim; a StatefulSet volumeClaimTemplate would give them one each and the standby would watch an empty directory for ever." -}}
{{- end -}}
{{- if and .Values.standby.enabled (not .Values.nodeId) -}}
{{-   fail "pravaha: standby.enabled needs an explicit nodeId. A standby is configured with the SAME pravaha.node.id as the primary, on purpose (ADR-035) -- that is how StateOwnership tells 'our node, claim expired' from 'another node'. Defaulting it to the pod name would give the two pods different ids and the standby would never promote." -}}
{{- end -}}
{{- if and .Values.standby.enabled (not .Values.persistence.enabled) -}}
{{-   fail "pravaha: standby.enabled needs persistence.enabled. There would be nothing to watch and nothing to resume from -- which the node itself refuses at startup." -}}
{{- end -}}
{{- if and .Values.spill.enabled (not .Values.persistence.enabled) -}}
{{-   fail "pravaha: spill.enabled with no persistence. The overflow tier would spill onto the container filesystem, which is gone when the pod is -- and on a read-only root filesystem it cannot even be written." -}}
{{- end -}}
{{- if and .Values.persistence.existingClaim .Values.spill.separateVolume -}}
{{-   fail "pravaha: spill.separateVolume with persistence.existingClaim is not supported. Name a second claim yourself and mount it through extraConfigMounts, or turn separateVolume off." -}}
{{- end -}}
{{- if and .Values.podDisruptionBudget.enabled (hasKey .Values.podDisruptionBudget "minAvailable") -}}
{{-   fail "pravaha: podDisruptionBudget.minAvailable on a one-replica release makes `kubectl drain` block for ever. Use maxUnavailable: 1." -}}
{{- end -}}
{{- if not .Values.config.pravaha -}}
{{-   fail "pravaha: config.pravaha is empty. The shipped defaults are the ones the node will run on; emptying them is almost never what was meant." -}}
{{- end -}}
{{- end -}}
