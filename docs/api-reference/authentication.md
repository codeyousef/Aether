# Authentication and identity

The unreleased Aether `0.6.0.0` separates generic application authentication in `aether-core` from
the passkey-first identity platform in `aether-auth`. These are target-release contracts;
publication remains blocked by the [identity release gates](../identity/deployment.md#release-verification).

- Use `aether-auth` for people, organizations, passkeys, recovery, CLI device authorization,
  service identities and enterprise federation/provisioning.
- Use generic `aether-core` auth/session facilities only for unrelated application protocols.
  A core principal or session is not an Aether Identity session and cannot provide organization
  scope.

The removed password/JWT/ORM `aether-auth` API is documented in the
[0.6 migration guide](../migrations/0.6-passkey-identity.md).

## Identity entry points

`IdentityConfig` holds exact deployment policy and versioned secret references.
`IdentityRuntime` supplies the wall clock, secure random source, cryptography, HTTP client and
secret resolver. `IdentityStore` is storage-neutral and exposes high-level atomic commands instead
of transactions or `QueryAST`.

`DefaultIdentityService.start()` runs runtime self-tests, including the provider-local outbound
HTTP capability check, and the adapter's environment isolation check. The HTTP check makes no
application request; custom clients must explicitly implement it and otherwise fail closed in
staging and production. `resolve(exchange)` validates a cookie-backed identity without creating an
anonymous session.

Protocol services include:

- `WebAuthnService` for discoverable registration, username-free authentication and passkey
  step-up;
- `IdentitySessionIssuer` and account-management services for opaque session lifecycle;
- `IdentityOrganizationService` for atomic invite-only enrollment as well as authenticated
  organization and membership operations;
- identity recovery services for one-time code and restricted enrollment flows; and
- `IdentityDeviceAuthorizationService` for RFC 8628 and rotating CLI tokens.

Applications map these services to the fixed JSON routes described in the
[identity overview](../identity/README.md#route-ownership). The host owns bounded request reading,
cookie emission, middleware ordering and audit request metadata.

## Request context and guards

Install `IdentityMiddleware(resolver, clock, secureRandom)` to establish a request correlation ID
and place `IdentityContext` and `IdentityPrincipal` on the exchange. No authenticated state is
created for an anonymous request. If an application uses the guards without `IdentityMiddleware`,
install `identityRequestId(secureRandom)` before them so every rejection has the same request ID in
the response header and safe JSON envelope.

Available guards are:

- `requireIdentity()`;
- `requireRecoveryEnrollmentSession()`;
- `requireOrganization(expectedId)`;
- `requireOrganizationRole(...)`;
- `requireCapability(capability, resolver)`; and
- `requireRecentPasskey()`.

Install `identityRequestTime(clock)` before the recent-passkey guard so every downstream decision
uses one captured instant. Install `IdentityCsrfMiddleware` after identity resolution and before
cookie-authenticated mutation routes.

Organization and capability failures deliberately return `not_found`, matching a nonexistent
tenant resource. Organization roles remain inside `IdentityContext`; `IdentityPrincipal.roles` is
empty. Add application grants, such as `package.publish`, with a `CapabilityResolver`.

## Principal kinds

| Kind | Credential | Scope |
| --- | --- | --- |
| `user` | HttpOnly identity session established by passkey or recovery | membership in the explicit organization route |
| `device` | short-lived opaque access token from an approved RFC 8628 family | one organization and approved capabilities |
| `service` | scoped service credential | one service identity, organization and capability allowlist |

Recovery sessions are restricted and do not receive membership capabilities. Federated user
sessions retain their authentication method, but sensitive operations still require passkey
step-up.

## Invite-only enrollment

`POST /identity/v1/invitations/enroll` is the anonymous entry point for a new invited user. It
accepts `{ "token": "<opaque invitation token>", "displayName": "..." }`, consumes the pending
invitation atomically, and establishes a 15-minute recovery-assurance session whose authentication
method is `invitation`. The JSON response exposes safe session metadata and a session-bound CSRF
token; the opaque session credential is set only in the standard HttpOnly session cookie.

The restricted session may call passkey registration start and finish, or logout. Completing
registration revokes the restricted session and returns ten replacement recovery codes once. An
expired, reused, mismatched, concurrently consumed, or email-conflicting invitation produces only a
stable generic identity error and leaves no partial identity state. Authenticated users continue to
use the organization-scoped invitation acceptance endpoint.

## Organization audit events

`GET /identity/v1/organizations/{organizationId}/audit-events` returns the organization audit
stream in newest-first order. The caller must have the organization-scoped `audit.read`
capability. A caller without that capability, a caller scoped to another organization, and an
unknown organization all receive the same `not_found` response.

The optional `limit` query parameter is a canonical decimal integer from 1 through 100 and defaults
to 50. The optional opaque `cursor` is returned as `nextCursor`; pass it unchanged to read the next
page. Ordering uses `(occurredAt, id)` as a descending keyset, so equal timestamps are deterministic
and events inserted above a cursor do not move or duplicate the following page. Unknown, duplicate,
empty, non-canonical, or malformed query parameters produce `request_invalid`.

Each response event contains only its ID, actor, organization ID, action, target, outcome, bounded
reason code, timestamp, and safe request fields (`requestId`, method, path, and trusted-proxy flag).
The response never exposes audit payloads, user-agent values, client-IP pseudonyms, token or
credential digests, assertions, or other secret material.

## Errors

Use `respondIdentityError` or serialize `IdentityErrorEnvelope`. The DTO accepts only a stable
`IdentityErrorCode`, its fixed public message, a bounded request ID and the code's fixed
retryability. Built-in HTTP and identity middleware establish that ID before validation. Never
serialize an exception, database/provider message, assertion, credential, token or digest.

See [Identity security](../identity/security.md) for cookies, CSRF, trusted proxies, origins,
tenant isolation and redaction.
