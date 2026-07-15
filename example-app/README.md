# Aether identity example

This Kotlin Multiplatform reference app runs a complete, development-only Aether Identity
authority:

- JVM uses the real JCA/SecureRandom identity runtime, runs `DefaultIdentityService.start()`
  readiness checks, mounts `IdentityMiddleware` and `IdentityHttpApi`, and serves Summon SSR plus
  the generated browser assets.
- wasmJs hydrates the same UI, invokes `navigator.credentials`, keeps opaque session cookies
  HttpOnly, and retains the session-bound CSRF token only in `sessionStorage`.
- `InMemoryIdentityStore` and all key material are process-local and disappear on restart. The
  example is intentionally unsuitable for production or multi-process use.
- Organization context comes only from explicit
  `/identity/v1/organizations/{organizationId}/...` routes. Device and service bearer credentials
  are resolved without a workspace-global selection or JWT fallback.
- Device grants may request the documented tenant identity scopes. Service credentials are limited
  to application scopes (`content.read`, `content.publish`, and `package.publish` in this example),
  and a managing actor may delegate only scopes they effectively hold in that organization.

Set a disposable first-owner secret and run the app:

```text
AETHER_IDENTITY_BOOTSTRAP_SECRET=a-development-secret-at-least-16-chars \
  ./gradlew :example-app:run
```

Open `http://localhost:8080/identity/bootstrap`, enter the same one-time secret, and create the
first owner and organization. The browser receives a restricted 15-minute enrollment session,
stores its CSRF value, redirects to `/identity`, enrolls a passkey, signs in with it, and displays
the first ten recovery codes once.

Other executable flows include:

- username-free passkey sign-in and distinct passkey step-up routes;
- recovery-code entry at `/identity/recovery`, restricted replacement-passkey enrollment, and
  one-time replacement recovery codes;
- organization listing through explicit tenant routes;
- RFC 8628 device approval. The CLI verification URI opens `/identity`; the signed-in browser
  accepts the human code in a bounded JSON POST body, never in a query string or route; the browser
  inspects `/identity/v1/device` and posts approval or denial to the fixed JSON routes.

The run task builds `:example-app:wasmJsBrowserDistribution` and serves its JavaScript and wasm from
the same origin. `PORT` may override port 8080; access the example through `localhost` so the exact
configured WebAuthn origin and RP ID continue to match.

For production, replace this development factory with `aether-auth-postgresql` or
`aether-auth-firestore`, durable externally managed secrets, reviewed migration/index resources,
TLS, deployment-specific origins, audit retention, and the startup isolation checks documented in
[`docs/identity`](../docs/identity/README.md). Never persist or deploy the example's in-memory store
or ephemeral peppers.
