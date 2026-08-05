# Troubleshooting

Every issue below was actually hit and fixed while building this platform, not a hypothetical list -
several are non-obvious enough that they're worth knowing about even if the current code already
works around them, in case you extend the chart or scripts further.

## `ImagePullBackOff` on the app pod

**Most likely cause**: the GHCR package is still private (the default) and no imagePullSecret is
configured. Either make the package public (see [deployment.md](deployment.md)'s GHCR section) or set
`GHCR_PULL_PAT`/`GHCR_PULL_USERNAME` before running `scripts/deploy.sh` so it creates the
imagePullSecret for you. Confirm with:

```bash
kubectl describe pod -n java-spring-auth-service-claude-<env> -l app.kubernetes.io/component=app
# look for: "pull access denied" or "unauthorized" in the Events section
```

## HPA shows `<unknown>` for CPU targets forever

`metrics-server` addon isn't enabled. `scripts/start.sh` enables it, but if you started minikube some
other way: `minikube addons enable metrics-server`, then confirm with `kubectl top pods -n
java-spring-auth-service-claude-<env>` - if that also fails, the addon isn't ready yet, give it a minute.

## ServiceMonitor exists but Prometheus never shows the target

Check `kubectl get servicemonitor java-spring-auth-service-claude -n java-spring-auth-service-claude-<env> -o yaml` and
compare its `spec.selector.matchLabels` against the app **Service's** `metadata.labels` (not the
Deployment's, not the Pod's) - Prometheus's `role: endpoints` discovery relabels based on the
Service's own labels copied onto the Endpoints object, not the Pod's labels directly. This project hit
exactly this: `templates/service.yaml` originally had `app.kubernetes.io/component: app` on
`spec.selector` (which pods to send traffic to) but not on the Service's own `metadata.labels` - so
the label never reached the Endpoints object and every target was silently dropped by relabeling.
Fixed by adding the label to both places; if you add further component-style labels to the chart,
make sure they're on both `metadata.labels` and `spec.selector`, not just one.

To check yourself: `kubectl get endpoints java-spring-auth-service-claude -n java-spring-auth-service-claude-<env> -o yaml`
and look for the label directly on that object. Also check Prometheus's own view:

```bash
kubectl port-forward -n monitoring svc/monitoring-kube-prometheus-prometheus 9090:9090
curl -s "http://localhost:9090/api/v1/targets?state=any" | jq '.data.droppedTargets'
```

A target appearing in `droppedTargets` rather than `activeTargets` confirms it's a relabeling/label
mismatch, not a network/scrape-config problem.

## Grafana dashboard doesn't appear

Check the sidecar actually picked it up:

```bash
kubectl logs -n monitoring deployment/monitoring-grafana -c grafana-sc-dashboard --tail=50 | grep java-spring-auth-service-claude
```

If nothing shows up, confirm the ConfigMap has the label the sidecar watches for:
`kubectl get configmap -n java-spring-auth-service-claude-<env> -l grafana_dashboard=1`. The
`kube-prometheus-stack` version this was built against defaults to
`sidecar.dashboards.searchNamespace: ALL`, so cross-namespace discovery already works out of the box
- if a future chart upgrade changes that default, you'd need
`--set grafana.sidecar.dashboards.searchNamespace=ALL` on the `monitoring` release, or move the
dashboard ConfigMap into the `monitoring` namespace instead.

## Ingress: connection refused / hangs / 404

1. Missing `/etc/hosts` entry - see [setup.md](setup.md).
2. On some minikube driver/OS combinations, `minikube ip` isn't directly routable from the host and
   you need `minikube tunnel` running in a separate terminal.
3. A 404 *from nginx itself* (not a connection failure) usually means the host header didn't match
   any Ingress rule - double check the host you're hitting matches `values-<env>.yaml`'s `ingress.host`
   exactly.

Since `deploy.sh`'s own health/smoke checks use `kubectl port-forward` rather than Ingress, none of
the above affects whether a deploy succeeds - only manual browser/curl access by hostname.

## Newman smoke tests fail or don't run

- `npx: command not found` - Node.js isn't installed; see [setup.md](setup.md). This only blocks the
  smoke-test step; everything else in `deploy.sh` still ran (rollout succeeded, health check passed).
- A smoke-test failure on a **repeat** run against the same still-running deployment - the Postman
  collection's username is `Date.now()`-based, so re-running `scripts/test.sh --smoke <url>` twice in
  the same millisecond-resolution window is the only realistic collision case; just re-run it.

## Loki: logs never show up (query returns nothing)

Loki defaults to multi-tenancy (`auth_enabled: true`) and silently rejects every push/query without
an `X-Scope-OrgID` header (`404 no org id`) - Promtail's default config doesn't send one.
`scripts/observability.sh` sets `--set loki.auth_enabled=false`, which is correct for this
single-tenant local demo; if you ever re-enable multi-tenancy, Promtail's client config needs a
[`tenant` stage](https://grafana.com/docs/loki/latest/send-data/promtail/stages/tenant/) added, or
logs will disappear with no obvious error on the Promtail side.

## Grafana → Explore has no "Loki" option

`kube-prometheus-stack`'s bundled Grafana only auto-provisions Prometheus and Alertmanager on its
own - Loki has to be added as a datasource explicitly, which `scripts/observability.sh` does via
`--set-json 'grafana.additionalDataSources=[...]'` on the `kube-prometheus-stack` install. If you
installed the stack before this was added (or ran a bare `helm install monitoring
prometheus-community/kube-prometheus-stack` by hand without that flag), re-run
`scripts/observability.sh` - it's `helm upgrade --install`, so it patches the existing release in
place rather than requiring a full reinstall. Confirm it worked: `kubectl port-forward -n monitoring
svc/monitoring-grafana 3000:80`, log in, Connections → Data sources, and check for "Loki" alongside
"Prometheus"/"Alertmanager".

## `scripts/observability.sh` fails to install Loki

A few non-obvious flags are required for the chart's Monolithic (single-binary) mode to actually
work on a local cluster - all already in `scripts/observability.sh`, documented here in case you're
customizing it further:

- **"more than zero replicas configured for both the monolithic and simple scalable targets"** -
  `read.replicas`/`write.replicas`/`backend.replicas` default to `3` each regardless of
  `deploymentMode`; the chart doesn't zero them out for you. Must explicitly set all three to `0`.
- **"You must provide a schema_config for Loki"** - add `--set loki.useTestSchema=true`, the chart's
  own suggested flag for schema-config-free local testing (no persistence guarantees, which is fine
  for a demo).
- **`loki-chunks-cache`/`loki-results-cache` pods stuck `Pending`** - these are optional memcached
  sidecars for read-path caching, not required for ingestion/query to work. On a resource-constrained
  local minikube node they can fail to schedule (`Insufficient memory`). Disabled via
  `--set chunksCache.enabled=false --set resultsCache.enabled=false`.

## Helm commands report "release: not found" right after a release clearly exists

A rough edge observed with Helm 4.x's release-storage backend during rapid uninstall/reinstall
cycles in the same namespace in quick succession. If `helm list`/`helm history` claim a release
doesn't exist but `kubectl get all -n <namespace>` shows its resources still running, the cluster
state is fine - Helm's own bookkeeping got confused. Fix: `kubectl delete namespace <namespace>` and
reinstall fresh, rather than trying to repair Helm's release secrets by hand.

## A namespace `kubectl delete`d with `--wait=false` reappears healthy, then later vanishes on its own

If you `kubectl delete namespace <ns> --wait=false` on a namespace containing `kube-prometheus-stack`
resources (the `monitoring` namespace, if you ever tear it down by hand instead of
`scripts/observability.sh --stop`), the delete can stay stuck in `Terminating` for a long time -
Prometheus/Alertmanager are CustomResources with finalizers the operator needs to process before the
namespace can actually finish deleting, and if the operator pod itself gets deleted first (as part of
the same namespace teardown), those finalizers may never clear on their own.

The confusing part: Kubernetes still lets you create new resources in a namespace that's
`Terminating` for a while before its final garbage-collection sweep runs. A `helm install
--create-namespace` targeting that same namespace name doesn't create a fresh namespace - it reuses
the still-terminating one - so the install can appear to succeed, with genuinely healthy pods, for
minutes or longer, before everything in it (the new install included) disappears the moment the
stuck termination finally completes. This was observed for real: a `monitoring` namespace deleted
with `--wait=false`, reinstalled and confirmed healthy in a later session, then found completely gone
(not stuck, fully removed) after an unrelated `minikube stop`/`minikube start` cycle - the restart
most likely nudged the controller manager into finishing the minutes-old stuck teardown.

Check with `kubectl get namespace <ns> -o jsonpath='{.status.phase}'` - if it prints `Terminating`
when you expected `Active`, that's this. Fix: let it finish (`kubectl get ns <ns> -w`), or force it
past stuck finalizers on the CRs themselves (`kubectl get prometheuses,alertmanagers -n <ns>`, then
`kubectl patch <resource> -n <ns> -p '{"metadata":{"finalizers":[]}}' --type=merge` on anything stuck)
rather than on the namespace object directly. Better: avoid this class of problem entirely by using
`scripts/observability.sh --stop` (`helm uninstall`, no namespace deletion at all) instead of
`kubectl delete namespace` by hand.

## Resources land in a different namespace than `-n` specified

Each `values-<env>.yaml` sets its own `namespace.name` (e.g. `java-spring-auth-service-claude-dev`), and the
chart's templates all resolve the target namespace from that value, not from `.Release.Namespace`.
This means the values file's namespace always wins over whatever `-n`/`--namespace` you pass to
`helm` directly - `scripts/deploy.sh` always keeps these in sync automatically by deriving both from
the same `<env>` argument, but if you run `helm` commands by hand, pass a `-n` matching the values
file's `namespace.name`, not an arbitrary one.

## `helm upgrade` fails with an immutable-field error on `Deployment.spec.selector`

Kubernetes Deployments can't have their label selector changed in place. If you're modifying
`templates/_helpers.tpl`'s selector labels or `templates/deployment.yaml`'s `spec.selector`, a plain
`helm upgrade` on an existing release will fail. Uninstall and reinstall for that one change:
`helm uninstall java-spring-auth-service-claude -n <namespace>` then `scripts/deploy.sh <env>` again.

## Why the observability charts aren't what other Loki tutorials show

`loki-stack` (the combined Loki+Promtail chart many older guides reference) is deprecated, and Loki
itself moved to a new `grafana-community/helm-charts` Helm repo in March 2026 - `helm repo add
grafana https://grafana.github.io/helm-charts` no longer serves a `loki` chart at all (it still
serves `promtail`, which is why `scripts/observability.sh` adds both the `grafana-community` repo
for Loki and the older `grafana` repo for Promtail). The `promtail` chart itself is also flagged
deprecated upstream in favor of Grafana Alloy, but is still functional and simpler to configure for
this demo's needs - a future migration to Alloy is a reasonable next step if `promtail` is ever
actually removed from the repo, not something this project needed to do preemptively.
