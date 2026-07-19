# Security in Microservices — OAuth 2.0, JWT, Keycloak (Theory Notes)

## 1. OAuth 2.0

OAuth 2.0 is an authorization protocol. Its main idea: a client gets a token from an Authorization Server(eg. Keycloak, Google, AWS Cognito or your own custom server), 
without ever handling the user's actual password — it gets delegated, scoped access to a resource on the user's behalf.

Key roles:
- **Resource Owner** — the user.
- **Client** — the application requesting access.
- **Authorization Server** — issues tokens, e.g. **Keycloak**.
- **Resource Server** — the API/microservice hosting protected data.

### Grant types (flows)

A grant type = a way for the client app to get an access token. Different situation → different way to get the token. OAuth 2.0 defines a few standard grant types for different cases.

| Grant type | Use case | Notes |
|---|---|---|
| Authorization Code (+ PKCE) | Apps with a user — web app, SPA (React/Angular app in browser), mobile app | Standard, most secure, used for user login |
| Client Credentials | Service-to-service (no user involved) | Common between microservices |
| Refresh Token | Getting a new access token without re-login | Used alongside other flows |
| Resource Owner Password Credentials | Legacy / fully trusted first-party apps | Deprecated — app sees the user's password, avoid |
| Implicit | (deprecated) token returned in URL fragment | Replaced by Authorization Code + PKCE |

#### Authorization Code (+ PKCE)
The user logs in on Keycloak's own page, not inside your app. Your app first gets a short-lived **code**, then trades it for a real token in a separate, server-to-server call. Full step-by-step flow is diagrammed below.
```
Example: user clicks "Login" in a React app -> redirected to Keycloak's login page
-> logs in there -> app receives a code -> app exchanges it for a token
```

#### Client Credentials
No user involved at all — the service itself is the identity. It has its own `client_id` + `client_secret`, and gets a token that represents *the service*, not a person.
```
POST /token
client_id=order-service
client_secret=xxx
grant_type=client_credentials

-> { access_token: "...", expires_in: 300 }
```
Example: `order-service` calls `inventory-service` on a schedule — no user triggered this call.

#### Refresh Token
Gets a new access token once the old one expires, without asking the user to log in again.
```
POST /token
grant_type=refresh_token
refresh_token=xyz789

-> { access_token: "new_token...", refresh_token: "new_refresh..." }
```
Example: access token expires after 5 minutes; the app silently requests a new one in the background — the user stays logged in and notices nothing.

#### Resource Owner Password Credentials — deprecated
The app itself asks the user for username + password, then sends them directly to the Authorization Server.
```
POST /token
grant_type=password
username=john
password=secret123
```
Why avoid it: your app sees the real password — this defeats the whole purpose of OAuth2 (no MFA/2FA, no SSO, no consent screen). Kept around mostly for legacy systems that can't be changed yet.

#### Implicit — deprecated
Skipped the "code" step entirely — the token came back directly in the URL, right after login (used before PKCE existed, mainly for SPAs).
```
GET /auth?response_type=token&client_id=myapp&redirect_uri=...

-> redirect to https://myapp.com/callback#access_token=abc123&expires_in=3600
```
Why deprecated: the token sits in the URL fragment — can leak via browser history, server logs, or a malicious script on the page. No refresh token support either. Authorization Code + PKCE replaced it.

**PKCE** (Proof Key for Code Exchange) — required for public clients (SPA, mobile) that can't safely store a client secret. The client generates a random `code_verifier`, sends its hash (`code_challenge`) with the initial redirect, then sends the raw `code_verifier` when exchanging the code. This proves the code-exchange request comes from the same client that started the flow — protects against authorization-code interception.

### `client_id` and `client_secret`

**`client_id`** — public identifier for your app. Identifies *which app* is talking to the Authorization Server. Not secret — visible in URLs, browser network tab, logs. Similar to a username, but for an application, not a person.

**`client_secret`** — private password for your app. Proves the app is really who it claims to be. Used when exchanging a code for a token, or for the `client_credentials` grant. Must stay server-side only, never in a browser or mobile app bundle.

Both are set up in Keycloak when you register a client: Realm → Clients → create client → `client_id` (you choose it) + `client_secret` (Keycloak generates it, on the "Credentials" tab).

Typical Spring Boot config (`application.yml`):
```yaml
client:
  client-id: proxima
  client-secret: fe057b1348d23005c2da88adfe9686ba42df80d0
```

#### Confidential client vs public client

| | Confidential client | Public client |
|---|---|---|
| Has `client_secret`? | Yes | No |
| Example | Backend app, another microservice | SPA (React app in browser), mobile app |
| Why | Runs on your server — secret stays hidden | Runs on user's device — anyone can extract the secret from the code |
| Protection instead of secret | — | PKCE |

#### Where they're used
```
POST /token
grant_type=authorization_code
code=abc123
client_id=proxima
client_secret=fe057b1348d23005c2da88adfe9686ba42df80d0
```
Without a correct `client_secret`, Keycloak refuses to exchange the code for a token — even with a valid, stolen `code`.

**Short summary:** `client_id` = who's asking (public). `client_secret` = proof it's really them (private, backend-only). Public clients (SPA/mobile) skip the secret and use PKCE instead.

### Authorization Code Flow

![OAuth 2.0 Authorization Code Flow](oauth2-authorization-code-flow.svg)

1. User clicks **Login** in the Client App.
2. Client redirects the browser to Keycloak's login page.
3. User enters creds
4. User authenticates **directly on Keycloak's page** — the Client App never sees the password.
5. Keycloak redirects back to the Client App with a short-lived **authorization code**.
6. Client App exchanges the code (server-to-server call, + `client_secret`) for tokens.
7. Keycloak returns an **access_token** (+ refresh_token).
8. Client App calls the Resource Server with `Authorization: Bearer <access_token>`.
9. Resource Server validates the token and returns data.

After passing these 9 steps for eg Authorization Code (+ PKCE)
1. FE app(React) get <access_token> and add Authorization: Bearer <access_token> for every request of this user
2. BE validates the token locally, using math (signature check), not a network call:
3. Parse token: header.payload.signature
4. Get Keycloak's public key (already cached in memory, fetched once earlier)
5. Verify signature = SHA256/RSA check using cached public key   <- no network call
6. Check payload.exp > now()
7. Check payload.aud == "my-service"
8. If all pass -> request allowed, roles read from payload.realm_access.roles


## 2. OpenID Connect (OIDC)

A thin identity layer on top of OAuth 2.0. Requesting the `openid` scope also returns an **ID Token**.

| Token | Purpose | Consumed by |
|---|---|---|
| ID Token (JWT) | Proves *who the user is* (authentication) | The Client app itself |
| Access Token | Grants access to a resource (authorization) | Resource Servers / APIs |
| Refresh Token | Gets a new access token without re-login | Authorization Server only |

## 3. JWT (JSON Web Token)

A compact, URL-safe token format — most commonly used to implement OAuth2 access tokens. 3 parts, base64url-encoded, joined by dots:

```
eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0IiwicmVhbG1fYWNjZXNzIjp7InJvbGVzIjpbInVzZXIiXX19.dBjftJeZ4CVP-mB92K...
      HEADER                          PAYLOAD                                  SIGNATURE
```

- **Header** — algorithm (`alg`, e.g. `RS256`), token type.
- **Payload** — claims: `sub` (user id), `exp` (expiry), `iss` (issuer), `aud` (audience), plus custom claims like roles.
- **Signature** — proves the token wasn't tampered with.

![JWT](jwt.jpg)

⚠️ The **Header** and **Payload** is only **base64-encoded, not encrypted** — anyone can decode and read it. Never put secrets there.

### Symmetric vs asymmetric signing

| | Symmetric (HS256) | Asymmetric (RS256 / ES256) |
|---|---|---|
| Key | One shared secret | Private key (signs) + public key (verifies) |
| Who can verify | Anyone with the shared secret | Anyone with the public key |
| Who can forge | Anyone with the shared secret | Only the holder of the private key |
| Typical use | Single service | Microservices — issuer keeps the private key, all services get the public key |

This is why **RS256** is the standard for microservices with Keycloak: services can verify tokens locally without being able to mint fake ones.

### Local validation (no DB / no call per request)

- Each service verifies the JWT signature locally, using the issuer's **public key**.
- Public keys come from Keycloak's **JWKS** endpoint: `GET /realms/{realm}/protocol/openid-connect/certs` — fetched once and cached, refreshed only occasionally (e.g. on key rotation).
- This avoids a "call the auth server on every request" bottleneck and keeps services stateless — fits well with horizontal scaling.

![JWT Validation in Microservices](jwt-validation-microservices.svg)

### JWT vs reference (opaque) tokens — trade-off

| | JWT (self-contained) | Reference / opaque token |
|---|---|---|
| Validation | Local, via signature — fast, no network call | Requires calling the Authorization Server's **introspection** endpoint |
| Revocation | Hard — valid until it naturally expires (short expiry mitigates this) | Immediate — the Authorization Server can invalidate it right away |
| Best for | Most access tokens in a microservice system | High-security scopes / admin actions that need instant revocation |

## 4. Keycloak

An open-source **Identity and Access Management (IAM)** server implementing OAuth 2.0 and OpenID Connect — the centralized Authorization Server / Identity Provider for a system of microservices.

| Concept | Meaning |
|---|---|
| Realm | An isolated space of users, roles, clients — like a "tenant" |
| Client | An app registered in Keycloak (frontend, backend service, etc.) |
| Confidential vs public client | Confidential = has a client secret (backend apps); Public = no secret (SPA, mobile) |
| Role | Permission label (realm role or client role), embedded as a JWT claim |
| User Federation | Keycloak can delegate to an external store, e.g. LDAP / Active Directory |
| Identity Provider linking | Keycloak can delegate login to Google, GitHub, another OIDC provider |

### Typical microservices setup

- Keycloak = single Authorization Server for the whole system.
- An API Gateway (or each service individually) validates the JWT's signature using Keycloak's cached public key, checks `exp` / `aud` / `iss`, and reads roles from the token's claims for authorization.
- Services don't call Keycloak on every request — only to refresh cached public keys occasionally, or during login/refresh.

### Token propagation between services

- Roles are embedded as claims in the access token — e.g. `realm_access.roles` or `resource_access.<client>.roles`.
- Service A calling Service B **on behalf of a user** typically forwards the same access token, or exchanges it via **OAuth2 Token Exchange** (`urn:ietf:params:oauth:grant-type:token-exchange`, RFC 8693).
- Pure service-to-service calls with **no user context** typically use the **Client Credentials** grant — the token represents the calling service itself.

## 5. Refresh tokens & expiry

- Access tokens are short-lived (minutes) — limits damage if leaked.
- Refresh tokens are longer-lived, used to silently get a new access token without re-login.
- Never store refresh tokens in `localStorage` for browser apps (XSS risk) — prefer `httpOnly` secure cookies or a backend-for-frontend pattern.
- Keycloak supports refresh token rotation and revocation (e.g. on logout).

## 6. Common security pitfalls

- Storing JWTs in `localStorage` in SPAs → vulnerable to XSS token theft.
- Not validating the `aud` (audience) claim → a token meant for Service A could be replayed against Service B.
- Not checking `exp` server-side.
- Symmetric (HS256) signing shared across many services → any compromised service can forge tokens for the whole system.
- Trusting client-supplied roles without validating the signature first.
- No revocation strategy — a stolen long-lived token stays valid until it naturally expires.

---

## Control questions (self-check)

1. Is OAuth 2.0 an authentication protocol or an authorization protocol? What does OpenID Connect add?
- More authentication, but 
2. Why shouldn't the Resource Owner Password Credentials grant be used in modern apps?
-
3. What's the difference between the ID token and the access token in OIDC?
-
4. Why is a JWT not "encrypted"? What follows from that?
-
5. Why is RS256 preferred over HS256 for token validation across multiple microservices?
-
6. How does a microservice validate a JWT's signature without calling Keycloak on every request?
-
7. What is the JWKS endpoint, and why does key rotation matter for it?
-
8. What happens if a service doesn't validate the `aud` claim? Give an attack scenario.
-
9. What grant type would you use for pure service-to-service calls with no user involved?
-
10. Why shouldn't tokens be stored in `localStorage` in a browser app?
-
11. What is PKCE, and why is it required for public clients (SPA/mobile) using the Authorization Code flow?
-
12. JWTs are stateless and self-contained — how would you immediately revoke a compromised one?
-
13. What's the difference between a reference token and a JWT access token in Keycloak, and when would you use each?
-
