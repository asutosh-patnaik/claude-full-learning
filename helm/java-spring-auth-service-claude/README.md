# java-spring-auth-service-claude Helm chart

Parallel to the plain `k8s/` manifests (kept as-is, untouched) — this is the recommended path for
anything beyond the original minimal demo: per-environment values, Ingress, HPA, and observability
hooks the plain manifests don't have. See the repo's `docs/` directory for full setup/deployment/
rollback/troubleshooting walkthroughs; this file covers just the chart's own usage.

## Install

Every install needs three files layered together: the chart defaults (`values.yaml`, implicit), an
environment overlay, and an **untracked** secrets overlay (real values are never committed — see
`values-secrets.yaml.example`):

```bash
cp values-secrets.yaml.example values-dev.secrets.yaml   # fill in real values, never commit this file
helm upgrade --install java-spring-auth-service-claude . \
  -f values-dev.yaml -f values-dev.secrets.yaml \
  -n java-spring-auth-service-claude-dev --wait --timeout 5m
```

Swap `values-dev.yaml`/`values-dev.secrets.yaml` for `values-test.yaml`/`values-test.secrets.yaml` or
`values-production.yaml`/`values-production.secrets.yaml` for the other environments. In practice, use
`scripts/deploy.sh <env>` instead of calling `helm` directly — it also resolves the image, waits for
rollout, health-checks, and smoke-tests (see `docs/deployment.md`).

## Values reference

See `values.yaml`'s inline comments for the full schema. The three environment overlays intentionally
change only what genuinely differs for a local demo — replica count, ingress host, log level, and
(production only) resource sizing + autoscaling. Nothing else is overridden per environment.

## Toggles worth knowing about

- `mongodb.enabled: false` + `config.mongoUri: <external URI>` — use an external Mongo instead of the
  chart's own unauthenticated, no-PVC Mongo Deployment.
- `image.pullSecretName` — set once the GHCR package needs a `kubernetes.io/dockerconfigjson` Secret
  (private package fallback — see `docs/deployment.md`).
- `serviceMonitor.enabled` / `grafanaDashboard.enabled` — both off by default; turn on once
  `scripts/observability.sh` has installed kube-prometheus-stack into the cluster.

## Verify a render without installing

```bash
helm lint .
helm template . -f values-dev.yaml -f values-dev.secrets.yaml
```
