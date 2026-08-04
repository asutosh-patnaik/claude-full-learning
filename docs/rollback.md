# Rollback

## `deploy.sh` already does this automatically

If `helm upgrade`, the rollout, the health check, or the smoke tests fail during a `scripts/deploy.sh`
run, it automatically rolls back to whatever revision was live immediately before that run - you
don't need to do anything by hand for that case. This doc is for the other two situations: a deploy
that *succeeded* but turned out to be bad, or a fresh install that failed with nothing to roll back to.

## Manual rollback

```bash
./scripts/rollback.sh <dev|test|production>              # list revision history
./scripts/rollback.sh <dev|test|production> <revision>    # roll back to that revision
```

The second form runs `helm rollback --wait` and then the same health-check retry loop `deploy.sh`
uses, so you get a clear pass/fail on whether the rollback actually left the app healthy - not just
that the Helm command returned success.

## What a rollback actually reverts

Only the Deployment's pod template: image tag, environment variables, resource requests/limits -
whatever the chart's `templates/deployment.yaml` renders. It does **not** revert:

- **Mongo's data.** The in-chart Mongo (`templates/mongo-deployment.yaml`) has no
  PersistentVolumeClaim on purpose (same demo posture as `k8s/mongo.yaml`) - its data is tied to the
  pod's lifetime, not to any Helm revision. A Helm rollback doesn't touch the Mongo pod at all unless
  the chart version that introduced the bad revision also changed something Mongo-related.
- **The Secret/ConfigMap contents**, if you changed `values-<env>.secrets.yaml` or `values-<env>.yaml`
  directly and re-ran `helm upgrade` - those follow the *file*, not a Helm revision snapshot in the
  way you might expect from e.g. a git revert. `helm rollback` does restore the exact rendered
  manifests (including Secret/ConfigMap) from that historical revision, so this is covered - just
  worth knowing it's restoring rendered YAML, not "the state of your values files at that time" if
  those files have since changed further.

## Fresh install failed, nothing to roll back to

If `deploy.sh` fails on its very first install for an environment (no previous revision exists), it
says so and suggests `helm uninstall <release> -n <namespace>` (or `scripts/destroy.sh
--uninstall-release <env>`) instead of a rollback - there's nothing to roll back *to*. Fix whatever
caused the failure (see [troubleshooting.md](troubleshooting.md)) and re-run `deploy.sh`.
