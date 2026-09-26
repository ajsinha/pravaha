{{/*
Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see the LICENSE file in the root of this repository.

One pod spec, used by the node and by the standby, because two that drift apart is how a standby
comes up with different flags from the thing it is standing in for.

Called as:  include "pravaha.podSpec" (dict "ctx" $ "role" "node")
            include "pravaha.podSpec" (dict "ctx" $ "role" "standby")
*/}}

{{- define "pravaha.httpPort" -}}
{{- dig "server" "port" 8080 (default (dict) .Values.config.spring) -}}
{{- end -}}

{{- define "pravaha.flightPort" -}}
{{- dig "flight" "port" 9090 (default (dict) .Values.config.pravaha) -}}
{{- end -}}

{{- define "pravaha.podSpec" -}}
{{- $ctx := .ctx -}}
{{- $role := .role -}}
{{- $standby := eq $role "standby" -}}
{{- $v := $ctx.Values -}}
{{- $http := include "pravaha.httpPort" $ctx -}}
{{- $flight := include "pravaha.flightPort" $ctx -}}
serviceAccountName: {{ include "pravaha.serviceAccountName" $ctx }}
{{- with $v.image.pullSecrets }}
imagePullSecrets:
  {{- range . }}
  - name: {{ . }}
  {{- end }}
{{- end }}
securityContext:
  {{- toYaml $v.podSecurityContext | nindent 2 }}
{{- with $v.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with $v.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with $v.affinity }}
affinity:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with $v.topologySpreadConstraints }}
topologySpreadConstraints:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with $v.priorityClassName }}
priorityClassName: {{ . }}
{{- end }}
# Shutdown reverses startup: stop accepting, let go of the queries, then release the state claim.
# A grace period shorter than that leaves the ownership marker to expire on its lease instead,
# and the next start waits out the lease before it can take its own directory back.
terminationGracePeriodSeconds: {{ $v.terminationGracePeriodSeconds }}
containers:
  - name: pravaha
    image: {{ include "pravaha.image" $ctx | quote }}
    imagePullPolicy: {{ $v.image.pullPolicy }}
    securityContext:
      {{- toYaml $v.containerSecurityContext | nindent 6 }}
    {{- with $v.extraArgs }}
    args:
      {{- toYaml . | nindent 6 }}
    {{- end }}
    ports:
      - name: http
        containerPort: {{ $http }}
        protocol: TCP
      - name: flight
        containerPort: {{ $flight }}
        protocol: TCP
    env:
      # The node CLAIMS its state directories under this id and finds its own checkpoints by it,
      # so it must be stable across a reschedule and must NOT be the pod IP. A StatefulSet pod
      # name is both. A standby uses the primary's id, deliberately (ADR-035).
      - name: PRAVAHA_NODE_ID
        {{- if $v.nodeId }}
        value: {{ $v.nodeId | quote }}
        {{- else }}
        valueFrom:
          fieldRef:
            fieldPath: metadata.name
        {{- end }}
      {{- if $standby }}
      - name: PRAVAHA_STANDBY_ENABLED
        value: "true"
      {{- end }}
      {{- if $v.javaOpts }}
      # Replaces the image's own defaults wholesale. The Arrow --add-opens are not in here; they
      # are on bin/pravaha-server's exec line, ahead of this, so they cannot be lost this way.
      - name: PRAVAHA_JAVA_OPTS
        value: {{ $v.javaOpts | quote }}
      {{- end }}
      # Lowest precedence first, and a location named later wins. The image sets the first two;
      # this adds the mounted secret, which must therefore come last.
      - name: SPRING_CONFIG_ADDITIONAL_LOCATION
        value: "optional:file:/opt/pravaha/defaults/,optional:file:/opt/pravaha/conf/{{ if $v.auth.existingSecret }},optional:file:/opt/pravaha/secrets/auth/{{ end }}{{ range $v.extraConfigMounts }},optional:file:{{ .mountPath }}/{{ end }}"
      {{- with $v.extraEnv }}
      {{- toYaml . | nindent 6 }}
      {{- end }}
    {{- with $v.envFrom }}
    envFrom:
      {{- toYaml . | nindent 6 }}
    {{- end }}
    # Three probes, three different questions, and conflating any two of them is a restart loop.
    #
    # startup  -- "has it finished coming up?" A node recovering a large checkpoint is not a hung
    #             node, so liveness does not run until this passes.
    # liveness -- "is the JVM alive?" Deliberately blind to whether the engine can serve: a node
    #             whose one query failed is not a node to restart.
    # readiness-- "can a client be served?" The engine indicator is IN this group (the server's
    #             application.yaml puts it there), so it is red while Flight is not listening.
    #             deploy/docker/smoke.sh proves that by taking Flight away and watching it go red.
    startupProbe:
      httpGet:
        path: /actuator/health/liveness
        port: http
      periodSeconds: {{ $v.probes.startup.periodSeconds }}
      failureThreshold: {{ $v.probes.startup.failureThreshold }}
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: http
      periodSeconds: {{ $v.probes.liveness.periodSeconds }}
      failureThreshold: {{ $v.probes.liveness.failureThreshold }}
      timeoutSeconds: {{ $v.probes.liveness.timeoutSeconds }}
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: http
      periodSeconds: {{ $v.probes.readiness.periodSeconds }}
      failureThreshold: {{ $v.probes.readiness.failureThreshold }}
      timeoutSeconds: {{ $v.probes.readiness.timeoutSeconds }}
    resources:
      {{- if $standby }}
      {{- toYaml $v.standby.resources | nindent 6 }}
      {{- else }}
      {{- toYaml $v.resources | nindent 6 }}
      {{- end }}
    volumeMounts:
      - name: config
        mountPath: /opt/pravaha/conf
        readOnly: true
      {{- if $v.auth.existingSecret }}
      - name: auth
        mountPath: /opt/pravaha/secrets/auth
        readOnly: true
      {{- end }}
      {{- if $v.tls.enabled }}
      - name: tls
        mountPath: {{ $v.tls.mountPath }}
        readOnly: true
      {{- end }}
      {{- range $v.extraConfigMounts }}
      - name: {{ .name }}
        mountPath: {{ .mountPath }}
        readOnly: true
      {{- end }}
      {{- if $v.persistence.enabled }}
      - name: data
        mountPath: {{ $v.persistence.mountPath }}
      {{- end }}
      {{- if and $v.spill.enabled $v.spill.separateVolume }}
      - name: spill
        mountPath: {{ $v.persistence.mountPath }}/spill
      {{- end }}
      # The root filesystem is read-only, and the JVM still needs somewhere to put hsperfdata and
      # whatever a library decides to unpack. Memory-backed and small on purpose: if something
      # starts writing data here it should fail rather than fill the node.
      - name: tmp
        mountPath: /tmp
volumes:
  - name: config
    configMap:
      name: {{ include "pravaha.fullname" $ctx }}
  {{- if $v.auth.existingSecret }}
  # Projected as application.yaml so Spring reads it as one more config file: a directory in
  # spring.config.additional-location is searched for application.yaml and nothing else, so the
  # Secret's own key name would be ignored in silence.
  - name: auth
    secret:
      secretName: {{ $v.auth.existingSecret }}
      items:
        - key: {{ $v.auth.key }}
          path: application.yaml
  {{- end }}
  {{- if $v.tls.enabled }}
  - name: tls
    secret:
      secretName: {{ $v.tls.existingSecret }}
      defaultMode: 0400
  {{- end }}
  {{- range $v.extraConfigMounts }}
  - name: {{ .name }}
    {{- if .configMap }}
    configMap:
      name: {{ .configMap }}
    {{- else }}
    secret:
      secretName: {{ .secret }}
    {{- end }}
  {{- end }}
  - name: tmp
    emptyDir:
      medium: Memory
      sizeLimit: 64Mi
  {{- if and $v.persistence.enabled $v.persistence.existingClaim }}
  - name: data
    persistentVolumeClaim:
      claimName: {{ $v.persistence.existingClaim }}
  {{- end }}
{{- end -}}
