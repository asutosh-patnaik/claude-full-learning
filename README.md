# claude-full-learning

A Spring Boot REST API for user registration and authentication, built around JWT bearer tokens with
server-side session revocation, RSA key rotation, and per-IP rate limiting — MongoDB-backed, Java 17.

> For architecture rationale, trade-offs, and the reasoning behind specific implementation choices, see
> [CLAUDE.md](CLAUDE.md). This README covers what the project is and how to use it; CLAUDE.md covers *why*
> it's built the way it is.

## Features

- **Registration & login** (`POST /register`, `POST /login`) — BCrypt-hashed passwords, unique usernames
  enforced at the database level (not just application logic).
- **JWT authentication**, signed with RSA (RS256), not HMAC — a private key mints tokens, a public key
  verifies them, so verification can later be delegated to other services without ever trusting them to
  forge a token.
- **Key rotation** — multiple signing keys can be known at once (`jwt.keys`), identified by a `kid` header
  on each token, so a key can be rotated without invalidating every session issued under the previous one.
- **Server-side session revocation** — despite using stateless JWTs, changing your password or being
  blocked invalidates every previously issued token immediately, on every machine, not just the one that
  triggered it.
- **Self-service password change** (`POST /users/me/password`) — requires the current password; wrong
  attempts don't touch the account or its existing sessions.
- **Per-IP rate limiting** on `/login`, `/register`, and `/users/me/password` — a token-bucket limiter
  (Bucket4j) backed by a bounded, expiring cache, so it can't itself become a memory-exhaustion vector.
- **`GET /users/me`** — returns only the caller's own account details, derived entirely from their token;
  there is no way to request another user's data.

## Tech stack

- Java 17, Spring Boot 3.3.4 (Web, Security, Data MongoDB, Validation)
- MongoDB
- [`jjwt`](https://github.com/jwtk/jjwt) for JWT signing/verification
- [Bucket4j](https://github.com/bucket4j/bucket4j) + [Caffeine](https://github.com/ben-manes/caffeine) for
  rate limiting
- JUnit 5, Mockito, [Testcontainers](https://testcontainers.com/) for testing
- Maven (via the checked-in wrapper, `./mvnw` — no local Maven install required)

## Prerequisites

- Java 17+
- Docker (for running MongoDB locally, and required by the integration test suite via Testcontainers)

## Getting started

```bash
# 1. Start MongoDB
docker run -d --name login-mongo -p 27017:27017 mongo:7

# 2. Run the app (starts on :8080)
./mvnw spring-boot:run
```

Or build and run the jar directly:

```bash
./mvnw -DskipTests package
java -jar target/claude-full-learning-1.0-SNAPSHOT.jar
```

## API reference

All request/response bodies are JSON.

### `POST /register`

| Field      | Rules                        |
|------------|-------------------------------|
| `username` | required                      |
| `password` | required, minimum 8 characters |

```bash
curl -X POST localhost:8080/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'
```

- `201 Created` — `{"message":"User registered successfully"}`
- `409 Conflict` — `{"message":"Username already exists"}`
- `400 Bad Request` — validation failure (e.g. password too short)

### `POST /login`

```bash
curl -X POST localhost:8080/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'
```

- `200 OK` — `{"message":"Login successful","token":"<jwt>"}`
- `401 Unauthorized` — `{"message":"Invalid username or password"}` (also returned for a blocked account —
  intentionally identical to a wrong password, so a caller can't distinguish the two)

### `GET /users/me`

Requires `Authorization: Bearer <token>`.

```bash
curl localhost:8080/users/me -H 'Authorization: Bearer <token>'
```

- `200 OK` — `{"id":"<mongo id>","username":"alice"}`
- `401 Unauthorized` — `{"message":"Authentication required"}` (missing, malformed, expired, or revoked
  token — see [Session revocation](#session-revocation) below)
- `404 Not Found` — `{"message":"User not found"}` (the account behind a still-valid token was deleted)

### `POST /users/me/password`

Requires `Authorization: Bearer <token>`.

| Field             | Rules                          |
|-------------------|----------------------------------|
| `currentPassword` | required                        |
| `newPassword`     | required, minimum 8 characters  |

```bash
curl -X POST localhost:8080/users/me/password -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"password123","newPassword":"newpassword456"}'
```

- `200 OK` — `{"message":"Password changed successfully. All existing sessions have been signed out."}` —
  the token used to make this call, and every other token issued to this account, is now dead.
- `401 Unauthorized` — `{"message":"Current password is incorrect"}` (the account and its existing sessions
  are left untouched)

### Rate limiting

`/login`, `/register`, and `/users/me/password` are capped per client IP (default: 5 requests per 60
seconds, see [Configuration](#configuration)). Exceeding it returns:

- `429 Too Many Requests` — `{"message":"Too many requests, please try again later"}`

When testing this by hand with curl, use an explicit IP (`http://127.0.0.1:8080/...`) rather than
`localhost` — `localhost` can resolve to `127.0.0.1` on some requests and `::1` on others, splitting
traffic across two separate rate-limit buckets.

## Security model

### JWT signing

Tokens are signed with RSA (RS256), not a shared HMAC secret: `jwt.keys` lists one or more RSA key pairs,
and `jwt.active-key-id` names the one currently used to sign new tokens. Only the private key can mint a
token; the public key can safely be handed to anything that only needs to verify one.

### Key rotation

Because multiple keys can be known at once, rotating the signing key doesn't invalidate every outstanding
session:

1. Generate a new key pair and add it to `jwt.keys`.
2. Point `jwt.active-key-id` at the new key. New tokens are signed with it; tokens already issued under the
   old key keep verifying, since the old key is still listed.
3. Once enough time has passed that all tokens signed under the old key would have expired anyway, remove
   its entry from `jwt.keys` entirely (its private key can be deleted as soon as step 2 is done — nothing
   signs with it anymore).

### Session revocation

A plain JWT can't be un-issued — verification is normally a pure signature/expiry check with no database
lookup, which is what makes JWTs stateless in the first place. This project gives up some of that
statelessness deliberately: every authenticated request also checks a per-user `tokenValidAfter` marker,
which is bumped to "now" on password change or on being blocked. Any token issued before that moment —
regardless of which machine or session it came from — is rejected on its very next use, even though the
token itself hasn't expired and its signature is still perfectly valid.

There is currently no HTTP endpoint for blocking a user — it exists as a tested service-layer capability
(`AuthService.blockUser`) with no admin role system attached to it yet.

## Configuration

All configuration lives in `src/main/resources/application.properties`.

| Property | Description |
|---|---|
| `spring.data.mongodb.uri` | MongoDB connection string. Default: `mongodb://localhost:27017/logindb`. |
| `spring.data.mongodb.auto-index-creation` | Must be `true` — otherwise the unique index on `username` is never created and duplicate usernames slip through. |
| `jwt.active-key-id` | Which entry in `jwt.keys` signs new tokens. |
| `jwt.keys[N].id` | Identifier for a key, matched against a token's `kid` header. |
| `jwt.keys[N].private-key` | PKCS8 RSA private key, base64 DER, no PEM headers. Required only for the active key. |
| `jwt.keys[N].public-key` | X.509 RSA public key, base64 DER, no PEM headers. Required for every key, active or retired. |
| `jwt.expiration-ms` | Token lifetime in milliseconds. Default: `3600000` (1 hour). |
| `ratelimit.capacity` | Requests allowed per client IP per rate-limited endpoint before a `429`. Default: `5`. |
| `ratelimit.refill-seconds` | Window over which the rate-limit allowance refills. Default: `60`. |

**The checked-in RSA key is a development-only placeholder.** Generate your own before deploying anywhere
real:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl pkcs8 -topk8 -nocrypt -in private.pem -outform DER | base64 | tr -d '\n'   # -> keys[N].private-key
openssl pkey -in private.pem -pubout -outform DER | base64 | tr -d '\n'            # -> keys[N].public-key
```

Use `openssl pkcs8 -topk8`, not `openssl pkey -outform DER`, for the private key. On LibreSSL (macOS's
default `/usr/bin/openssl`), `pkey -outform DER` emits legacy PKCS1 DER instead of PKCS8, which Java
rejects at startup with `algid parse error, not a sequence`.

In production, both keys (and the MongoDB URI) should come from environment variables backed by a secrets
manager, not from a checked-in properties file.

## Testing

```bash
# Run everything (unit + integration; integration tests need Docker running)
./mvnw test

# Run a single test class
./mvnw -Dtest=ClassName test
```

- **Unit tests** (`JwtUtilTest`, `AuthServiceTest`, `UserServiceTest`, `RateLimitFilterTest`,
  `JwtAuthenticationFilterTest`) run with plain Mockito/instantiation — no Spring context, no external
  services required.
- **Integration tests** (`AuthControllerIntegrationTest`, `UserControllerIntegrationTest`) boot the full
  Spring application and drive it over real HTTP (MockMvc) against a real MongoDB instance, provisioned
  automatically per test run via [Testcontainers](https://testcontainers.com/) — no manual database setup
  needed, just a running Docker daemon.

See [CLAUDE.md's Testing policy](CLAUDE.md#testing-policy) for the project's rule on when a change requires
a unit test versus an integration test.

## Project structure

```
src/main/java/org/example/
├── controller/     REST endpoints (AuthController, UserController)
├── service/        Business logic (AuthService, UserService)
├── repository/     Spring Data MongoDB repositories
├── model/          MongoDB documents (User)
├── dto/            Request/response bodies
├── security/       Servlet filters (JwtAuthenticationFilter, RateLimitFilter)
├── config/         Spring configuration (SecurityConfig, AppConfig)
└── util/           JwtUtil, JwtProperties
```

Full breakdown of each class's responsibilities: [CLAUDE.md's Architecture section](CLAUDE.md#architecture).

## Continuous integration

Pull requests in this repository are automatically reviewed by Claude via GitHub Actions
(`.github/workflows/claude-code-review.yml` and `claude.yml`, installed via Claude Code's
`/install-github-app`). Mention `@claude` in a PR or issue comment to invoke it directly — for example,
`@claude review this PR` or `@claude` followed by any other instruction.

## Contributing

- Every change to functionality should ship with tests in the same change — see
  [CLAUDE.md's Testing policy](CLAUDE.md#testing-policy) for exactly what's expected (unit vs. integration,
  when each is required).
- A local Git hook (`.claude/hooks/run-tests-on-stop.sh`) runs the full test suite automatically at the end
  of a Claude Code turn in this repo and surfaces failures back to Claude — see
  [CLAUDE.md](CLAUDE.md#tests) for details.

## License

No license has been specified for this project.
