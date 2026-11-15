{{- define "idli.fullname" -}}
{{- if eq .Release.Name .Chart.Name -}}
{{- .Chart.Name -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "idli.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- /* Flux appends the OCI digest as +build-metadata to .Chart.Version;
"+" is invalid in label values, hence the replace (as in helm create). */}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end -}}

{{- define "idli.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- /* The image reference for one component's .image values: repository@digest
     when a digest is given, repository:tag otherwise. Deliberately not
     repository:tag@digest — containerd accepts that form, other runtimes have
     refused it. A mistyped digest fails here, at render time, instead of as an
     InvalidImageName at rollout (the usual slip: copying it without its
     "sha256:" prefix). */ -}}
{{- define "idli.image" -}}
{{- if .digest -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" .digest) -}}
{{- fail (printf "image.digest must be \"sha256:\" followed by 64 hex characters, as the registry shows it — got %q" .digest) -}}
{{- end -}}
{{- printf "%s@%s" .repository .digest -}}
{{- else -}}
{{- printf "%s:%s" .repository .tag -}}
{{- end -}}
{{- end -}}

{{- /* Pod-level hardening, the same for both components (chart 0.6.0).
     - No service-account token: neither process talks to the cluster API, so
       the credential Kubernetes would mount by default is only something to
       steal.
     - runAsNonRoot: the kubelet refuses to start a container whose image runs
       as root — or names its user instead of numbering it, which it cannot
       verify. Both images declare a numeric USER; the chart deliberately sets
       no runAsUser, so the uid stays the image's business.
     - seccomp RuntimeDefault: the container runtime's syscall filter, which a
       pod does not get unless it asks (or the kubelet is configured to default
       it). */ -}}
{{- define "idli.podHardening" -}}
automountServiceAccountToken: false
securityContext:
  runAsNonRoot: true
  seccompProfile:
    type: RuntimeDefault
{{- end -}}

{{- /* Container-level hardening, the same for both components (chart 0.6.0).
     Together with idli.podHardening this is what the Kubernetes "restricted"
     Pod Security Standard asks for, plus the read-only root filesystem, which
     it does not. Each container gets an emptyDir on /tmp for the little it
     does write. */ -}}
{{- define "idli.containerHardening" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop:
    - ALL
{{- end -}}
