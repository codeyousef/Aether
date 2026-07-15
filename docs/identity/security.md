# Identity security and threat model

This document is the security contract for the Aether passkey-first identity authority. It covers
the authority, storage adapters and browser UI. Application data protected by identity capabilities
needs its own threat model as well.

## Assets and trust boundaries

The protected assets are passkey public-key records and counters, authenticated sessions, recovery
codes, device and service credentials, federation links, provider configuration, audit evidence and
organization membership. Raw secret values exist only at issuance or presentation boundaries.
Persistent storage contains keyed digests and key versions, not session, recovery, device or service
credential secrets.

The trusted computing base includes the Aether authority process, selected storage adapter,
configured secret manager, platform cryptographic provider, TLS terminator and any explicitly
trusted reverse proxy. Browser JavaScript, user-agent and device labels, forwarded headers,
federation assertions, SCIM payloads, CBOR/COSE, JSON and XML are untrusted inputs. The database is
trusted to execute reviewed atomic functions or transactions but is not trusted with raw credential
secrets.

The design addresses phishing and credential replay, session fixation, CSRF, tenant confusion,
concurrent ceremony completion, credential substitution, cloned authenticators, refresh-token
replay, federation replay and signature wrapping, XML entity attacks, malicious proxy headers,
storage retry races and sensitive-data leakage. Compromise of a user's authenticator, authority
process, secret manager, TLS endpoint or host OS remains outside what application protocol checks
alone can prevent.

SAML XML is rejected before semantic or signature work when it exceeds 25 element levels or 30
attributes on one element. Those values are authority-wide hard ceilings, matching the May 2026
OpenSAML parser-hardening defaults; tenant configuration may lower them but cannot raise them.
DTD declarations, entity references, processing instructions, duplicate expanded attributes and
duplicate ID values are rejected independently of those resource limits.

## WebAuthn invariants

The first release accepts discoverable ES256 credentials only. Registration requests
`residentKey=required`, `userVerification=required` and attestation `none`. Authentication is
username-free unless a step-up ceremony deliberately restricts allowed credentials.

For every finish request, validate the exact ceremony type, challenge, RP ID and RP ID hash, exact
allowed origin, credential ownership, signature, UP and UV flags, backup flags and supported
extensions. JSON, CBOR, COSE and authenticator data are bounded before allocation growth. A
ceremony expires after five minutes, is bound to its same-site ceremony context and is atomically
consumed on every finish attempt, including malformed or failed attempts. Only one concurrent
completion may succeed.

If both the stored and returned signature counters are nonzero and the returned value does not
increase, quarantine the credential, deny authentication and audit the anomaly. Zero counters are
valid. Backup eligibility is immutable after registration; backup state may change and is tracked.

## Sessions, recovery and privilege changes

Identity session credentials use `selector.secret`. Persist only a versioned HMAC-SHA-256 digest.
Rotate the credential after authentication, step-up, recovery and privilege changes, and revoke the
predecessor in the same atomic operation. Session-family absolute expiry is never extended by
rotation. Revoke-all increments the user's session epoch so old records cannot become usable again.
Federated sessions also persist the issuing provider's logical session epoch. Provider disable
advances that epoch without rewriting session rows; every resolver, touch, and rotation compares it
to the current enabled control, so a disable/re-enable cycle cannot revive an older session.

Idle expiry is sliding but never extends the session-family absolute expiry. After a cookie digest,
user state and session epoch are verified, the resolver atomically advances `lastUsedAt` and
`idleExpiresAt` with a session-version precondition. A concurrent revoke or rotation fails closed;
an already-completed concurrent touch is accepted only after rereading and revalidating the same
cookie and user epoch. Routine touches deliberately do not emit audit events, avoiding an
authenticated-request audit flood; creation, rotation, revocation and security transitions remain
audited.

Recovery produces ten independently random 128-bit, single-use codes. Display them exactly once,
persist selector plus keyed digest, and replace the complete generation atomically. A successful
code creates a 15-minute restricted session that may enroll a passkey but cannot inherit
organization roles or perform general account actions. Enrollment revokes other sessions and
replaces remaining codes.

The production HTTP authority requires an explicitly injected recovery-attempt limiter and fails
startup when one is absent. Derive its lookup key from a keyed pseudonym of direct connection
metadata; never expose or persist the raw peer address. Clustered deployments need a shared,
bounded limiter backend. On exhaustion return only the generic `rate_limited` envelope, and fail
closed with `service_unavailable` when the limiter cannot make a decision.

Invitation enrollment follows the same restricted-session boundary. Verify the pending invitation
version, expiry, and keyed token digest in the same atomic command that creates the unique active
user, invited membership, acceptance audit event, and 15-minute `invitation` session. Concurrent
redemption, token mismatch, reuse, and email uniqueness conflicts must roll back every dependent
write. Never return the invitation token, token digest, or session credential in JSON. Passkey
completion revokes the invitation session and emits the first ten recovery codes exactly once.

Administrative recovery is disabled unless both a platform-level
`identity.account.recover` authorizer and a notification sink are configured. Organization owner or
admin roles never grant account recovery. The administrator must have passkey authentication within
five minutes; the delivered enrollment ticket is single-use and expires after 15 minutes. Creation,
delivery result, use, expiration and cancellation are audit events.

## RP ID and origin deployment rules

`publicBaseUrl` and every allowed WebAuthn origin are exact origins: scheme, lowercase host and an
optional explicit port, with no path, wildcard, user info, query or fragment. `publicBaseUrl` must
appear in `allowedOrigins`. The RP ID is a host, never a URL, and every allowed origin host must
equal it or be its subdomain.

Production and staging require non-loopback HTTPS. Plain HTTP is accepted only for development on
`localhost`, `127.0.0.1` or `::1`. Do not infer an RP ID or origin from `Host`, `Forwarded`,
`X-Forwarded-*`, deployment metadata or the first incoming request. Changing an RP ID prevents
existing passkeys from authenticating; treat it as a deliberate account migration, not a routine
configuration change.

## Cookies and CSRF

The default identity cookie is `__Host-aether_session; Secure; HttpOnly; SameSite=Lax; Path=/` with
no `Domain`. Keep that form unless a reviewed deployment constraint requires another cookie name.
Do not expose the session token to browser JavaScript and do not create anonymous identity sessions.

For cookie-authenticated `POST`, `PUT`, `PATCH`, `DELETE` and `CONNECT` requests, require both:

1. exactly one `Origin` header that exactly matches an allowed RP origin; and
2. exactly one `X-CSRF-Token` header whose value verifies against the digest stored with the current
   identity session.

Issue a new CSRF value with every new or rotated session. Never accept CSRF values from a query
string or form body. Query values leak into browser history, access logs and referrers. Bearer-only
device or service calls are not ambient-cookie requests, but still require their normal
organization and capability checks.

Apply the same URL rule to RFC 8628 human codes. Return the plain `verification_uri`, omit optional
`verification_uri_complete`, and resolve a manually entered code with a small, CSRF-protected JSON
POST body. Do not accept the code in a query string or path segment.

## Reverse proxies and request metadata

Direct connection scheme, host and peer address come from the request adapter. The default policy
is `DIRECT_ONLY`, which ignores forwarding headers. To terminate TLS at a proxy, set
`TRUSTED_CIDRS` to the smallest stable CIDR set that contains every legitimate direct proxy peer.
Honor forwarded scheme or host only when the direct peer is in that allowlist and the header shape
is unambiguous. Never trust a client-supplied forwarding header merely because the application is
"behind a proxy".

Production and staging dispatchers compare the resolved scheme and host to the exact configured
`publicBaseUrl` before processing a known identity route. Missing direct metadata, a mismatched
authority, or forwarding metadata from an untrusted peer fails with a generic correlated error.
HTTP adapters must therefore populate direct scheme, host and peer metadata; they must never copy
forwarded values into those direct fields.

The edge must replace, not append to, spoofable forwarding headers before sending a request to the
authority. Tests must cover an untrusted peer supplying `Forwarded`, `X-Forwarded-Proto`,
`X-Forwarded-Host` and `X-Forwarded-For`. A proxy configuration change is a security change and
requires origin and RP regression tests.

## Tenant isolation

Resolve organization scope from the explicit route ID and the authenticated membership, device
family or service identity. Do not resolve it from a server-side selected workspace. Organization
roles never populate `aether-core`'s global role set. Unknown and unauthorized tenant resources
return the same `not_found` envelope to prevent enumeration.

Service-credential allowlists contain application capabilities only. Reject every built-in Aether
identity-management capability and `identity.account.recover` at configuration time and again at
issuance/authentication boundaries. Issuance and rotation also require every scope to be present in
the managing user's effective capabilities for the explicit organization; process-wide allowlisting
alone never authorizes delegation.

Federation provider identity is `(tenant, provider, issuer, subject)`. Never merge or link by email.
SCIM deprovisioning disables only the tenant membership and tenant credentials; it does not delete
the stable user. Unknown SCIM groups grant no role, and SCIM group-to-role configuration rejects
`owner` so directory synchronization cannot create or promote organization owners. Disabling an
enterprise adapter blocks new starts, callbacks and provisioning, retains stable links, and
logically revokes sessions whose primary authentication came from that adapter. The retained rows
cannot authenticate; no provider-wide session scan or rewrite is part of the disable operation.
Sensitive operations still require passkey step-up.
Federated sessions therefore persist a typed `oidc` or `saml` primary method together with the
exact organization, provider storage key, provider session epoch and external-identity ID. Starts
acquire an exact enabled-provider lease before metadata discovery or other provider I/O. Callback
state and its persisted challenge carry that full lease, and callback consumption, replay/linking,
JIT provisioning and session creation revalidate it atomically. Every provider-control
compare-and-set transition advances the version; disabling additionally advances the logical
session epoch and re-enabling retains it. Exact lease-version checks reject pre-transition
callbacks, while the epoch check keeps pre-disable sessions invalid after re-enable without a
provider-wide session scan.

## Audit and redaction

Audit state changes in the same atomic storage operation as the mutation. Record a stable action,
outcome, target, actor, organization when applicable, request ID and bounded pseudonymous request
metadata. `IdentityAuditConfig` defaults to omitting User-Agent entirely. If
`AuditUserAgentPolicy.PSEUDONYMIZE` is selected, `IdentityAuditRedactor` stores only a keyed,
domain-separated `PseudonymousValue`; the raw header is not representable in
`AuditRequestMetadata`. Client addresses use a different pseudonym domain, preventing correlation
between attribute classes. A positive configured retention duration and bounded adapter purge own
the event lifecycle.

Never log or serialize:

- session, CSRF, recovery, device, service or enrollment-ticket secrets;
- keyed digests, private or symmetric keys, secret-manager values or authorization headers;
- WebAuthn assertions, signatures, credential public-key material or complete browser envelopes;
- OIDC tokens, SAML assertions, SCIM bearer credentials or upstream provider error bodies;
- raw IP addresses, unbounded user-agent values, database exception text or XML/CBOR parse input.

Federation callback cookies are lookup selectors, not containers. Do not serialize an OIDC PKCE
verifier, SAML authentication state, assertion, provider error, or success return URL into the
cookie or a query parameter. Consume the corresponding server-side record before protocol
completion so concurrent callbacks and browser replay cannot both proceed. OIDC correlation is
`SameSite=Lax`; SAML HTTP-POST correlation is the narrow `SameSite=None` exception because browsers
omit explicit Lax cookies on cross-site POST. Both are Secure, HttpOnly, `__Host-`, short lived and
contain only opaque selectors. Identity session cookies remain Lax. Keep the CSRF handoff cookie
`__Host-` scoped; it is deliberately short-lived and script-readable only because it is a
session-bound CSRF header value, not an authentication token.

Secret wrapper `toString()` methods are a defense in depth, not a logging policy. Use allowlisted
audit fields and test failure paths for redaction. If sensitive material enters logs, revoke the
affected family/key, preserve an access-controlled incident copy, purge normal log stores according
to policy and document the exposure window.
