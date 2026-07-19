# Security in Microservices — OAuth 2.0, JWT, Keycloak (Theory Notes)

## 1. OAuth 2.0

OAuth 2.0 is an **authorization** framework (not authentication by itself). Its main idea: a client gets a token from an Authorization Server, without ever handling the user's actual password — it gets delegated, scoped access to a resource on the user's behalf.

Key roles:
- **Resource Owner** — the user.
- **Client** — the application requesting access.
- **Authorization Server** — issues tokens, e.g. **Keycloak**.
- **Resource Server** — the API/microservice hosting protected data.

### Grant types (flows)

| Grant type | Use case | Notes |
|---|---|---|
| Authorization Code (+ PKCE) | Web apps, SPAs, mobile apps | Standard, most secure, used for user login |
| Client Credentials | Service-to-service (no user involved) | Common between microservices |
| Refresh Token | Getting a new access token without re-login | Used alongside other flows |
| Resource Owner Password Credentials | Legacy / fully trusted first-party apps | Deprecated — app sees the user's password, avoid |
| Implicit | (deprecated) token returned in URL fragment | Replaced by Authorization Code + PKCE |

**PKCE** (Proof Key for Code Exchange) — required for public clients (SPA, mobile) that can't safely store a client secret. The client generates a random `code_verifier`, sends its hash (`code_challenge`) with the initial redirect, then sends the raw `code_verifier` when exchanging the code. This proves the code-exchange request comes from the same client that started the flow — protects against authorization-code interception.

### Authorization Code Flow

![OAuth 2.0 Authorization Code Flow](oauth2-authorization-code-flow.svg)

1. User clicks **Login** in the Client App.
2. Client redirects the browser to Keycloak's login page.
3–4. User authenticates **directly on Keycloak's page** — the Client App never sees the password.
5. Keycloak redirects back to the Client App with a short-lived **authorization code**.
6. Client App exchanges the code (server-to-server call, + `client_secret`) for tokens.
7. Keycloak returns an **access_token** (+ refresh_token).
8. Client App calls the Resource Server with `Authorization: Bearer <access_token>`.
9. Resource Server validates the token and returns data.

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

⚠️ The payload is only **base64-encoded, not encrypted** — anyone can decode and read it. Never put secrets in a JWT payload.

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

An open-source **Identity and Access Management (IAM)** server implementing OAuth 2.0 and OIDC — the centralized Authorization Server / Identity Provider for a system of microservices.

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
-
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
