# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Spring Boot 3.3.4 REST API on Java 17. Exposes `POST /register`, `POST /login`, `GET /users/me`, and
`POST /users/me/password` against MongoDB, protected by Spring Security (JWT-based) and a per-IP rate
limiter. Sessions can be revoked server-side (password change, block) despite using JWTs — see below.

- Build tool: Maven, via the checked-in wrapper (`./mvnw`) — no global Maven install required.
- Package root: `org.example`

## Commands

```bash
# Compile
./mvnw compile

# Run the app (starts on :8080, needs MongoDB reachable per spring.data.mongodb.uri)
./mvnw spring-boot:run

# Package an executable jar
./mvnw -DskipTests package
java -jar target/java-spring-auth-service-claude-1.0-SNAPSHOT.jar

# Run all tests (unit + integration; integration tests need Docker running, for Testcontainers)
./mvnw test

# Run tests AND the code-coverage gate (what the Stop hook actually runs — see "Code coverage" below)
./mvnw verify

# Run a single test class
./mvnw -Dtest=ClassName test

# Run only unit tests, the same way ci.yml's fast job does (excludes *IntegrationTest)
./mvnw test -Dtest='!*IntegrationTest'

# Check / fix formatting (what ci.yml's build job gates on — see "Continuous integration" below)
./mvnw spotless:check
./mvnw spotless:apply

# View the coverage report after any of the above
open target/site/jacoco/index.html
```

## Architecture

Standard layered Spring Boot structure under `src/main/java/org/example/`:

- `controller/AuthController` — `POST /register` and `POST /login`, validates the request body and delegates
  to `AuthService`.
- `service/AuthService`
  - `authenticate` — looks up the user by username via `UserRepository`, rejects blocked users outright, and
    checks the password with `PasswordEncoder.matches(...)`.
  - `register` — hashes the password and saves a new `User`; relies on the unique index on `username`
    (catches `DuplicateKeyException`) rather than a find-then-save check, so concurrent registrations for the
    same username can't both succeed.
  - `isSessionValid(username, tokenIssuedAt)` — false if the user is blocked, gone, or `tokenIssuedAt`
    predates the user's `tokenValidAfter` marker. Called by `JwtAuthenticationFilter` on every request; see
    "Session revocation" below.
  - `changePassword` / `blockUser` — both bump `tokenValidAfter` to `Instant.now()`, which is how every
    token issued before the call — on any machine — fails `isSessionValid` on its very next use.
- `controller/UserController` — `GET /users/me` returns the caller's own details; `POST /users/me/password`
  changes it (current + new password, `@Size(min=8)` on the new one). Identity for both comes only from
  `@AuthenticationPrincipal String username` (the subject `JwtAuthenticationFilter` put in the
  `SecurityContext`), never from a path/query parameter — there is no way to ask for or change another user's
  account by construction, which is the actual authorization boundary here (there are no roles/permissions
  beyond "has a valid, non-revoked token"). `GET /users/me` 404s if the token's user no longer exists.
- `service/UserService` — `getUserDetails` maps `User` → `UserDetailsResponse`, stripping the password hash.
- `repository/UserRepository` — Spring Data MongoDB repository (`MongoRepository<User, String>`) over the
  `users` collection.
- `model/User` — MongoDB document; `password` field is a BCrypt hash, never plaintext; `username` has a
  unique index.
- `dto/` — `LoginRequest`/`RegisterRequest` (request shapes), `LoginResponse` (omits null `token` via
  `@JsonInclude(NON_NULL)` so a failed login has no `token` key), `UserDetailsResponse` (`GET /users/me` body
  — id + username only, never the password hash), `MessageResponse` (generic message body used by
  `/register`, `/users/me`'s 404, and the rate limiter's 429 response).
- `util/JwtUtil` — builds/signs JWTs with the active RSA private key (RS256, `kid` header set to
  `jwt.active-key-id`) and verifies via a key-locator over every key in `jwt.keys` (`JwtProperties`), not
  just the active one — see "Key rotation" below. Asymmetric rather than HMAC on purpose: only a private key
  can mint tokens, so a public key can later be handed to other services (or published, OIDC-style) that
  only need to verify tokens without ever being trusted to forge one. Also embeds a custom `iatMillis` claim
  alongside the standard `iat` — see "Session revocation" below for why.
- `util/JwtProperties` — `@ConfigurationProperties(prefix = "jwt")`; holds `activeKeyId`, `expirationMs`, and
  `keys` (a list, not a single pair) so multiple keys can be known at once during a rotation.
- `security/JwtAuthenticationFilter` — reads a `Bearer` token; if it's cryptographically valid, unexpired,
  *and* `AuthService.isSessionValid` says the session is still live, populates `SecurityContextHolder` with
  the username as principal. Doesn't reject requests itself; enforcement is via `authorizeHttpRequests` in
  `SecurityConfig`, so unauthenticated calls to `/login`/`/register` still pass.
- `security/RateLimitFilter` — per-client-IP, per-endpoint token bucket (Bucket4j) over `/login`, `/register`,
  and `/users/me/password` (a stolen-but-valid token still needs the current password, so this endpoint is a
  brute-force target too). Buckets live in a bounded, expiring Caffeine cache (not a plain map) so the
  limiter itself can't be turned into a memory-exhaustion vector by an attacker rotating source IPs.
  Exceeding the limit returns `429` with a `MessageResponse` body.
- `config/AppConfig` — defines the `PasswordEncoder` (`BCryptPasswordEncoder`) bean.
- `config/SecurityConfig` — stateless session policy, CSRF disabled (no cookies/sessions in play), permits
  `/login`, `/register`, and `/error`, everything else requires authentication. Custom
  `authenticationEntryPoint` returns a `401` + `MessageResponse` JSON body (Spring Security's default is a
  bare `403`, which is the wrong status for "not authenticated" and unhelpful for an API client). Filter
  order: `RateLimitFilter` → `JwtAuthenticationFilter` → the rest of the Spring Security chain.
  `/error` must stay permitted: Boot forwards internally to `/error` whenever any permitAll endpoint sets an
  error status (e.g. a validation 400, or a 500), and if that forward isn't itself allowed through, Security
  intercepts it and reports a bare 403 in place of the real status/body — this masked real errors during
  testing until fixed.
- `Main` excludes `UserDetailsServiceAutoConfiguration` — the app never uses Spring Security's
  `AuthenticationManager`/`UserDetailsService` (auth is custom, via `AuthService`), so leaving it enabled just
  creates an unused in-memory user and prints a random generated password on every startup.

Auth flow: `AuthController.login` → `AuthService.authenticate` (bool) → on success, `JwtUtil.generateToken`
issues the token; on failure, a 401 with a plain failure message. `AuthController.register` → returns 201 on
success, 409 if the username is already taken.

`GET /users/me` is the first protected endpoint: it isn't listed in `SecurityConfig`'s permitAll paths, so it
falls under `anyRequest().authenticated()` and requires a valid, non-expired, non-revoked Bearer token — no
SecurityConfig change was needed to add it, that wiring already existed for exactly this case.

### Session revocation

A plain JWT can't be un-issued — that's the whole point of it being stateless. Supporting "log out every
session on password change / block" required deliberately giving that up: `JwtAuthenticationFilter` now
calls `AuthService.isSessionValid` (a database read) on every authenticated request, not just a signature
check. That's the trade-off, made explicitly rather than accidentally — a pure JWT can be fast and stateless,
or revocable, not both.

The mechanism is a per-user `tokenValidAfter` (`Instant`, nullable) on `User`: `changePassword` and
`blockUser` bump it to `Instant.now()`, and `isSessionValid` rejects any token whose issued-at time predates
it. `blockUser` also sets `blocked = true`, checked independently of timing so a blocked user's tokens are
rejected even if somehow their `tokenValidAfter` comparison were to pass.

**Precision pitfall already hit once, worth not repeating**: the standard JWT `iat` claim is a NumericDate —
whole seconds only, per spec. `tokenValidAfter` is `Instant.now()`, millisecond+ precision. Comparing the two
directly means a token minted in the *same wall-clock second* as an invalidation event can land on either
side of it almost arbitrarily, depending on where in that second each was captured — first observed as a
freshly issued token being spuriously rejected, then (after a same-second-safe-looking "fix" that truncated
`tokenValidAfter` to whole seconds) as the opposite bug: the *old*, supposedly-revoked token still being
accepted, because truncation made it and the marker compare equal. Neither direction is acceptable for a
security control whose entire point is immediate revocation. The actual fix: `JwtUtil.generateToken` embeds
a custom `iatMillis` claim carrying full millisecond precision (the standard `iat` is left alone for spec
compliance), and `isSessionValid` compares that against `tokenValidAfter` with no truncation on either side.
`JwtUtilTest.extractIssuedAtHasMillisecondPrecisionNotJustSeconds` and
`AuthServiceTest.sessionValidityIsCorrectEvenWhenTokenAndInvalidationLandInTheSameWallClockSecond` guard
against this regressing.

There is no HTTP endpoint for blocking a user yet — `AuthService.blockUser` is a real, tested capability with
no admin/role system wired to it, on purpose: building RBAC wasn't asked for, and bolting an unguarded HTTP
endpoint onto a mutation this sensitive would be worse than not exposing it yet. `UserControllerIntegrationTest`
exercises it by calling the autowired `AuthService` bean directly.

### Key rotation

`jwt.keys` is a list, and `JwtUtil` verifies against a key-locator keyed by the token's `kid` header rather
than a single hardcoded public key — this is what makes rotation possible without a "everyone gets logged
out" event. To rotate: add a new entry to `jwt.keys`, point `jwt.active-key-id` at it, and keep the previous
entry in the list (public key only — its private key can be deleted, since nothing signs with it anymore)
until its already-issued tokens would have expired naturally anyway. Only then remove the old entry, at
which point a token still bearing its `kid` is rejected as an unknown key. New tokens are always signed with
whichever key `jwt.active-key-id` names; any entry in the list — active or retired — can still verify a
token whose `kid` matches its own id.

`JwtUtilTest` has three tests directly proving this works, not just that it compiles:
`tokenSignedByAKeyThatIsStillKnownButNoLongerActiveIsStillAccepted` (rotation doesn't break outstanding
tokens), `tokensMintedAfterRotationUseTheNewKeyNotTheRetiredOne` (new tokens actually use the new key), and
`tokenSignedByAFullyRetiredKeyIsRejectedOnceThatKeyIsRemovedFromConfig` (full retirement does eventually cut
a key off, so this isn't a mechanism that keeps every key alive forever by accident).

## Configuration

`src/main/resources/application.properties`:
- `spring.data.mongodb.uri` — defaults to `mongodb://localhost:27017/logindb`.
- `spring.data.mongodb.auto-index-creation` — must stay `true`, or `@Indexed` annotations (the unique index
  on `User.username`) are silently never applied and duplicate usernames slip through.
- `jwt.active-key-id` — which entry in `jwt.keys` signs new tokens.
- `jwt.keys[N].id` / `.private-key` / `.public-key` — RSA key(s) (PKCS8 private / X.509 public, base64 DER,
  no PEM headers). `private-key` is required only for the entry matching `jwt.active-key-id`; other entries
  (retired keys, kept only so their still-unexpired tokens keep verifying — see "Key rotation" above) need
  just `public-key`. The checked-in dev key is development-only and must be overridden (env vars, e.g. one
  secrets-manager-backed key per index, or external config) before any real deployment. Regenerate with:
  ```
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
  openssl pkcs8 -topk8 -nocrypt -in private.pem -outform DER | base64 | tr -d '\n'   # -> keys[N].private-key
  openssl pkey -in private.pem -pubout -outform DER | base64 | tr -d '\n'            # -> keys[N].public-key
  ```
  Must be `pkcs8 -topk8`, not `pkey -outform DER`, for the private key — on LibreSSL (macOS's default
  `/usr/bin/openssl`) `pkey -outform DER` emits traditional PKCS1 DER instead of PKCS8, which Java's
  `PKCS8EncodedKeySpec` rejects with `algid parse error, not a sequence` at Spring context startup.
- `jwt.expiration-ms` — token lifetime in milliseconds (default 1 hour).
- `ratelimit.capacity` / `ratelimit.refill-seconds` — requests allowed per client IP per endpoint
  (login/register), refilling every `refill-seconds` (default: 5 per 60s).
- `management.endpoints.web.exposure.include` — deliberately just `health,prometheus,info`, not a wider
  list; these endpoints are unauthenticated in `SecurityConfig` (kubelet/Prometheus can't present a JWT).
- `management.endpoint.health.probes.enabled` / `management.health.{liveness,readiness}state.enabled` —
  splits `/actuator/health` into `/actuator/health/liveness` and `/actuator/health/readiness`, used by the
  Helm chart's Deployment probes (see "Continuous deployment" below).
- `management.metrics.distribution.percentiles-histogram.http.server.requests` — exports histogram buckets
  (not just count/sum/max) so the Grafana dashboard's latency panel can compute real percentiles.

## Deployment

Three interchangeable ways to run locally, kept deliberately: `./mvnw spring-boot:run`, running the built
jar directly, and `docker compose up --build` (see README's Getting Started). None is being deprecated in
favor of another — the jar/spring-boot:run paths are the faster edit-compile-run loop; Docker is for
verifying the actual shipped artifact and for the Kubernetes path below, which builds on the same image.

**`Dockerfile`** is a two-stage build (JDK to compile, bare JRE to run) specifically so the shipped image
never carries Maven, the JDK, or source — only `app.jar` and a JRE. `.mvn/` (the wrapper) is copied in
before `src/`, deliberately, so `docker build` can cache the `dependency:go-offline` layer across
source-only changes instead of re-resolving every dependency on every build.

**`k8s/`** manifests are intentionally minimal, not production-shaped: `mongo.yaml` has no auth and no
`PersistentVolumeClaim` (data is lost on pod restart — acceptable for this demo, not for anything real),
and `app.yaml` uses TCP-socket readiness/liveness probes rather than an HTTP health check. That predates
Spring Boot Actuator being added to the project (see "Continuous deployment" below, which does use real
`/actuator/health` HTTP probes via the Helm chart) and is left unchanged deliberately — these manifests stay
the simplest possible way to run the app on minikube, not a target for every later addition. `app.yaml`'s
`imagePullPolicy: Never` assumes the image was loaded via `minikube image load`, not pulled from a registry
— there is no registry involved in this setup at all.

**`k8s/secret.yaml`** reuses the exact same dev RSA key pair as `application.properties` (generated
programmatically from it, not retyped, to eliminate transcription risk with base64 blobs of that length —
see the git history for the exact approach). A real deployment would source this from an actual secrets
manager, not a Kubernetes `Secret` checked into git; this is a demo of the deployment mechanics, not a
secrets-management story.

This was deployed to a real local minikube cluster and verified end-to-end (register → login → `/users/me`,
confirming the RSA `kid` header and rate-limit `429` both work identically inside the cluster) before these
manifests were checked in — not just written and assumed correct.

**`postman/java-spring-auth-service-claude.postman_collection.json`** — one gotcha worth not repeating: a Postman
collection variable's stored default value containing a dynamic variable (e.g. `"alice-{{$randomInt}}"`)
re-resolves to a *fresh* random value on every single reference to that variable, not once per collection
run. Storing a "random username" this way silently produces a different username each time `{{username}}`
is used, breaking any test that registers a user and later expects to log in as the same one — found by
actually running the collection with Newman rather than only inspecting the JSON. Fixed with a
collection-level pre-request script that generates the value once (`Date.now()`-based) and reuses it via
`pm.collectionVariables`.

## Continuous integration

`.github/workflows/ci.yml` — CI only, no deployment (CD is a deliberately separate piece of work — see
"Continuous deployment" below; it publishes to GHCR and deploys to local minikube via Helm, not AWS/EKS,
which was the original placeholder plan before the free-tooling constraint made GHCR + minikube the actual
target). Runs on push/PR to `master`; a new push cancels its own branch's still-running CI via
`concurrency` rather than letting stale and fresh runs both finish.

Six jobs, chained with `needs` so each only runs if the previous succeeded: `build` (`clean compile` +
`spotless:check` as its own step) → `unit-tests` (`test -Dtest='!*IntegrationTest'`) → `integration-tests`
(`verify` — see below for why this isn't filtered) → `packaging` (`-DskipTests package`, since tests already
gated the build above) → `docker-build` (image built, tagged with the full commit SHA, **not pushed** —
registry publishing is CD scope) → `publish-artifacts` (re-uploads everything the prior jobs uploaded as one
`ci-build-output` bundle, keyed on `github.run_id`, for a future CD workflow to consume).

**Why `integration-tests` runs the unfiltered `verify` instead of filtering to just the integration
classes**: this project has no Failsafe/Surefire split — both kinds of test run under Surefire in the `test`
phase (see "Tests" below), distinguished only by naming convention and by what's inside them (Mockito vs.
`@SpringBootTest` + Testcontainers), not by which Maven plugin executes them. `mvn verify` is therefore the
only command that reproduces the actual documented coverage baseline (96.5% line / 87.5% branch); filtering
this job to `*IntegrationTest` only, the same way `unit-tests` filters to everything else, would make the
JaCoCo `check` gate evaluate partial-suite coverage and pass or fail for a reason that has nothing to do
with whether the change under test is actually well-covered. The unit tests re-running here (having already
run once in the `unit-tests` job) is the accepted cost of keeping that gate meaningful, not an oversight.

**`spotless-maven-plugin`** (in `pom.xml`) is the formatting/quality gate for stage 2, deliberately configured
with a gentle rule set (`removeUnusedImports`, `trimTrailingWhitespace`, `endWithNewline`, 4-space `indent`)
rather than a full reformatter like `google-java-format` — the latter would rewrite this codebase's existing
brace/wrapping style wholesale in one unreviewable diff just to add a CI gate. It is **not** bound to any
Maven lifecycle phase, on purpose: `./mvnw compile`/`test`/`verify` behave exactly as already documented
above, unaffected by formatting, for the local dev loop and the Stop hook alike. Only `ci.yml` calls
`spotless:check` (and a developer would call `spotless:apply` to fix violations) as an explicit, separate
step — so a formatting failure is visibly distinct from a compile or test failure in the CI log.

**No `services:` block for MongoDB anywhere in `ci.yml`**: Testcontainers already starts and tears down its
own MongoDB container per test class directly against the runner's Docker daemon (present by default on
`ubuntu-latest`), which is exactly the isolation "Ensure tests run in an isolated environment" is asking for
— adding a GitHub Actions `services:` MongoDB on top would be a second, unused Mongo instance.

**`.github/actions/setup-java-maven`** is a composite action (checkout + JDK + Maven dependency cache),
shared by the `build`/`unit-tests`/`integration-tests`/`packaging` jobs so the Java version has exactly one
place to change. Each job still does its own `actions/checkout` and full recompile: GitHub Actions jobs run
on separate, fresh runners with no shared filesystem, so `needs` only orders execution — it does not carry
compiled output between jobs. Only the Maven dependency cache (keyed on `pom.xml`) is actually reused across
jobs; Docker layer caching for `docker-build` instead uses the GitHub Actions cache backend
(`cache-from`/`cache-to: type=gha`), since that job doesn't touch Maven's own cache at all.

**Immutable image tag** = the full commit SHA (`docker/metadata-action`'s `type=sha,format=long`); a
floating branch-name tag rides alongside for local convenience only and is never what identity is keyed on.
The image is built but never pushed (`push: false`) — no registry, no ECR, no Kubernetes apply — because
this workflow is CI, not CD; that boundary is intentional, not an oversight to fix later in this same file.

`docker-build` sets `DOCKER_BUILD_RECORD_UPLOAD: false` on the build step — `docker/build-push-action`
otherwise auto-uploads a `*.dockerbuild` build-record artifact (for Docker Desktop import) that isn't
consumed anywhere here, and its format made `publish-artifacts`'s blanket `actions/download-artifact` fail
outright rather than just skip it (observed in a real CI run before this was added — not a hypothetical).
We already write our own `docker-image-metadata.json` from `docker image inspect`, so the fix is to stop the
record from being generated at all rather than filter around it after the fact.

Branch protection requiring these checks on `master` is a **repository setting**, not something a workflow
YAML can express — see README's Continuous integration section for the exact steps (it also isn't retroactive:
each job name only appears in the branch-protection picklist after it has completed at least once).

## Continuous deployment

Two disconnected halves joined by one manual step, not a single automated pipeline — see
[`docs/architecture.md`](docs/architecture.md) for the full diagram and why: GitHub-hosted Actions runners
have no network path to a developer's local minikube, so "push to deploy" all the way through isn't
possible without registering a self-hosted runner (a real option, deliberately not taken here — it trades
this clean split for a persistent listener process with access to the local machine).

**`.github/workflows/cd.yml`** — cloud, fully automatic. Builds and pushes to
`ghcr.io/asutosh-patnaik/java-spring-auth-service-claude` on push to `master` and on `v*` tags, using the built-in
`GITHUB_TOKEN` (no new secret). Immutable full-SHA tag always; semver tags only on `v*` tags; floating
`latest` only from `master`. Re-runs its own Docker build rather than consuming `ci.yml`'s image (no clean
way to hand a loaded image between separate workflow *runs*), reusing the same `type=gha` cache layer
`ci.yml` just populated. Includes its own `mvnw verify` first, since a `v*` tag push can point at any
commit, not just a reviewed/CI-passed one — `master` pushes are covered by branch protection instead.

**`helm/java-spring-auth-service-claude/`** — a Helm chart parallel to the plain `k8s/` manifests (kept exactly as-is,
untouched by this work). Deployment, Service, ConfigMap, Secret, Namespace, ServiceAccount, Ingress, HPA,
an in-chart Mongo (same no-auth/no-PVC demo posture as `k8s/mongo.yaml`, deliberately not a Bitnami
subchart — see the chart's own `mongo-deployment.yaml` comment), plus `values.yaml` defaults and
`values-{dev,test,production}.yaml` overlays that change only what genuinely differs for a local demo
(replica count, ingress host, log level, and — production only — bumped resources with autoscaling
actually turned on). Real HTTP health probes (`/actuator/health/{liveness,readiness}`) replace `k8s/
app.yaml`'s TCP-only probes — the payoff of adding Actuator. Each `values-<env>.yaml` fixes its own
`namespace.name`, which always wins over whatever `-n` a `helm` command is given — `scripts/deploy.sh`
keeps the two in sync by deriving both from the same `<env>` argument; a hand-run `helm` command must do
the same or resources land somewhere unexpected.

**`scripts/*.sh`** (+ shared helpers in `scripts/lib/`) — the local half: `start.sh` (minikube + required
addons), `observability.sh` (Prometheus/Grafana/Loki, once per cluster, optional), `deploy.sh` (the actual
Phase-4-style sequence: resolve image → `helm upgrade --install` → rollout wait → health check → Newman
smoke tests against the existing `postman/` collection → automatic `helm rollback` on any failure from
that point on), `test.sh` (`mvnw verify`, or `--smoke <url>` for just the Newman portion), `rollback.sh`
(manual undo + re-verify), `destroy.sh` (the one script with real confirmation gating — uninstalling one
release vs. `minikube delete` are never behind the same flag). Full walkthrough:
[`docs/deployment.md`](docs/deployment.md); recovery: [`docs/rollback.md`](docs/rollback.md); real,
previously-hit failure modes: [`docs/troubleshooting.md`](docs/troubleshooting.md).

**Observability** — `prometheus-community/kube-prometheus-stack` (Prometheus + Grafana + Alertmanager +
node-exporter + kube-state-metrics) plus `grafana-community/loki` (Monolithic mode, filesystem storage —
the originally-planned `loki-stack` chart turned out deprecated, and Loki itself moved to a new
`grafana-community` org in March 2026) and `grafana/promtail` for log shipping (no application logging
code changes — Spring Boot already logs to stdout, Promtail tails container logs directly).
`scripts/observability.sh` also passes `--set-json 'grafana.additionalDataSources=[...]'` on the
`kube-prometheus-stack` install to pre-wire Loki as a Grafana datasource — without it, that chart's
bundled Grafana only auto-provisions Prometheus and Alertmanager (confirmed via its own
`/api/datasources`), so Explore would have nothing to query logs with until someone added Loki by
hand. The chart's
`templates/servicemonitor.yaml` and `templates/grafana-dashboard-configmap.yaml` are both off by default
(`serviceMonitor.enabled`/`grafanaDashboard.enabled`), turned on once `scripts/observability.sh` has
installed the stack. `management.metrics.distribution.percentiles-histogram.http.server.requests=true`
(in `application.properties`) is the one additional Actuator property beyond Phase 0's baseline — it's
what lets the Grafana dashboard's latency panel compute real p50/p95 via `histogram_quantile` instead of
just an average. See `docs/troubleshooting.md` for the several non-obvious flags Loki's chart needed
(`useTestSchema`, zeroed SimpleScalable replicas, disabled memcached sidecars, disabled multi-tenancy) and
the real Service-labeling bug the ServiceMonitor work surfaced (fixed in `templates/service.yaml`).

**Secrets** — real values never committed. `helm/java-spring-auth-service-claude/values-<env>.secrets.yaml` (one per
environment) is gitignored (`helm/**/values-*.secrets.yaml`); `values-secrets.yaml.example` is the tracked
template, cross-referencing `application.properties`'s existing RSA key-generation recipe rather than a
new one. `templates/secret.yaml` fails the Helm render loudly if `secrets.jwtPrivateKey` is still empty,
so a forgotten `-f values-<env>.secrets.yaml` can't silently deploy a broken app. `k8s/secret.yaml`
already has real dev RSA key material checked into git — a known, pre-existing, out-of-scope-to-fix-here
shortcut for that minimal demo path, not a pattern the Helm path follows. A private GHCR package's pull
credential (`GHCR_PULL_PAT`/`GHCR_PULL_USERNAME`) is read only from the shell environment inside
`deploy.sh`, never written to a file or passed as a literal `--set` argument.

## Testing policy

Every change to functionality ships with tests in the same change, not as a follow-up:

- **New or changed endpoint** (new route, or a change to an existing one's request/response contract,
  status codes, or auth requirements): add an integration test in `AuthControllerIntegrationTest`
  (MockMvc + Testcontainers, hitting the real HTTP layer) **and** unit tests for any new logic in the
  service/util classes underneath it. Integration tests are mandatory here, not optional — a new API flow
  isn't done until it has one.
- **New or changed logic in an existing class** (service, filter, util) that doesn't add/change a route:
  a unit test is enough — extend the existing test class for that component (`AuthServiceTest`,
  `JwtUtilTest`, `RateLimitFilterTest`, `JwtAuthenticationFilterTest`) rather than creating a parallel one.
- **Bug fix**: add a test that fails against the old code and passes against the fix, so the bug can't
  silently come back.
- Pure config/doc/comment changes (`application.properties`, `CLAUDE.md`) don't need tests.

Match existing conventions rather than introducing new ones: Mockito + plain instantiation for unit tests
(no Spring context), `@SpringBootTest` + `MockMvc` + Testcontainers `MongoDBContainer` for integration tests.
Any new integration test touching `/login` or `/register` (or a future rate-limited endpoint) needs its own
fake client IP via `.with(fromIp(...))` — see the note in `## Tests` below on why.

Whether the *right* tests were added for what changed is a judgment call a hook can't make — the Stop hook
(`.claude/hooks/run-tests-on-stop.sh`) runs `./mvnw verify` (tests + the coverage gate below) after every
turn and blocks on failures, but "coverage didn't regress" is not the same claim as "this was tested
meaningfully." This section is the check for the latter; the coverage gate is a mechanical backstop for the
former — evidence a change was covered *at all*, not evidence it was covered *well*.

## Code coverage

`./mvnw verify` runs a JaCoCo coverage gate (`jacoco-maven-plugin`, bound to the `verify` phase) that fails
the build if project-wide line coverage drops below 80% or branch coverage below 70%, excluding `Main`
(the `SpringApplication.run()` bootstrap — not meaningfully unit-testable, and excluded the same way in
effectively every Spring Boot project). Both thresholds sit comfortably below this project's actual
baseline (96.5% line / 87.5% branch as of the JWT-rotation work) specifically so the gate catches a real
drop in coverage — a large new untested class, a controller added without tests — without being a
hair-trigger that fails the build over one defensive catch block someone reasonably chose not to test.

`plain ./mvnw test` does **not** run this gate — only `./mvnw verify` does (and that's what the Stop hook
runs). View the HTML report at `target/site/jacoco/index.html` after either command; both generate it via
the `report` execution.

If a legitimate change needs the threshold moved, change it deliberately in `pom.xml`
(`jacoco-maven-plugin` → `check` execution → `limits`) with a reason — don't lower it silently just to make
a failing build pass.

## Tests

- Unit tests (`JwtUtilTest`, `AuthServiceTest`, `UserServiceTest`, `RateLimitFilterTest`,
  `JwtAuthenticationFilterTest`) use Mockito/plain instantiation — no Spring context, no external services.
  Tests needing a `JwtUtil` generate a fresh, throwaway RSA key pair via `TestRsaKeys.generate()` rather
  than hardcoding key material.
- `AuthControllerIntegrationTest` and `UserControllerIntegrationTest` boot the full app (`@SpringBootTest` +
  `MockMvc`) against a real MongoDB via Testcontainers (`@Container` + `@ServiceConnection` — needs Docker
  running locally, no manual Mongo setup required) and drive it through HTTP. Between them: registration,
  duplicate/validation rejection, login success/failure, JWT issuance, `/users/me` with a valid/missing/
  malformed/genuinely-expired token, cross-user isolation (each token only ever sees its own user),
  rate-limit enforcement, and session revocation (password change invalidates the old token but not on a
  wrong current password; blocking invalidates existing tokens immediately and future login attempts).
- Each integration test method uses a distinct fake client IP (`RequestPostProcessor` setting
  `remoteAddr`) via `.with(fromIp(...))`. `RateLimitFilter`'s buckets are keyed by IP+path and live in a
  singleton bean shared across the whole test class, so without this, unrelated tests hitting `/login` from
  the same default MockMvc IP would silently drain each other's rate-limit allowance. `UserControllerIntegrationTest`'s
  expired-token test mints its own token with a throwaway `JwtUtil` built from the app's real, `@Autowired`
  `JwtProperties` (same keys, copied into a new `JwtProperties` with a 1ms expiry), rather than overriding
  `jwt.expiration-ms` for the whole class — that would make even the "valid token" tests race against expiry.
- `JwtUtilTest` covers rotation directly with three throwaway-key-pair scenarios (see "Key rotation" above)
  rather than only testing the single-key happy path.

## Testing the API manually

Requires a reachable MongoDB (e.g. `docker run -d --name login-mongo -p 27017:27017 mongo:7`).

```bash
curl -X POST localhost:8080/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'

curl -X POST localhost:8080/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'
# copy the "token" field from the response, then:
curl localhost:8080/users/me -H 'Authorization: Bearer <token>'

curl -X POST localhost:8080/users/me/password -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"password123","newPassword":"newpassword456"}'
# <token> above is now dead — every other request with it will 401, on this or any other machine
```

Hitting either endpoint more than `ratelimit.capacity` times within `ratelimit.refill-seconds` from the same
IP returns `429 Too Many Requests`. When testing rate limiting by hand with curl, use an explicit IP (e.g.
`http://127.0.0.1:8080/login`) rather than `localhost` — `localhost` can resolve to `127.0.0.1` on some
requests and `::1` on others, splitting traffic across two different rate-limit buckets.
