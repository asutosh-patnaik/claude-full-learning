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
java -jar target/claude-full-learning-1.0-SNAPSHOT.jar

# Run all tests (unit + integration; integration tests need Docker running, for Testcontainers)
./mvnw test

# Run a single test class
./mvnw -Dtest=ClassName test
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
- `util/JwtUtil` — builds/signs JWTs with an RSA private key (RS256) and verifies them with the matching
  public key (via `jjwt`), using `jwt.private-key`/`jwt.public-key`/`jwt.expiration-ms` from
  `application.properties`. Asymmetric rather than HMAC on purpose: only the private key can mint tokens,
  so the public key can later be handed to other services (or published, OIDC-style) that only need to
  verify tokens without ever being trusted to forge one. Also embeds a custom `iatMillis` claim alongside
  the standard `iat` — see "Session revocation" below for why.
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

## Configuration

`src/main/resources/application.properties`:
- `spring.data.mongodb.uri` — defaults to `mongodb://localhost:27017/logindb`.
- `spring.data.mongodb.auto-index-creation` — must stay `true`, or `@Indexed` annotations (the unique index
  on `User.username`) are silently never applied and duplicate usernames slip through.
- `jwt.private-key` / `jwt.public-key` — RSA key pair (PKCS8 private / X.509 public, base64 DER, no PEM
  headers); the checked-in values are development-only and must be overridden (env var or external config,
  e.g. `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`) before any real deployment. Regenerate with:
  ```
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
  openssl pkcs8 -topk8 -nocrypt -in private.pem -outform DER | base64 | tr -d '\n'   # -> jwt.private-key
  openssl pkey -in private.pem -pubout -outform DER | base64 | tr -d '\n'            # -> jwt.public-key
  ```
  Must be `pkcs8 -topk8`, not `pkey -outform DER`, for the private key — on LibreSSL (macOS's default
  `/usr/bin/openssl`) `pkey -outform DER` emits traditional PKCS1 DER instead of PKCS8, which Java's
  `PKCS8EncodedKeySpec` rejects with `algid parse error, not a sequence` at Spring context startup.
- `jwt.expiration-ms` — token lifetime in milliseconds (default 1 hour).
- `ratelimit.capacity` / `ratelimit.refill-seconds` — requests allowed per client IP per endpoint
  (login/register), refilling every `refill-seconds` (default: 5 per 60s).

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

This is a judgment call a hook can't make. The Stop hook (`.claude/hooks/run-tests-on-stop.sh`) runs the
suite after every turn and blocks on failures, but it only verifies that whatever tests exist still pass —
it has no way to know whether tests were actually added for what just changed. This section is that check.

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
  expired-token test mints its own token with a throwaway `JwtUtil` built from the app's real
  `jwt.private-key`/`jwt.public-key` (`@Value`-injected) and a 1ms expiry, rather than overriding
  `jwt.expiration-ms` for the whole class — that would make even the "valid token" tests race against expiry.

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
