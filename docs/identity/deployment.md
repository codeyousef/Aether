# Identity deployment and operations

An identity authority must fail closed before accepting traffic. Run runtime capability self-tests,
storage environment checks, migrations or index checks, and secret resolution during a readiness
phase. Do not let a health endpoint report ready while any gate is missing.

## Production configuration

Production configuration is explicit. It includes environment, canonical public origin, RP ID,
the exact allowed-origin set, trusted proxies, cookie policy, lifetimes, registration policy,
storage namespace and versioned secret references. Configuration stores references, not secret
values.

```kotlin
val environment = IdentityEnvironment.PRODUCTION
fun secret(name: String, version: String) = SecretReference(
    provider = "workload-secret-manager",
    name = name,
    version = version,
    environment = environment
)

val identityConfig = IdentityConfig(
    environment = environment,
    publicBaseUrl = "https://identity.example.com",
    relyingParty = RelyingPartyConfig(
        id = "identity.example.com",
        name = "Example Identity",
        allowedOrigins = setOf("https://identity.example.com")
    ),
    storageNamespace = "aether_production",
    registrationPolicy = RegistrationPolicy.INVITATION_ONLY,
    trustedProxy = TrustedProxyConfig(TrustedProxyMode.DIRECT_ONLY),
    audit = IdentityAuditConfig(
        retention = IdentityDuration.days(90),
        userAgentPolicy = AuditUserAgentPolicy.OMIT
    ),
    keys = IdentityKeyConfig(
        sessionPepper = secret("identity-session-pepper", "v3"),
        previousSessionPeppers = listOf(secret("identity-session-pepper", "v2")),
        recoveryPepper = secret("identity-recovery-pepper", "v2"),
        deviceTokenPepper = secret("identity-device-pepper", "v2"),
        serviceCredentialPepper = secret("identity-service-pepper", "v2"),
        auditPseudonymizationKey = secret("identity-audit-key", "2026-q3"),
        encryptionKey = secret("identity-encryption-key", "v2"),
        signingKey = secret("identity-signing-key", "v2")
    ),
    bootstrapLifecycle = IdentityBootstrapLifecycle.PENDING,
    bootstrapSecret = secret("identity-first-owner-bootstrap", "deploy-1")
)
```

Every key reference must be distinct and belong to the configured environment. Production has no
inferred origin, RP ID, key or open-registration default. A new production deployment remains
`PENDING` and fails configuration validation without its single-use bootstrap secret. After the
first owner completes passkey enrollment and the durable bootstrap receipt is verified, deploy
`bootstrapLifecycle = IdentityBootstrapLifecycle.RETIRED` with `bootstrapSecret = null`, then
invalidate the old secret-manager version. Retired startup never resolves that reference and the
bootstrap route remains disabled. Later owners are created through normal owner-protected membership
changes. Never mark an empty or restored-before-bootstrap identity store as retired.

Bootstrap atomically creates the first user, organization, owner membership, audit event, and a
15-minute `bootstrap` recovery-assurance session. Return that session only as the normal HttpOnly
cookie and immediately complete passkey registration. Until enrollment succeeds, the session can
only start/finish passkey registration or log out. Successful enrollment consumes the ceremony,
revokes the bootstrap session, advances the user session epoch, and displays the first ten recovery
codes once. A bootstrap response without this constrained enrollment session is not a usable or
supported deployment flow.

Install `IdentityMiddleware` before every application and optional identity adapter route. Its
default `RestrictedEnrollmentRoutePolicy` denies every downstream route except the two passkey
registration calls and logout, and it never installs a recovery-assurance identity as the generic
`aether-core` principal. A browser shell may explicitly add only public GET assets to that policy;
do not add authenticated application routes.

Normal invite-only onboarding uses `POST /identity/v1/invitations/enroll` with the opaque
invitation token and a bounded display name. The storage command atomically verifies that the
invitation is pending, unexpired, at the expected version, and matches the keyed token digest;
creates one active user and the invited organization membership; consumes the invitation; appends
the acceptance audit event; and creates a 15-minute `invitation` recovery-assurance session. Email
uniqueness, invitation reuse, and concurrent finishes fail the whole command without leaving a user,
membership, session, or audit row behind. The response contains only safe user, membership, session,
expiry, assurance, and CSRF fields; the raw session token is emitted only in the HttpOnly cookie.

Registration policy is evaluated from server-trusted provenance, never from a client-supplied
field. `INVITATION_ONLY` accepts invitations with or without an issuing-user marker;
`ADMIN_ONLY` accepts only invitations carrying the authenticated issuing user recorded when the
invite was created; and `DISABLED` rejects creation of a new invitation-derived account while
still allowing an existing user to accept an organization invitation. Pending bootstrap, recovery,
and existing-account passkey management remain explicit authorized paths under every policy. `OPEN`
reserves a public-onboarding source for development, but 0.6 does not expose an anonymous public
registration route, so setting it does not make the current passkey registration endpoints
anonymous.

An invitation session has the same narrow route policy as bootstrap and recovery enrollment: it may
only start or finish passkey registration, or log out. Successful passkey registration revokes that
restricted session and all other user sessions, advances the session epoch, and returns ten new
single-use recovery codes exactly once. The existing authenticated, organization-scoped invitation
acceptance route remains available for adding an already authenticated identity; it does not create
a second user or an enrollment session.

Call `IdentityService.start()` before mounting public routes. It runs cryptographic readiness, a
provider-local HTTP capability self-test that performs no application request, and the selected
adapter's environment-marker initializer. Injected HTTP clients fail closed by default and must
explicitly implement the readiness contract. A missing capability, failed provider self-test,
unresolved key or mismatched marker is a deployment failure, not a degraded mode.

Reject oversized bodies while streaming, before materializing a request for protocol parsing. On
the JVM, set `VertxServerConfig.maxRequestBodySize` no higher than the largest installed authority
endpoint; the bundled example uses 1 MiB, matching `IdentityHttpApiConfig.maximumJsonBodyBytes`.
Keep the smaller OAuth form and SCIM limits enabled inside their dispatchers as defense in depth.

## Runtime profiles

### JVM

Use `jvmIdentityRuntime` with a production `IdentitySecretResolver`. JCA and `SecureRandom` provide
cryptography and entropy; the default HTTP client does not follow redirects. Vert.x PostgreSQL may
call reviewed functions directly. Pin the JDK and crypto provider in the deployment image and rerun
known-answer tests after an image or provider update.

### wasmJs

WebCrypto, `crypto.getRandomValues` and Fetch provide the runtime capabilities. Authority execution
belongs in a trusted server or worker host. Browser wasmJs is limited to public DTOs, the Summon UI
and `navigator.credentials`; never bundle secret references or server credentials into a browser
distribution.

### wasmWasi

A future production profile requires a component host that provides:

- a real WASI realtime clock and cryptographically secure random source;
- outbound WASI HTTP suitable for the selected storage and federation endpoints; and
- Aether `0.6.0.0`'s SemVer WIT package `aether:identity-crypto@0.6.0`, backed by the supplied
  OpenSSL 3 host library.

The WIT contract is
`aether-auth/src/wasmWasiMain/resources/aether-identity-crypto.wit`; the reference native host is
`aether-identity-wasi-host`. The embedding runtime must map every `WasmWasiIdentityHost` operation,
run the host self-test and preserve the documented input ceilings. No branded Wasmtime, Wasmer or
other runtime is certified merely because it can execute a WASI module: certification is for the
complete host profile and its CI test. A Node-only fake provider, Kotlin `Random`, fake clock or
pure-Kotlin asymmetric fallback is unsupported in production.

The current Kotlin 2.3.x build emits a Preview1 core module and does not yet ship the component
binding that connects the guest runtime to this WIT world. The native OpenSSL library and the
guest-side capability tests are independently verified, but that is not equivalent to executing
the combined production host. Production wasmWasi identity-authority hosting is therefore not
supported in `0.6.0.0`; a future supported release requires the component-model binding and a
combined `wasi:http`/crypto integration test. JVM remains the deployable authority target; browser
wasmJs remains public client/UI code only.

Map WIT signing through an `aether_identity_signing_key_store`. Register only provider- or
HSM-owned `EVP_PKEY` references under versioned opaque handles; never expose PKCS#8 bytes to the
guest. Retire a signing version by removing its handle after all corresponding verification
material has passed its retention window. The native gate exercises both ES256 and RSA-SHA256
handle lookup, signing, verification, and immediate removal.

Build the native primitive gate with:

```text
cmake -S aether-identity-wasi-host -B build/identity-wasi-host -DCMAKE_BUILD_TYPE=Release
cmake --build build/identity-wasi-host --config Release --parallel 2
ctest --test-dir build/identity-wasi-host --output-on-failure
```

## PostgreSQL migrations

Use PostgreSQL 16 and a dedicated production database. The adapter owns the fixed
`aether_identity` schema; application ORM migrations and `aether-db` do not manage it. Apply the
reviewed `V001__identity_foundation.sql` and subsequent V002 through V011 migrations through
`PostgresqlMigrationRunner`. V003 adds organization-scoped audit reads, V004 adds bounded audit
retention, and V005 serializes same-ID device-grant CAS contenders so the loser receives a retryable
version conflict while different-ID device/user-code collisions remain uniqueness failures. V006
adds the atomic, compare-and-set identity-session idle-expiry touch used by authenticated requests.
V007 makes administrative-recovery delivery activation atomic with its audit event. V008 binds
device grants and token families to an exact active membership snapshot and rechecks the active user,
organization, membership, and audit scope inside grant exchange and refresh rotation. V009 makes
all runtime environment assertions read-only and fail-closed when the singleton is absent, and adds
an exact-match-only deployment provisioner. V010 wraps registration, authentication, counter
quarantine, and recovery-enrollment completion in a nested transaction: a deterministic store or
constraint rejection rolls back credential, session, user, and recovery-code effects, then commits
only a failed challenge and a redacted `webauthn_store_rejected` audit. The reviewed implementation
functions live in `aether_identity_internal`; PUBLIC has neither schema usage nor function execution.
Only the four fixed `aether_identity` wrappers are `SECURITY DEFINER`, each with
`search_path=pg_catalog` and fully qualified calls. Never add the internal schema to PostgREST's
exposed schemas or grant an application role direct access to it.
V011 persists the immutable tenant/provider route and storage-key mapping, and uses a logical
provider session epoch so disable is constant-time. Every provider-control compare-and-set
transition advances its version; disable additionally advances the session epoch and enable retains
that epoch. Starts, callbacks, external-link challenges, replay receipts, federated session
creation/touch/rotation, and JIT provisioning all lock and validate the exact enabled control and
lease version in their transaction. Existing session rows are neither scanned nor rewritten. Its
helper functions remain in
`aether_identity_internal` with PUBLIC execution revoked. JIT provisioning inserts a new user
without an email identity key and an exact viewer membership in the same transaction as the replay
receipt, external link, and audit; failures and races roll back every JIT entity.
The runner takes a transaction-scoped advisory lock,
stores each SHA-256 checksum and rejects drift for an already applied version.

Do not edit an applied migration. Add a new, reviewed version, exercise upgrade and rollback/restore
in staging, and record the resulting checksum in release evidence. wasmJs and wasmWasi use the same
stored functions through a protected PostgREST-compatible RPC endpoint, so the database retains the
same atomic semantics. Restrict PostgREST to the identity schema/functions, require TLS and service
authorization, and do not expose it to browsers.

Production and development must use different databases. A namespace is defense in depth, not a
substitute for database separation. After V009, provision the environment marker as a deployment
action, then let normal startup verify the exact environment and namespace. The provisioner creates
an absent singleton, succeeds idempotently for the exact existing pair, and refuses a mismatch; it
never updates the marker. PostgreSQL's default PUBLIC function execution is revoked by V009. The
normal application/PostgREST role must not receive this grant. A short-lived deployment role may be
used as follows, with the grants issued and later revoked by the schema owner:

```sql
GRANT USAGE ON SCHEMA aether_identity TO aether_identity_deployer;
GRANT SELECT, INSERT ON aether_identity.environment TO aether_identity_deployer;
GRANT EXECUTE ON FUNCTION aether_identity.provision_environment(TEXT, TEXT)
    TO aether_identity_deployer;

SET ROLE aether_identity_deployer;
SELECT aether_identity.provision_environment('production', 'aether_production');
RESET ROLE;

REVOKE EXECUTE ON FUNCTION aether_identity.provision_environment(TEXT, TEXT)
    FROM aether_identity_deployer;
REVOKE SELECT, INSERT ON aether_identity.environment FROM aether_identity_deployer;
REVOKE USAGE ON SCHEMA aether_identity FROM aether_identity_deployer;
```

## Firestore indexes and marker

Use a separate production project or database. Deploy
`aether-auth-firestore/src/commonMain/resources/aether-identity/firestore.indexes.json`, including
the field overrides that disable indexing of payload fields, before enabling traffic. Review index
deployment status rather than relying on an eventual query failure. Organization audit reads need
the `auditEvents` composite index over `organizationId ASC`, `occurredAt DESC`, and `entityId DESC`;
the last field is the deterministic tie-breaker used by the opaque keyset cursor.
Retention workers need the shipped `occurredAt ASC`, `entityId ASC` audit index.

Identity records live below `aetherIdentity/{namespace}`. The environment marker is deliberately
outside that tree at the database-global singleton `aetherIdentityEnvironment/current` and uses
marker schema version 2. Deployment automation explicitly calls `provisionEnvironmentMarker()`;
the call is exact-match idempotent and cannot claim another namespace in the same project/database.
Normal `initialize()` only reads and verifies the marker. A legacy namespace-local
`aetherIdentity/{namespace}/environment/current` marker is ignored and must be replaced by explicit
global provisioning during upgrade. No composite index is required for the singleton; deploy the
bundled entity indexes unchanged. Production uses a refreshing workload-identity or service-account
OAuth source. When Firestore rejects an authenticated request with HTTP 401 or provider status
`UNAUTHENTICATED`, the adapter invalidates the cached bearer and retries the request exactly once;
the second failure is returned as a generic store error. The no-auth provider is only for an exact
loopback emulator. Firestore security rules must deny direct browser access.

Release verification must also run `./aether-auth-firestore/run-emulator-gate.sh`. The script starts
the official Firestore emulator on loopback with a dedicated `aether-identity-emulator-*` project;
the test provisions an exact `TEST` environment marker and exercises the REST transaction path and
race invariants. A skipped fake transport test is not equivalent release evidence.

## Audit retention operations

Run `IdentityAuditRetentionService.purgeExpired()` from a scheduled maintenance job and repeat
bounded calls while the returned `hasMore` flag is true. The cutoff is calculated from the
configured positive retention duration and current authority wall clock. Deletion is strict: an
event exactly at the cutoff is retained. Calls are safe to retry after an unknown outcome.

PostgreSQL deployments must apply `V004__audit_retention.sql`; its fixed RPC function locks and
deletes at most 500 oldest eligible records in one transaction. Firestore performs the equivalent
query and preconditioned deletes in one REST transaction and therefore uses the same 500-event
maximum. Never implement retention by fetching audit documents into application logs or by issuing
unbounded collection deletes.

## Key rotation

For session, recovery, device-token and service-credential peppers:

1. Create a new secret version and grant the authority access.
2. Make it the active reference and retain the previous reference in the corresponding key ring.
3. Deploy and verify that new records carry the new key version while old credentials still verify.
4. Wait through the maximum credential lifetime or explicitly revoke remaining old families.
5. Confirm no active record references the old version, then remove it from configuration and the
   secret manager grant.

Never reuse a key between credential classes or environments. A forced session-pepper retirement
requires revoke-all/session-epoch invalidation. A forced device or service pepper retirement
requires revoking the affected token or credential families.

Coordinate signing/encryption key rotation with OIDC/SAML metadata and federation replay windows.
Publish validation keys before issuing with a new signing key; retain old validation material until
all assertions and caches have expired. Rotating the audit pseudonymization key intentionally breaks
cross-period correlation unless a protected mapping is retained; align that decision with the
retention policy.

For every federation provider, deploy an exact tenant/provider registry entry, the immutable
provider redirect endpoint allowlist, and one fixed post-login success URL. Provision a shared,
atomic callback-state store before accepting traffic; it must support consume-once semantics across
all authority replicas and TTL deletion. Keep OIDC and SAML state cookie names distinct. When both
adapter middlewares are mounted, configure `NotOwned` only for providers conclusively owned by the
other adapter, and terminate unresolved federation routes with a generic not-found response.

Disabling a provider is a control-plane compare-and-set transaction. It advances the control
version and logical session epoch and retains session rows, external links, and replay receipts;
resolvers and session mutations reject sessions from the previous epoch even after the provider is
re-enabled. Re-enable advances the control version but retains the invalidating session epoch.
Do not add an unbounded session update or a sessions-by-provider composite query to this path.
Re-enable only after metadata, redirect allowlists, callback-state storage, and audit evidence have
been verified, and issue only leases from the new control version.

## Backup and restore

Backups must capture identity state, audit events, migration/marker state and the exact key-version
inventory as one recovery point. Secret values stay in the secret manager and follow its separate,
access-controlled disaster-recovery process.

For PostgreSQL, use a transactionally consistent database backup that includes the
`aether_identity` schema and `schema_migrations`; rehearse restore into an isolated database, run
checksum verification and the storage conformance suite, then have the database owner delete the
restored marker and invoke the exact provisioner for the isolated environment before any test
access. For Firestore, use a managed export of the selected database, retain the deployed index
configuration, and restore into an isolated project/database. A privileged restore job must delete
the restored global marker and explicitly provision the isolated environment before validation;
normal startup and provisioning never rewrite a mismatch.

Never attach a production restore to development. After a suspected credential disclosure, restore
data only for integrity and then revoke sessions, token families and service credentials created in
the exposure window. A database restore cannot recover raw one-time credentials, which is an
intentional property.

## Development and production separation

- Use different PostgreSQL databases or Firestore projects/databases, secret-manager namespaces,
  bootstrap secrets, origins, RP IDs where appropriate, cookies, audit sinks and federation clients.
- Include the environment name in every storage namespace and require the marker to match.
- Open registration is development-only. Production defaults to invitation-only.
- Do not copy production passkey, session, recovery, provider or audit rows into developer machines.
- Use the Firestore emulator or a disposable PostgreSQL 16 instance for tests and synthetic users.
- Keep enterprise provider secrets as external references. A tenant cannot reference another
  tenant's provider configuration, links, replay receipts or SCIM credentials.

## Release verification

Pushes to `main` publish after the automated workflow verification succeeds. `workflow_dispatch`
is reserved for a guarded failed-deployment retry or exact-ID resume; it is not required for normal
publication and must not be used to repeat an upload with an unknown outcome.
Before any upload, the workflow publishes into an isolated local Maven repository and verifies that
every non-POM component has its exact primary artifact, sources JAR, and Javadoc JAR. A Central
upload is not considered complete at HTTP acceptance: the workflow polls the deployment until it
reaches `PUBLISHED`, and a validation failure prevents release completion. Exceptional recovery of
an already-tagged but unpublished release requires the exact authenticated `FAILED` deployment ID
and explicit authorization to move the GitHub tag to the corrected commit. Upload and polling are
separate steps: the accepted deployment UUID is recorded immediately and an interrupted deployment
is resumed by that UUID plus the exact upload commit encoded in its deterministic deployment name.
Resume executes the current reviewed polling workflow while keeping the deployment and repaired tag
bound to that original upload commit. Never rerun an ambiguous upload. Recovery requires Central's
complete 111-PURL report—75 base coordinates, 35 `type=klib` variants, and the plugin marker's
`type=pom` variant—before it can repair a tag or update the GitHub release.
Every failed-deployment retry atomically creates a permanent claim tag immediately before its
replacement upload, making that deployment ID single-use. If Central no longer exposes a reviewed
failed deployment, HTTP 404 alone is not proof that the deployment failed. The `0.6.0.0` exception
is therefore also bound to the recorded deployment, original tag object and commit, reviewed repair
baseline, explicit operator acknowledgement, and a fresh canonical Maven lookup proving that all 75
coordinates remain unpublished. After an interruption, never submit the old failed-deployment ID
again: resume the exact replacement ID if Central accepted one, or stop for manual review if no
accepted ID can be proven. Do not remove a claim without definitive evidence that no upload began.
Automated verification includes JVM, wasmJs and wasmWasi guest protocol/crypto tests, the native
OpenSSL host-library tests, PostgreSQL 16 and Firestore conformance/race suites, Summon browser
tests, federation adversarial suites, and the complete example build. Production wasmWasi
identity-authority hosting is not supported in `0.6.0.0` because the combined Kotlin guest, WIT
crypto, and `wasi:http` component-host integration is not complete.

Before a production deployment, perform an independent adversarial review and a manual
hardware-passkey smoke test on both Firefox and Safari. The review should disposition all findings
and record a non-secret evidence reference; assertions, tokens, secrets, credential IDs, recovery
material, and user PII must not appear in that evidence. In each browser, register and complete
username-free sign-in, perform passkey step-up, enroll and use a second named passkey, revoke a
distinct active session, prove that the revoked cookie no longer authenticates, and complete
recovery re-enrollment. Verify that recovery revokes the prior sessions and replaces the remaining
recovery-code generation. Record browser, OS, authenticator model/transport, RP/origin, and result
without recording credential IDs, assertions, cookies, or recovery material. Treat these checks as
operational validation guidance rather than automated publication gates.
