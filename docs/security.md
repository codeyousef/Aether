# Security

Aether takes security seriously and provides built-in mechanisms to protect your application.

## Authentication & Authorization

Use the [passkey-first identity platform](identity/README.md) for people, organizations, CLI device
authorization, service identities and federation. It uses organization-scoped roles and
capabilities; password authentication, identity JWT fallback and global groups/permissions are not
supported. Generic `aether-core` authentication remains separate for unrelated application
protocols.

## CSRF Protection

Generic `aether-core` applications can install `CsrfMiddleware`:

```kotlin
pipeline.installCsrfProtection()
```

Aether Identity instead validates an exact allowed `Origin` and a session-bound CSRF header token
on state-changing browser requests. Query-string and form-body CSRF tokens are rejected. Follow the
[Identity CSRF policy](identity/security.md#csrf-and-cross-origin-requests) when mounting
`/identity/v1`.

## Security Headers

For enhanced security, use the `SecurityHeaders` middleware. It sets:
- `Content-Security-Policy`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Strict-Transport-Security` (HSTS)

```kotlin
pipeline.installSecurityHeaders()
```

## HTTPS / SSL

Aether supports HTTPS out of the box on the JVM.

### Configuration

You can configure SSL keys in your `VertxServerConfig`.

```kotlin
val config = VertxServerConfig(
    ssl = SslConfig(
        enabled = true,
        keyPath = "path/to/key.pem",
        certPath = "path/to/cert.pem"
    )
)
```

For development, you can enable self-signed certificates:

```kotlin
val config = VertxServerConfig(
    ssl = SslConfig(
        enabled = true,
        selfSigned = true
    )
)
```

## SQL Injection

The `aether-db` ORM uses parameterized queries internally, which protects against SQL injection attacks by default. Always use the ORM methods rather than raw SQL strings when handling user input.

## XSS Protection

When using `aether-ui` (Composable DOM), output is escaped by default, mitigating Cross-Site Scripting (XSS) risks. If you are rendering raw HTML strings, ensure you sanitize user input manually.
