# Deployment

Assumes [setup.md](setup.md)'s prerequisites and `./scripts/start.sh` have already run.

## The `scripts/deploy.sh` sequence

```bash
./scripts/deploy.sh <dev|test|production> [--tag <tag>] [--source ghcr|local] [--local-port <port>]
```

In order:

1. **Validates** the environment argument and that `helm/java-spring-auth-service-claude/values-<env>.secrets.yaml`
   exists (see [setup.md](setup.md)'s Secrets section - it errors out immediately with the exact copy
   command if it's missing, rather than deploying with empty/broken secrets).
2. **Resolves the image**:
   - `--source ghcr` (default): the cluster pulls `ghcr.io/asutosh-patnaik/java-spring-auth-service-claude:<tag>`
     directly. If `GHCR_PULL_PAT` and `GHCR_PULL_USERNAME` are set in your shell, an imagePullSecret
     is created/updated automatically for a private package (see setup.md's GHCR section) - the
     credential is read only from the environment, never written to a file or passed as a literal
     `--set` argument (which would land in shell history).
   - `--source local`: builds `java-spring-auth-service-claude:<tag>` from this checkout and `minikube image
     load`s it - mirrors `k8s/app.yaml`'s original flow, for testing an unpushed change.
3. **`helm upgrade --install`** with `values.yaml` (defaults) + `values-<env>.yaml` (environment
   overlay) + `values-<env>.secrets.yaml` (untracked secrets), `--wait --timeout 5m`.
4. **`kubectl rollout status`** as an explicit, clearly-logged check alongside Helm's own `--wait`.
5. **Health check**: a `kubectl port-forward` to the Service (not Ingress - driver-independent,
   works identically on every OS/minikube driver) plus a retry loop against `/actuator/health`.
6. **Smoke tests**: the existing `postman/java-spring-auth-service-claude.postman_collection.json` via Newman,
   against that same port-forward. Each run registers a fresh user (the collection's username is
   `Date.now()`-based - see CLAUDE.md's Postman gotcha) - fine for a smoke test, just not idempotent
   against one fixed account.
7. **On any failure from step 3 onward**: automatic `helm rollback` to the revision that was live
   before this run, then a non-zero exit pointing at [rollback.md](rollback.md) and
   [troubleshooting.md](troubleshooting.md). See "Rollback" below for what this does and doesn't cover.
8. **On success**: prints the release/namespace/tag and how to reach it again later.

## Values-file layering

```
values.yaml (chart defaults)
  → values-<env>.yaml (dev/test/production overlay - replica count, ingress host, log level, and
    for production only: bumped resources + autoscaling)
    → values-<env>.secrets.yaml (untracked - real JWT key material)
```

Later files win. `deploy.sh` passes all three via `-f`, in that order, plus `--set image.tag=<tag>`
(and `image.repository`/`pullPolicy` for `--source local`).

Worked example, dev:

```bash
cp helm/java-spring-auth-service-claude/values-secrets.yaml.example helm/java-spring-auth-service-claude/values-dev.secrets.yaml
# fill in real values in values-dev.secrets.yaml
./scripts/deploy.sh dev --source local
```

Worked example, deploying a specific GHCR-published tag to test:

```bash
cp helm/java-spring-auth-service-claude/values-secrets.yaml.example helm/java-spring-auth-service-claude/values-test.secrets.yaml
./scripts/deploy.sh test --tag sha-<commit-sha-from-a-cd.yml-run>
```

## GHCR package visibility

GHCR packages default to **private**, even on a public repo, until you change that once in the
GitHub UI. Until you do (or if you'd rather keep it private), `--source ghcr` needs the
`GHCR_PULL_PAT`/`GHCR_PULL_USERNAME` pair from [setup.md](setup.md). To make it public instead: repo
→ Packages tab → the `java-spring-auth-service-claude` package → Package settings → Danger Zone → Change
visibility. This can't be scripted without extra token permissions, so it's a deliberate one-time
manual step, not something `deploy.sh` does for you.

## Turning on observability for the app

Off by default (`serviceMonitor.enabled: false`, `grafanaDashboard.enabled: false` in `values.yaml`).
Once `scripts/observability.sh` has installed the monitoring stack:

```bash
./scripts/deploy.sh dev --source ghcr \
  --tag <tag>
# then, or combined into the same run by editing values-dev.yaml:
helm upgrade java-spring-auth-service-claude helm/java-spring-auth-service-claude \
  -f helm/java-spring-auth-service-claude/values-dev.yaml -f helm/java-spring-auth-service-claude/values-dev.secrets.yaml \
  --set serviceMonitor.enabled=true --set grafanaDashboard.enabled=true \
  -n java-spring-auth-service-claude-dev
```

Verify: `kubectl port-forward -n monitoring svc/monitoring-kube-prometheus-prometheus 9090:9090` and
check the Targets page for `serviceMonitor/java-spring-auth-service-claude-dev/java-spring-auth-service-claude/0` showing
`up`; `kubectl port-forward -n monitoring svc/monitoring-grafana 3000:80` and look for a
"java-spring-auth-service-claude" dashboard (see [setup.md](setup.md)'s "Accessing the dashboards" section for
the Grafana login and the Kubernetes dashboard too). If either doesn't show up, see
[troubleshooting.md](troubleshooting.md) - both had real, non-obvious causes during development.

## Rollback

`deploy.sh` already rolls back automatically on its own failures (step 7 above). For rolling back a
deploy that *succeeded* but turned out to be bad, or to just inspect history, see
[rollback.md](rollback.md).
