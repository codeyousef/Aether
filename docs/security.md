# Security

Aether takes security seriously and provides built-in mechanisms to protect your application.

## Authentication & Authorization

See the [Authentication API](api-reference/authentication.md) documentation for details on:
- AuthMiddleware
- JWT / Session Auth
- **Role-Based Access Control (RBAC)** providing Groups and Permissions.

## CSRF Protection

Cross-Site Request Forgery (CSRF) protection is available via `CsrfMiddleware`.

```kotlin
pipeline.installCsrfProtection()
```

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
