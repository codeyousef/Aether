# Application request authority

`IdentityRequestAuthorityGuard` (0.8.1.0) lets an application use Aether Identity's canonical
Origin, CSRF and public-authority validation while executing its own handler on the original
Exchange. It shares the implementation used by `IdentityHttpApi`; it neither remaps a route nor
runs an identity action.

```kotlin
val resolver = CompositeIdentityContextResolver(
    StoreBackedIdentityContextResolver(store, runtime, identityConfig),
    identityConfig,
    IdentityBearerAuthenticator { deviceAuthorization.authenticateAccessToken(it) }
)
val guard = IdentityRequestAuthorityGuard(runtime, identityConfig, identityHttpConfig)
// Install IdentityMiddleware(resolver, runtime.clock, runtime.secureRandom) first.
// Inside a routed application POST handler:
guard.protect(exchange) { original ->
    // Enforce this application's resource/capability policy, then perform the mutation.
    applicationHandler(original)
}
```

`protect` returns true only after invoking the callback once; on denial it writes a canonical
identity error and returns false. `asMiddleware()` protects a downstream application pipeline.
Only the guard checks are caught and mapped to safe authority errors; callback exceptions and
cancellation keep their normal application/pipeline behavior. The callback receives the same
request, headers, body, connection metadata, attributes and response. The guard does not read the
body or change its route. It sets the usual no-store and request-ID response metadata.

Authenticated cookie mutations require exactly one allowed Origin and the configured CSRF header,
the actual resolved user session, its versioned session pepper and a constant-time digest match.
Missing/duplicate/invalid/query-supplied tokens and wrong origins deny before the callback. Mixed
cookie/bearer and duplicate Authorization credentials deny during resolution and in the guard.
Recovery/bootstrap/invitation enrollment authority cannot call application handlers. Anonymous
requests cannot use the guard to authenticate. Supply `IdentityHttpApiConfig` consistently if the
CSRF header or request-ID header is customized.

Native device/service requests must first pass `CompositeIdentityContextResolver` with their actual
configured token authenticator. They use exactly one bearer credential, no identity session cookie,
and their canonical device/service principal. Cookie CSRF cannot replace a native credential or
upgrade its capabilities. The guard enforces request authority but does not grant a device permission,
organization role, resource ownership, decryption or security-administrator capability.

In staging/production the original request must resolve to the configured public HTTPS authority.
Only configured trusted CIDRs may supply forwarding metadata; direct peers cannot override the
connection's scheme/Host through spoofed forwarding headers. Exact configured origins remain required
for cookie mutations. Use the same proxy/public-base configuration as the identity authority.

Both JVM listeners preserve repeated header values using the transport's `getAll` view, including
identical Authorization, Origin, CSRF, idempotency and device-grant fields. HTTP/2 normalizes field
names and combines Cookie fields according to its cookie representation. Multiple Cookie fields
or duplicate cookie names (including combined HTTP/2 cookies) fail with a safe transport 400 before
dispatch. Ordinary distinct cookie pairs remain usable. Do not infer security-header uniqueness
from a first-value getter; use `getAll(name).singleOrNull()` for application exact-one policies.

In 0.8.1.0, plaintext listeners use HTTP/1.1; HTTP/2 is opt-in over TLS through ALPN on both
listeners (`http2Enabled=true` in the TLS configuration and `maxChunkSize >= 16_384`).
TLS listeners retain HTTP/1.1 by default. The installed Vert.x h2c upgrade handler overwrites duplicate fields while converting
an upgrade request, before Aether can inspect them. Cleartext HTTP/2 is therefore disabled rather
than admitting a request with lost security metadata. Negotiating plaintext clients fall back to
HTTP/1.1; clients using HTTP/2 prior knowledge must use TLS. Existing response/body limits and deadlines apply to both supported protocols.
