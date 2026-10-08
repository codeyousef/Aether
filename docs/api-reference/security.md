# Secure application pipeline

`aether-core` provides policy-neutral primitives for a fail-closed private API. Applications still
own identity resolution, grant storage, resource loading, idempotency receipts, and audit sinks.
Use `secureApplicationPipeline(profile)` to make their registration order reviewable rather than
assembling a different order per route.

## Fixed middleware order

`SecureApplicationPipelineProfile` registers these stages in order:

1. request identity and trusted connection metadata;
2. transport limits and type validation;
3. safe error and diagnostic boundary;
4. authentication;
5. exact-origin and session-bound CSRF validation;
6. tenant or device grant resolution;
7. object/action authorization;
8. idempotency;
9. redacted audit;
10. the application use case.

Middleware unwinds in reverse order. The safe boundary therefore covers authentication, grant,
authorization, idempotency, audit, and use-case failures. Request identity and transport validation
remain outside it so malformed transport input is rejected before authentication work. Each of
those two outer stages must emit only bounded canonical errors.

```kotlin
val privatePipeline = secureApplicationPipeline(
    SecureApplicationPipelineProfile(
        requestIdentity = requestIdentity,
        transportValidation = requestLimits,
        safeErrorBoundary = recovery,
        authentication = identityMiddleware,
        originAndCsrf = originAndCsrf,
        tenantOrDeviceGrant = resolveGrant,
        objectActionAuthorization = authorize,
        idempotency = operationReceipts,
        redactedAudit = audit
    )
)
```

Do not reorder stages on an individual endpoint. In particular, no mutation may run before CORS,
CSRF, current grant, current policy, and idempotency checks complete.

## Exact credentialed CORS

`ExactCorsMiddleware` accepts canonical `http` or `https` origins only. Configure every deployment
origin explicitly. Wildcards, user-info, query strings, fragments, paths, duplicate `Origin`
headers, missing origins on unsafe methods, unlisted methods, and unlisted request headers fail
with `403 cors_denied` before `next`. A valid preflight ends with 204 and never invokes the use
case.

```kotlin
val cors = ExactCorsMiddleware(
    ExactCorsConfig(
        allowedOrigins = setOf("https://app.example.com"),
        allowedMethods = setOf(HttpMethod.GET, HttpMethod.POST),
        allowedHeaders = setOf("Content-Type", "X-CSRF-Token"),
        allowCredentials = true
    )
).asMiddleware()
```

CORS is not authentication. Non-browser clients can send any origin; authentication and policy
checks remain mandatory.

## Resolved authorization and feature gates

For versioned or leased permissions, use `requireCurrentResolvedAuthorization`. Its
`AuthorizationValidityResolver` re-reads the resource's current epoch and expiry during execution;
stale and expired grants are rejected before application policy or use-case code runs.

`requireResolvedAuthorization` reads typed actor and resource values only from exchange attributes
populated by trusted upstream middleware. The operation is a server-selected enum or sealed type.
Policy code receives `ResolvedAuthorizationInput<Actor, Resource, Action>`; query and body claims
cannot substitute any of those values. Missing trusted values and policy denials return stable
bounded categories without exposing whether another tenant owns the resource.

`BoundedAuthorizationRevocationCache` is a process-local optimization for authoritative policy
results. It enforces a maximum entry count and a TTL no greater than 15 seconds. Every lookup takes
the currently observed epoch; a transition evicts the old result immediately. Shared persistent
grant state remains authoritative across instances.

`requireFeature(FeatureId("vault.write"), provider)` similarly takes a server-selected feature key.
The provider resolves current state from trusted configuration. A disabled route returns
`404 feature_disabled` before the use case; hiding navigation alone is not enforcement.

Generic `installAuthorization` role checks remain available for unrelated protocols. They are not a
substitute for tenant, device, object, epoch, or expiry policy and must not be placed in the
`objectActionAuthorization` slot for those resources.

## Private response headers

Install `PrivateSecurityHeaders` before route dispatch. It applies `Cache-Control: no-store`, HSTS,
`nosniff`, clickjacking, referrer, permissions, and a deny-by-default CSP to success, redirect, and
error responses. The default CSP allows same-origin connections, forms, images, scripts, and
styles, plus `data:` images. It contains no `unsafe-inline` or `unsafe-eval` source.

Application shells that contain generated inline script or style must supply an exact nonce or
SHA-256/384/512 hash through `PrivateCspSourceProvider`. `CspDynamicSource` is the only dynamic CSP
source type; arbitrary directives cannot enter the policy. Derive these values from the actual
built shell rather than maintaining an unrelated allowlist.

Public assets use the separate `PublicCacheHeaders` profile. Never install a public cache profile
on an authenticated shell or private API. `ProxyConfig.PrivateObjectTransfer` strips browser
`Cookie`, `Authorization`, and `X-CSRF-Token` values after all per-request overrides, disables
redirect and forwarding behavior, and marks the response `no-store`. Add only a narrowly scoped
object-store credential.

## Rate limits

`RateLimitMiddleware` permits the request that reaches the configured limit and rejects later
requests. Usage increments must be positive. Rejections carry `X-RateLimit-*` and a positive,
ceiling-rounded `Retry-After` when headers are enabled. In-memory state has an explicit bucket
bound and fails closed at capacity. For sensitive routes, use `serverResolvedRateLimitKey` with a
server-declared `RateLimitNamespace`, a trusted subject attribute, and `requireKey = true`; never
derive a quota namespace from request fields. Use a persistent/shared `QuotaProvider` whose
`recordUsage` operation is atomic for multi-instance deployments.
