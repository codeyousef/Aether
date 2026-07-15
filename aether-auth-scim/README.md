# Aether SCIM 2.0 adapter

`aether-auth-scim` is the optional, storage-neutral SCIM 2.0 engine for Aether identity. It runs from common Kotlin on JVM, `wasmJs`, and `wasmWasi`; it does not add an enterprise dependency to `aether-auth`.

The fixed routes are:

- `/scim/v2/Users` and `/scim/v2/Users/{id}`
- `/scim/v2/Groups` and `/scim/v2/Groups/{id}`

Both resource types implement POST, GET, PUT, PATCH, and DELETE. The core User projection covers names, profile metadata, language/locale/timezone, emails, phone numbers, IM handles, photos, addresses, entitlements, informational roles, and X.509 certificate values. Password provisioning is rejected: it never creates password authentication or stores a password value. Collection GET supports one-based `startIndex`, bounded `count`, and a single equality (`eq`) filter. User filters support the identifiers and common single-/multi-value attributes; Group filters support `id`, `externalId`, `displayName`, and `members.value`. Unsupported compound filters fail with the standard `invalidFilter` response.

## Host integration

Construct `ScimEngine` with the shared `IdentityStore`, an implementation of `ScimDirectory`, `IdentityRuntime`, and tenant configuration. Install `ScimHttpMiddleware(engine, authenticator, authorizer, config).asMiddleware()` to expose it through Aether's framework-neutral `Exchange`/`Middleware` API on JVM, `wasmJs`, or `wasmWasi`.

`ScimAuthenticator` and `ScimTenantAuthorizer` are mandatory and have no allow-by-default implementation. Authentication receives immutable method/path/header/connection metadata before body I/O; authorization must explicitly allow the exact `organizationId` configured on both the middleware and engine. Rejection fails closed with a generic SCIM 401/403 response, while authenticator, authorizer, handler, and infrastructure failures return a generic 503 without exception text.

Mutating requests require one valid `Idempotency-Key` by default (the header name is configurable), `application/scim+json`, and a body within the configured limit. The adapter checks a single `Content-Length` before reading, checks the observed size afterward, rejects duplicate security/conditional/idempotency headers, strictly percent-decodes one value per query parameter, and forwards only `If-Match`, `If-None-Match`, and bounded User-Agent metadata to the engine. Server adapters must enforce the same body limit while streaming so an untrusted peer cannot force an oversized allocation before `Request.bodyBytes()` returns. Engine response status, headers, and bytes are copied unchanged to the Aether response.

Setting `ScimConfig.enabled=false` immediately makes every endpoint unavailable. Every request also verifies that its configured organization is active, and durable reservations are re-validated against the tenant-scoped provider key before retry, preventing operation-ID collisions from crossing tenants.

Every POST, PUT, PATCH, and DELETE needs a stable `ScimOperationId`. HTTP integrations should derive it from an allowlisted, bounded idempotency header or from a durable provider delivery identifier. Reusing an operation ID with a different method, path, version precondition, or byte-for-byte body returns a conflict. The module writes the exact `ApplyScimBatchCommand` into a durable reservation, then applies it once through `IdentityStore.applyScimBatch`; a crash and retry therefore submits the identical Group aggregate, ordered mutations, revocations, receipts, and audit events.

`ScimDirectory.reserveOperation` is a high-level atomic command, not a database transaction callback. An implementation must validate the expected projection version, acquire a durable per-resource/uniqueness reservation, and enforce tenant uniqueness before the identity mutation runs. `completeOperation` then updates the projection and marks the operation complete atomically. Active User `userName` and `externalId`, and active Group `displayName` and `externalId`, are tenant-unique. Tombstones are retained for retry receipts but excluded from reads and uniqueness checks.

The engine requires `If-Match` for PUT, PATCH, and DELETE by default, emits weak ETags in the HTTP header and `meta.version`, supports `If-None-Match` on resource GET, returns `201` plus `Location` for create, and returns `204` for delete. All responses use `application/scim+json`.

## Security and tenant semantics

Input is rejected before typed decoding when it exceeds the byte, nesting, node, or string limits. The common parser rejects malformed UTF-8, duplicate object keys (including escaped aliases), invalid Unicode surrogate pairs, non-finite numbers, and trailing data. Typed decoding rejects unknown writable attributes; the defined read-only `id`, `meta`, and User `groups` fields are accepted and ignored as required by RFC 7644.

The provider key stored in core mutations is structurally scoped as `{providerName}:{organizationId}`. New SCIM users receive a new stable identity and a Viewer membership; email is never used to merge identities. Explicit Group mappings can raise the tenant role. Unknown groups persist as SCIM groups but grant no role, User `roles` and `entitlements` are informational only, and removing the final mapped group returns the membership to Viewer.

Setting `active=false` or deleting a User removes only that organization's membership. It does not deactivate or delete the stable `User`. In the same identity-store transaction, deprovisioning and role changes revoke active federated sessions carrying that organization and active device-token families bound to that user and organization, including their active access and refresh tokens. They do not advance the stable User's global session epoch, revoke passkey/global sessions, or affect another tenant's membership, sessions, or token families.

Errors contain only fixed generic text and standard SCIM fields. Internal exceptions, provider subjects, emails, tokens, and storage diagnostics are never serialized.

## Atomicity boundary

`IdentityStore.applyScimBatch` atomically commits all affected Users and Memberships, the canonical `ScimGroup` aggregate, final-state last-owner validation, tenant-local credential revocation, child audits, the canonical `scim.group_changed` audit, and an outer idempotency receipt. An identical retry returns the stored receipt; reusing the operation ID with any changed command field is an idempotency conflict. This closes the former per-member partial-application and sink-delivery gap.

The module-owned RFC projection may be deployed separately from the identity store and therefore cannot share its physical transaction. `ScimDirectory.reserveOperation` remains the durable recovery boundary: it freezes the exact identity batch before the identity commit, and `completeOperation` applies that already-reserved projection after the batch succeeds. Retrying either side is deterministic and idempotent.

## Verification

```text
./gradlew :aether-auth-scim:allTests
```

The common suite runs the bounded parser, equality filters, pagination, Users and Groups CRUD/PATCH, ETag failures, idempotent retry, unknown-group behavior, atomic multi-member role changes, tenant-only deprovisioning, credential isolation, Group audit/aggregate persistence, and tombstones on all configured targets.
