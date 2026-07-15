# Passkey-first identity

The unreleased Aether Identity `0.6.0.0` is a storage-neutral Kotlin Multiplatform identity
authority. Publishing is blocked until every release gate in the
[deployment guide](deployment.md#release-verification) passes. It uses
discoverable WebAuthn passkeys for people, organization-bound device grants for the CLI, and
scoped service credentials for automation. Password authentication, identity JWT fallback,
legacy identity sessions, and global groups or permissions are not part of this subsystem.

The generic authentication and session facilities in `aether-core` remain available to unrelated
applications. Do not combine those facilities with an Aether Identity session or treat a core role
as an organization role.

## Modules and targets

| Module | Purpose | Targets |
| --- | --- | --- |
| `aether-auth` | Models, protocols, middleware, runtime and store contracts | JVM, wasmJs, wasmWasi |
| `aether-auth-postgresql` | PostgreSQL RPC and PostgREST-compatible storage | JVM, wasmJs, wasmWasi |
| `aether-auth-firestore` | Firestore REST transactions | JVM, wasmJs, wasmWasi |
| `aether-auth-summon` | JVM SSR and browser passkey/account UI | JVM, wasmJs |
| `aether-auth-oidc` | Optional OIDC federation | JVM, wasmJs, wasmWasi |
| `aether-auth-saml` | Optional SAML federation | JVM, wasmJs, wasmWasi |
| `aether-auth-scim` | Optional SCIM provisioning | JVM, wasmJs, wasmWasi |
| `aether-auth-testkit` | Non-published adapter conformance fixtures and in-memory test store | JVM, wasmJs, wasmWasi |

`aether-auth` has no runtime dependency on Summon, PostgreSQL, Firestore, OIDC, SAML, or SCIM.
Browser wasmJs code may use public DTOs and `navigator.credentials`; it must not receive an
`IdentityRuntime`, secret resolver, authority key, storage credential, session token, or token
digest. wasmWasi exposes the same JSON authority APIs and never embeds browser UI.

## Route ownership

| Prefix | Resources |
| --- | --- |
| `/identity/v1/passkeys` | registration, discoverable authentication, management and step-up |
| `/identity/v1/sessions` | logout and device/session revocation |
| `/identity/v1/recovery` | recovery-code and restricted enrollment flows |
| `/identity/v1/invitations/enroll` | atomic invite-only onboarding into a restricted passkey-enrollment session |
| `/identity/v1/organizations/{organizationId}` | organization, membership, invitation, service-identity and bounded audit-event resources |
| `/identity/v1/device` | authenticated, CSRF-protected JSON POST inspection plus fixed approval and denial JSON routes |
| `/identity/v1/federation/{tenantId}/{providerId}` | OIDC and SAML starts/callbacks |
| `/oauth/device_authorization` | RFC 8628 device authorization |
| `/oauth/token` | device-code and refresh-token exchange |
| `/scim/v2` | RFC 7643/7644 tenant provisioning |

Every organization resource carries an explicit organization ID. There is no server-side
"selected organization" and no workspace-global token. A client may remember a preferred
organization locally, but it must still send the ID in every organization-scoped request.
The audit stream is read at
`GET /identity/v1/organizations/{organizationId}/audit-events`; it requires
`audit.read`, uses opaque newest-first keyset cursors, and exposes only the redacted public
audit view documented in the [authentication API](../api-reference/authentication.md#organization-audit-events).

RFC 8628 public clients must send a bounded `client_id` to both
`/oauth/device_authorization` and `/oauth/token`. The authority persists that identifier on the
grant and token family; a different or missing client ID cannot poll, exchange, rotate, or revoke
the credentials. `client_name` is optional display text and never substitutes for `client_id`.
The authorization response intentionally omits optional `verification_uri_complete`. The user opens
the plain `verification_uri` and manually enters the human code; Aether never places that code in a
query string or route segment.

Device grants expire after ten minutes. Device codes contain 256 bits of entropy; human
`XXXX-XXXX` codes carry 40 bits and are compared through the atomic store command. Polling starts
at five seconds, and each RFC `slow_down` response increases the required interval. Approval binds
one explicit organization and an explicit subset of requested capabilities. Exchange produces a
15-minute opaque access token and a rotating 30-day refresh token. Only keyed digests are stored;
refresh replay revokes the entire token family and emits an audit event.

### Federation HTTP boundary

OIDC and SAML expose exactly two routes per configured tenant/provider:

```text
GET  /identity/v1/federation/{tenantId}/{providerId}/start
GET  /identity/v1/federation/{tenantId}/{providerId}/callback   # OIDC
POST /identity/v1/federation/{tenantId}/{providerId}/callback   # SAML HTTP-POST
```

Mount `OidcFederationHttpMiddleware` and `SamlFederationHttpMiddleware` only on an identity
authority host. Their registries return `Found` only when the route tenant/provider exactly matches
the immutable provider configuration. Return `NotOwned` only to pass an exact provider route to the
other installed adapter; return `Missing` for an unknown or disabled provider and `Unavailable`
when configuration cannot be read. This explicit ownership result lets OIDC and SAML compose on the
same prefix without treating a registry failure as fallthrough.

Both middlewares require an injected atomic, single-use callback-state store. The browser cookie
contains only a fresh 256-bit opaque selector and is always `Secure`, `HttpOnly`, `Path=/`, with no
`Domain`; PKCE verifiers, SAML request state, assertions, provider tokens and link material remain
server-side. OIDC uses `SameSite=Lax`. The SAML correlation cookie uses `SameSite=None` because an
explicit Lax cookie is not sent on a cross-site SAML HTTP-POST; it is short-lived correlation only,
never identity session authority. The identity session cookie remains `SameSite=Lax`. Missing or
mismatched SAML correlation fails before assertion handling. State-store implementations must
atomically delete before returning from `consume`,
expire records at the provider transaction deadline, hash selectors at rest, encrypt any protected
payload, call the state object's `destroy()` when evicting it without consumption, and never use a
client-serialized envelope as the source of truth.

For a distributed store, use `useForProtection` only inside an authenticated-encryption boundary
and rehydrate with the matching `restore` factory after verifying the envelope. The protected
envelope stays in server storage; it is never the callback cookie, URL state, response body or log
payload.

Provider redirect endpoints and the post-login success URL are explicit allowlists in each
registration. Starts cannot accept a caller-supplied return URL. Successful callbacks use
`FederatedIdentitySessionService` to load an active user and atomically persist a `SESSION`
assurance session plus `session.created` audit evidence. The session records `oidc` or `saml`, the
organization, provider storage key and external-identity ID. The session token is delivered only in
the configured HttpOnly identity cookie. A five-minute, script-readable `__Host-aether_csrf`
handoff cookie carries the session-bound CSRF header value; it is never placed in `Location`, and it
does not authenticate a request by itself.

Callback responses are `Cache-Control: no-store` and expose only a stable generic code, message,
request ID and retryability. OIDC callbacks reject duplicate or unknown query fields and any body.
SAML callbacks require one bounded `application/x-www-form-urlencoded` body containing exactly one
`SAMLResponse` and one `RelayState`. Every start first acquires an exact enabled-provider lease and
persists it in server callback state and the single-use challenge. Callbacks, replay/link commands,
atomic JIT provisioning and federated session creation all revalidate that same lease. Every
provider-control compare-and-set transition advances its version; disabling additionally advances
the logical session epoch, while re-enabling retains that epoch. Exact lease versions invalidate
stale callbacks and the epoch invalidates existing sessions without rewriting or scanning session
rows.

## Wire contract

- JSON fields use camelCase. Protocol-mandated OAuth fields retain their RFC snake-case names.
- Enum values use lowercase snake case.
- Timestamps use RFC 3339 UTC and UUID identity values use canonical string form.
- Binary values use unpadded base64url.
- WebAuthn start responses contain `{ceremonyId, publicKey}`. Finish requests contain the opaque
  ceremony ID and the browser credential envelope.
- Identity failures contain only `error.code`, a fixed generic `error.message`, `requestId`, and
  `retryable`. Exception text, provider responses, assertions, secrets and credential material are
  never copied into the response.
- Unauthorized organization resources and nonexistent organization resources return the same
  `not_found` response.

The compile-tested Seen FEL-634 examples live in
[`contract-fixtures/seen-fel-634`](../../contract-fixtures/seen-fel-634/README.md). Those are
consumer views, not serialized store entities.

## Authorization model

Built-in organization roles are `owner`, `admin`, `publisher`, and `viewer`. Roles apply only to a
membership in one organization. Owner and admin receive the documented identity-management
capabilities; publisher and viewer receive read-only identity metadata. Applications extend these
grants through `CapabilityResolver`. For example, Seen can map a publisher membership to
`package.publish` without introducing a global Aether permission. Resolver output is suppressed
for recovery-assurance sessions and cannot grant any capability in `Capability.AETHER_OWNED`, so an
application mapping cannot override the fixed identity role matrix.

SCIM group mappings may grant `admin`, `publisher`, or `viewer`, but never `owner`. Owner creation,
promotion, transfer, and last-owner changes remain passkey-authorized native identity operations.

Device and service principals carry an explicit organization and a closed, allowlisted capability
set. A device grant is approved for exactly one organization. Service credentials are tied to one
service identity in one organization and may carry application capabilities only: Aether identity-
management capabilities and platform account recovery are never delegable. Their scopes must also
be a subset of the managing actor's effective capabilities in that exact organization. Sensitive
owner, destructive, and service-credential actions require passkey authentication no older than
five minutes.

Service credentials contain 256 bits of entropy, are displayed once, default to a 30-day lifetime,
and cannot exceed 90 days. Rotation permits a ten-minute overlap for an orderly deployment; either
credential can be revoked immediately. The store persists keyed digests and bounded public
prefixes, never the presented secret.

## CLI device authorization

The JVM CLI uses the RFC 8628 routes and never accepts a workspace-global grant:

```text
aether-cli auth login [--server URL] [--scope SCOPE] [--no-store]
aether-cli auth whoami [--server URL]
aether-cli auth org list [--server URL]
aether-cli auth org use <organization-id> [--server URL]
aether-cli auth logout [--server URL]
```

`auth login` verifies secure credential storage before starting authorization. Persistent storage
is limited to macOS Keychain, Windows DPAPI-backed Credential Manager storage, or Linux Secret
Service. If the platform store is unavailable, login fails before issuing a device grant unless the
operator explicitly passes `--no-store`. That flag keeps the one login result out of persistent
storage, so later CLI processes cannot reuse it. `auth org use` records only a preferred explicit
organization ID alongside the credentials; every API request still includes its organization.

## Running the example

The `example-app` is a real KMP app: JVM serves a Summon SSR shell and wasmJs performs browser
WebAuthn through `NavigatorCredentialsPasskeyClient`.

```text
AETHER_IDENTITY_BOOTSTRAP_SECRET=a-development-secret-at-least-16-chars \
  ./gradlew :example-app:run
```

Open `http://localhost:8080/identity/bootstrap` and enter the same disposable secret to create the
first owner and organization. The Gradle run task builds and serves the wasmJs browser distribution
from the same origin. It mounts the framework-neutral `IdentityHttpApi` behind
`IdentityMiddleware`, enforces a 1 MiB streaming request limit, and demonstrates bootstrap,
passkey, recovery, explicit-organization, RFC 8628 device-approval, and service-identity routes.

The executable deliberately uses `aether-auth-testkit`'s process-local `InMemoryIdentityStore` and
fresh in-memory peppers. All identities and keys disappear on restart. This is a runnable protocol
example, not a production storage choice: deployed authorities must use the PostgreSQL or Firestore
adapter, external secret resolution, reviewed migrations/indexes, exact HTTPS origins, and the
startup isolation gates described in the deployment guide.

Continue with [Security and threat model](security.md), [Deployment and operations](deployment.md),
and the [0.6 migration guide](../migrations/0.6-passkey-identity.md).
