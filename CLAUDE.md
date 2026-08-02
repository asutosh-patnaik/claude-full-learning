# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Spring Boot 3.3.4 REST API on Java 17. Exposes `POST /register`, `POST /login`, and `GET /users/me` against
MongoDB, protected by Spring Security (stateless, JWT-based) and a per-IP rate limiter.

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
  - `authenticate` — looks up the user by username via `UserRepository` and checks the password with
    `PasswordEncoder.matches(...)`.
  - `register` — hashes the password and saves a new `User`; relies on the unique index on `username`
    (catches `DuplicateKeyException`) rather than a find-then-save check, so concurrent registrations for the
    same username can't both succeed.
- `controller/UserController` — `GET /users/me`, returns the caller's own details. Identity comes only from
  `@AuthenticationPrincipal String username` (the subject `JwtAuthenticationFilter` put in the
  `SecurityContext`), never from a path/query parameter — there is no way to ask for another user's data by
  construction, which is the actual authorization boundary here (there are no roles/permissions beyond "has a
  valid token"). 404s if the token's user no longer exists (e.g. deleted after the token was issued).
- `service/UserService` — `getUserDetails` maps `User` → `UserDetailsResponse`, stripping the password hash.
- `repository/UserRepository` — Spring Data MongoDB repository (`MongoRepository<User, String>`) over the
  `users` collection.
- `model/User` — MongoDB document; `password` field is a BCrypt hash, never plaintext; `username` has a
  unique index.
- `dto/` — `LoginRequest`/`RegisterRequest` (request shapes), `LoginResponse` (omits null `token` via
  `@JsonInclude(NON_NULL)` so a failed login has no `token` key), `UserDetailsResponse` (`GET /users/me` body
  — id + username only, never the password hash), `MessageResponse` (generic message body used by
  `/register`, `/users/me`'s 404, and the rate limiter's 429 response).
- `util/JwtUtil` — builds/signs JWTs and validates/parses them (HMAC via `jjwt`), using
  `jwt.secret`/`jwt.expiration-ms` from `application.properties`.
- `security/JwtAuthenticationFilter` — reads a `Bearer` token, and if valid, populates
  `SecurityContextHolder` with the username as principal. Doesn't reject requests itself; enforcement is via
  `authorizeHttpRequests` in `SecurityConfig`, so unauthenticated calls to `/login`/`/register` still pass.
- `security/RateLimitFilter` — per-client-IP, per-endpoint token bucket (Bucket4j) over `/login` and
  `/register` only. Buckets live in a bounded, expiring Caffeine cache (not a plain map) so the limiter itself
  can't be turned into a memory-exhaustion vector by an attacker rotating source IPs. Exceeding the limit
  returns `429` with a `MessageResponse` body.
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
falls under `anyRequest().authenticated()` and requires a valid, non-expired Bearer token — no SecurityConfig
change was needed to add it, that wiring already existed for exactly this case.

## Configuration

`src/main/resources/application.properties`:
- `spring.data.mongodb.uri` — defaults to `mongodb://localhost:27017/logindb`.
- `spring.data.mongodb.auto-index-creation` — must stay `true`, or `@Indexed` annotations (the unique index
  on `User.username`) are silently never applied and duplicate usernames slip through.
- `jwt.secret` — HMAC signing key; the checked-in value is a development-only placeholder and must be
  overridden (env var or external config) before any real deployment.
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
- `AuthControllerIntegrationTest` and `UserControllerIntegrationTest` boot the full app (`@SpringBootTest` +
  `MockMvc`) against a real MongoDB via Testcontainers (`@Container` + `@ServiceConnection` — needs Docker
  running locally, no manual Mongo setup required) and drive it through HTTP. Between them: registration,
  duplicate/validation rejection, login success/failure, JWT issuance, `/users/me` with a valid/missing/
  malformed/genuinely-expired token, cross-user isolation (each token only ever sees its own user), and
  rate-limit enforcement.
- Each integration test method uses a distinct fake client IP (`RequestPostProcessor` setting
  `remoteAddr`) via `.with(fromIp(...))`. `RateLimitFilter`'s buckets are keyed by IP+path and live in a
  singleton bean shared across the whole test class, so without this, unrelated tests hitting `/login` from
  the same default MockMvc IP would silently drain each other's rate-limit allowance. `UserControllerIntegrationTest`'s
  expired-token test mints its own token with a throwaway `JwtUtil` built from the app's real `jwt.secret`
  (`@Value`-injected) and a 1ms expiry, rather than overriding `jwt.expiration-ms` for the whole class — that
  would make even the "valid token" tests race against expiry.

## Testing the API manually

Requires a reachable MongoDB (e.g. `docker run -d --name login-mongo -p 27017:27017 mongo:7`).

```bash
curl -X POST localhost:8080/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'

curl -X POST localhost:8080/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'
# copy the "token" field from the response, then:
curl localhost:8080/users/me -H 'Authorization: Bearer <token>'
```

Hitting either endpoint more than `ratelimit.capacity` times within `ratelimit.refill-seconds` from the same
IP returns `429 Too Many Requests`. When testing rate limiting by hand with curl, use an explicit IP (e.g.
`http://127.0.0.1:8080/login`) rather than `localhost` — `localhost` can resolve to `127.0.0.1` on some
requests and `::1` on others, splitting traffic across two different rate-limit buckets.
