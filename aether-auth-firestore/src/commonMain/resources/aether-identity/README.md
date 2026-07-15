# Firestore identity storage

The adapter stores records below the namespace document
`aetherIdentity/{namespace}` in the configured Firestore database. Entity documents contain a
versioned JSON payload plus a small set of typed shadow fields used by reviewed queries. The
payload field must remain unindexed in every identity collection, including internal bootstrap,
uniqueness, replay, token, and SCIM receipt collections; deploy `firestore.indexes.json` with the
application. The JVM resource contract test compares the shipped exemptions with every collection
declared by the adapter so a newly added payload collection cannot silently retain default indexes.

Values which must be globally unique are represented by deterministic, SHA-256-derived documents
under `unique/`. Email addresses, WebAuthn credential IDs, membership pairs, session digests,
recovery selectors, device/user-code digests, service-credential prefixes, federation subjects,
federation-provider route selectors, federation-provider storage keys, and replay assertion
digests are claimed in the same transaction as their owning entity. Raw email addresses, external
subjects, and secret digests never appear in unique document IDs.

## Federation provider controls

Each tenant provider has one `federationProviderControls/{storageKey}` document. Two deterministic
documents under `unique/` independently reserve its `(organizationId, providerId)` route and its
globally stable storage key. First acquisition, an initial disable, later compare-and-set state
changes, and the matching audit event use one Firestore transaction with create/update-time
preconditions, so a route cannot be remapped to a different protocol configuration during a race.

Challenges for external linking persist the exact enabled-provider lease. Challenge creation and
consumption, replay receipts, external links, and federated session create/touch/rotation all read
and validate the provider control in their mutation transaction. Disabling advances the provider's
logical `sessionEpoch`; it never scans or rewrites session documents. Re-enabling retains that
epoch, so pre-disable callbacks and sessions remain invalid even though stable external links are
retained. JIT linking creates its email-less user, active viewer membership, external link, replay
receipt, and audit record in one commit; a provider disable, subject conflict, or replay conflict
therefore leaves no orphan user or membership.

The bundled composite-index resource includes the reviewed tenant/state/provider ordering used by
operator tooling. Authority-path lease validation uses direct control and deterministic uniqueness
document reads and does not depend on a query index. Keep provider payloads unindexed and deploy the
resource before enabling federation in an environment.

Every command uses Firestore REST `beginTransaction`, transactional reads/queries, and `commit`
with `exists` or `updateTime` preconditions. Audit records are writes in that same commit. ABORTED,
UNAVAILABLE, and DEADLINE_EXCEEDED transactions are retried from the initial read up to the
configured bound; callers receive only stable `IdentityStoreError` values.

## Environment marker

The database-global singleton `aetherIdentityEnvironment/current` contains the exact environment,
namespace, and marker schema version. It is outside `aetherIdentity/{namespace}`, so a second
environment or namespace cannot claim the same project/database. Normal `initialize()` only reads
and verifies it and fails closed if it is missing or mismatched. Deployment automation must invoke
the explicit, exact-match-idempotent `provisionEnvironmentMarker()` operation. A legacy
namespace-local `aetherIdentity/{namespace}/environment/current` document is ignored. Never point
development and production at the same project/database; a namespace is not an isolation boundary.
The singleton is not queried and needs no composite index; the bundled indexes apply to entity
collections only.

## Authentication

Production uses an injected `FirestoreAccessTokenProvider`. The supplied refreshing provider
caches short-lived OAuth tokens from a workload identity, metadata server, or service-account
credential source and refreshes before expiry. If Firestore returns HTTP 401 or the bounded Google
error status `UNAUTHENTICATED`, the transport invalidates the cached token and retries the request
exactly once. A second rejection returns only a stable generic store error; provider bodies and
bearer values are never exposed. The no-auth provider is accepted only when the API base URL is an
exact loopback Firestore emulator URL. Direct browser access is denied by the shipped rules; a
trusted Aether identity authority performs all storage calls.

## Real-emulator release gate

Run `./aether-auth-firestore/run-emulator-gate.sh` from the repository root with `gcloud` and its
`cloud-firestore-emulator` component installed. The gate starts a loopback emulator, uses a
dedicated test-only project and unique `TEST` namespace, provisions/verifies the environment marker,
and executes the adapter's atomicity and replay suite through the actual Firestore v1 REST API.
Absence or startup failure of the emulator fails the gate; the ordinary cross-target unit suite is
not a substitute for this release check. The shared suite includes provider first-acquire/disable
and compare-and-set races, disable/re-enable stale leases and sessions, external-link replay, and
JIT conflict/replay/disabled-provider orphan rollback.
