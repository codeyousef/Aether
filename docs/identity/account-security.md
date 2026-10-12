# Opt-in account security in 0.8.0.0

Passkeys remain the default. An application can explicitly pass `accountSecurity` to
`IdentityHttpApi` to enable the versioned password and contact-verification routes below.
The service composes the existing identity runtime, store, session issuer and HTTP authority.
Generic authentication middleware cannot supply Aether Identity sessions.

```kotlin
val accountSecurity = IdentityAccountSecurityService(
    store = identityStore,
    runtime = runtime,
    config = identityConfig,
    attempts = sharedDeploymentAttemptLimiter,
    passwordProvider = JvmPasswordProvider(),
    emailDelivery = approvedDeliveryProvider
)
// Pass accountSecurity to IdentityHttpApi, after IdentityMiddleware resolution.
```

Omit `passwordProvider` to keep password operations disabled; omit `emailDelivery` to disable
challenge delivery. Omit the service altogether to preserve the existing passkey configuration.
A deployment must supply a shared account/network attempt limiter that fails closed, an approved
email provider, runtime secrets, and initialized durable storage. The sample names above refer to
application-supplied providers, not built-in allow-all or fake production implementations.

## Passwords

| Endpoint | Request | Authority |
| --- | --- | --- |
| POST `/identity/v1/password/login` | `{email,password}` | Exact allowed Origin, bounded account and trusted-network attempts |
| POST `/identity/v1/password/replace` | `{password}` | Recent passkey/step-up, or explicit constrained recovery; session CSRF |
| POST `/identity/v1/password/disable` | `{}` | Recent passkey/step-up; session CSRF |

Passwords have no public account-registration route. Existing invitation/bootstrap enrollment
and maintained recovery establish the account first. A password can then be enrolled or replaced
under that account's strong authority. An application explicitly permitting constrained password
recovery must install `RestrictedEnrollmentRoutePolicy.PASSKEY_OR_PASSWORD_ENROLLMENT` on
`IdentityMiddleware`; its default still permits passkey enrollment and logout only.

The JVM provider uses JCA `PBKDF2WithHmacSHA256`, 600,000 iterations, a random 16-byte salt and a
32-byte result, persisted as `pbkdf2-sha256-v1`. It accepts 15–1,024 characters for enrollment,
with a 1,024-byte UTF-8 hard limit, and rejects NUL; it never trims, normalizes or truncates
passwords. Login accepts 1–1,024 characters within the same byte limit. Unknown, disabled and
unenrolled accounts run the same KDF as an invalid password. Two hashing jobs can run across JVM
provider instances; excess work is rejected without queuing. Hash-provider failures are generic.
The service bounds an operation to 15 seconds; injected asynchronous providers must cooperate
with cancellation. These parameters follow the PBKDF2 guidance in the
[OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
This is not a FIPS certification claim.

Each successful login creates fresh opaque cookie and CSRF material. Password sessions carry
`SessionAuthenticationMethod.PASSWORD` and only `AuthenticationAssurance.SESSION`. They cannot
replace passkeys, approve CLI device grants, or acquire passkey/step-up authority. Applications
must separately enforce enrolled-device, content-key and security-administrator permissions.
Password replacement/disabling commits the verifier, user epoch increment, revocation of all
existing sessions/device token families and tokens, and redacted audit together. It clears the
browser cookie; authenticate again afterwards. Password reset has no email-link shortcut.
Existing passwords or legacy sessions are never imported automatically.

## Verified contact email

| Endpoint | Request | Authority |
| --- | --- | --- |
| POST `/identity/v1/email/request` | `{}` | Existing account session and CSRF |
| POST `/identity/v1/email/verify` | `{code}` | Same account/contact/epoch, session and CSRF |
| GET `/identity/v1/email/status` | — | Existing account session |
| POST `/identity/v1/email/change` | `{email}` | Recent passkey/step-up and CSRF |
| POST `/identity/v1/email/revoke` | `{}` | Recent passkey/step-up and CSRF |

Delivery always targets the stored contact address, never a request-supplied recipient. A random
32-byte code is delivered in the email content and submitted in a JSON body; no URL token, login
link or code is returned in the challenge-request response. Storage retains its SHA-256 digest,
15-minute expiry, user epoch and at most five attempts. The challenge is activated only after
provider acknowledgement; delivery failure leaves it unusable. Replacing the challenge invalidates
previous codes. Verification atomically consumes the challenge, updates contact proof and records
redacted audit. Tampering decrements attempts; replay, expiry and concurrent losers fail safely.

Email address uniqueness uses the existing case-insensitive contact-address claims. The explicit
change route stores a lowercase address and clears proof/challenges. External user updates also
invalidate proof conservatively when their `updatedAt` exceeds proof time; challenges cannot
survive an intervening user update or epoch change. A contact address, invitation or federation
claim is never silently promoted to verified-email proof. Verification does not change session
assurance or grant device, content-key, recovery or security-admin authority.

The delivery provider must acknowledge actual provider acceptance, keep codes/addresses out of
URLs, logs and telemetry, bound attempts/timeouts, and handle retries without fabricating success.
Tests record delivery and exercise actual PostgreSQL and Firestore emulator transactions. Live
email delivery and production provider qualification are not established by those tests.

## Storage and compatibility

`IdentityStore.findAccountSecurity` and `mutateAccountSecurity` are additive capabilities. The
in-memory testkit, PostgreSQL and Firestore adapters implement them. Custom adapters default to
unavailable until implemented. PostgreSQL adds reviewed, checksummed V014; earlier SQL bytes
remain immutable. Firestore adds the `accountSecurity` collection under the isolated namespace.
CAS operations check user/security versions and the active actor session inside the atomic store
operation. Account-security documents/verifiers and adapter RPCs are privileged authority data;
never expose them directly through public database or Firestore clients. Public APIs return only
safe email status, session projections and canonical errors. Limiter keys are keyed pseudonyms.

The supplied password provider is JVM-only. Other targets can inject a maintained implementation
of the explicit provider contract; absence fails closed. Existing passkey/federation flows and
production WASI limitations are unchanged. Coordinate source/artifact versions and route mappings
before a consumer opts into these capabilities.
