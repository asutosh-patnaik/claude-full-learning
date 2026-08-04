# Setup

Everything here runs locally and is free. Nothing requires an AWS/cloud account.

## Prerequisites

| Tool | Used for | Check |
|---|---|---|
| Docker | Building images, running `docker-compose.yml` | `docker version` |
| minikube | Local Kubernetes cluster | `minikube version` |
| kubectl | Talking to the cluster | `kubectl version --client` |
| Helm 3+ (chart format v2) | Installing the app and the observability stack | `helm version` |
| Node.js / npx | Newman smoke tests in `scripts/deploy.sh` and `scripts/test.sh --smoke` | `node --version` |
| `gh` CLI (optional) | GHCR package visibility, triggering `cd.yml` manually | `gh auth status` |

Install Helm if `helm version` doesn't work:

```bash
# macOS
brew install helm

# Linux
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

If Newman/Node isn't something you want installed, `scripts/deploy.sh` still runs everything else -
only the smoke-test step needs it (see [deployment.md](deployment.md)).

## One-time cluster setup

```bash
./scripts/start.sh
```

Starts minikube (no-op if already running) and enables the `ingress` and `metrics-server` addons -
both required by the Helm chart (`Ingress` resources need the former; `HorizontalPodAutoscaler`
needs the latter or it shows `<unknown>` forever). Safe to re-run any time.

## Optional: observability stack

```bash
./scripts/observability.sh
```

Installs Prometheus + Grafana + Alertmanager (`kube-prometheus-stack`) and Loki + Promtail into a
`monitoring` namespace. This is separate from `start.sh` because it's heavier and optional - the app
deploys and runs fine without it; you just won't get metrics/dashboards/log aggregation until you run
this once per cluster. See [deployment.md](deployment.md) for how to then turn on scraping/dashboard
for the app itself.

## Ingress host access (dev/test/production overlays all enable Ingress by default)

`scripts/deploy.sh`'s own health checks and smoke tests use `kubectl port-forward`, not Ingress, so
Ingress isn't required for the deploy pipeline itself to work. It's there for manually browsing to
the app by hostname. To use it:

```bash
echo "$(minikube ip)  claude-full-learning.dev.local" | sudo tee -a /etc/hosts
```

(swap the host for whichever environment's `values-<env>.yaml` you're using). On some minikube
driver/OS combinations you'll also need `minikube tunnel` running in a separate terminal for the
Ingress controller's address to actually be reachable at `minikube ip` - if a plain `curl` to the
host above hangs or connection-refuses, try that.

## GHCR authentication (only needed for a private package)

`cd.yml` pushes using the repo's own `GITHUB_TOKEN` - no setup needed there. Pulling from a **private**
GHCR package locally needs a Personal Access Token with `read:packages`, exported as:

```bash
export GHCR_PULL_PAT=<your PAT>
export GHCR_PULL_USERNAME=<your GitHub username>
```

`scripts/deploy.sh` picks these up automatically (see [deployment.md](deployment.md)). If you make
the GHCR package public instead (Packages tab on GitHub → package settings → Danger Zone → Change
visibility), neither variable is needed.

## Secrets

```bash
cp helm/claude-full-learning/values-secrets.yaml.example helm/claude-full-learning/values-dev.secrets.yaml
```

Fill in real RSA key material (the file itself documents the exact `openssl` recipe, same one
`application.properties` already uses). Repeat per environment (`values-test.secrets.yaml`,
`values-production.secrets.yaml`) with different keys per environment. These files are gitignored -
`git status` should never show them as untracked-but-stageable after you create them; if it does,
check `.gitignore` before committing anything.

## Verify the prerequisites are enough

```bash
./scripts/test.sh              # ./mvnw verify - no cluster needed
./scripts/start.sh              # minikube + addons
./scripts/deploy.sh dev --source local   # builds from this checkout, no GHCR/network needed
```

If that full sequence succeeds, everything above is correctly installed.
