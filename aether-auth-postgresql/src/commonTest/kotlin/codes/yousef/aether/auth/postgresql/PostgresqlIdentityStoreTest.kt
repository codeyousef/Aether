package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.Credential
import codes.yousef.aether.auth.AcquireFederationProviderLeaseCommand
import codes.yousef.aether.auth.AuditEventId
import codes.yousef.aether.auth.FederationProviderControl
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.OrganizationAuditEventPage
import codes.yousef.aether.auth.OrganizationAuditEventPageRequest
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.PurgeAuditEventsCommand
import codes.yousef.aether.auth.PurgeAuditEventsCommit
import codes.yousef.aether.auth.IdentityEnvironment
import codes.yousef.aether.auth.IdentitySession
import codes.yousef.aether.auth.IdentityStoreError
import codes.yousef.aether.auth.IdentityStoreErrorCode
import codes.yousef.aether.auth.StoreResult
import codes.yousef.aether.auth.TouchIdentitySessionCommand
import codes.yousef.aether.auth.UserId
import codes.yousef.aether.auth.testkit.IdentityFixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PostgresqlIdentityStoreTest {
    @Test
    fun failsClosedUntilEnvironmentIsVerifiedAndOnlyInitializesOnce() = runTest {
        val transport = RecordingTransport { request -> successFor(request) }
        val store = PostgresqlIdentityStore(config(), transport)

        val beforeInitialization = assertIs<StoreResult.Failure>(
            store.findUser(IdentityFixtures.userId("user-1"))
        )
        assertEquals(IdentityStoreErrorCode.UNAVAILABLE, beforeInitialization.error.code)
        assertEquals(0, transport.requests.size)

        assertIs<StoreResult.Success<Unit>>(store.initialize())
        assertIs<StoreResult.Success<Unit>>(store.initialize())
        assertEquals(
            listOf(PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName),
            transport.requests.map { it.operation }
        )

        val missing = assertIs<StoreResult.Success<codes.yousef.aether.auth.User?>>(
            store.findUser(IdentityFixtures.userId("missing"))
        )
        assertNull(missing.value)
    }

    @Test
    fun rejectsMismatchedDatabaseMarker() = runTest {
        val transport = RecordingTransport { request ->
            PostgresqlRpcResponseEnvelope(
                operation = request.operation,
                outcome = PostgresqlRpcOutcome.SUCCESS,
                result = buildJsonObject {
                    put("verified", true)
                    put("environment", "development")
                    put("namespace", "aether_development")
                }
            )
        }
        val result = PostgresqlIdentityStore(config(), transport).initialize()

        val failure = assertIs<StoreResult.Failure>(result)
        assertEquals(IdentityStoreErrorCode.INTERNAL, failure.error.code)
    }

    @Test
    fun missingDatabaseMarkerFailureNeverInitializesOrMutatesThroughAnotherOperation() = runTest {
        val transport = RecordingTransport { request ->
            PostgresqlRpcResponseEnvelope(
                operation = request.operation,
                outcome = PostgresqlRpcOutcome.FAILURE,
                error = IdentityStoreError(IdentityStoreErrorCode.INTERNAL)
            )
        }
        val store = PostgresqlIdentityStore(config(), transport)

        assertEquals(
            IdentityStoreErrorCode.INTERNAL,
            assertIs<StoreResult.Failure>(store.initialize()).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.UNAVAILABLE,
            assertIs<StoreResult.Failure>(store.findUser(IdentityFixtures.userId("missing-marker"))).error.code
        )
        assertEquals(
            listOf(PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName),
            transport.requests.map { it.operation }
        )
    }

    @Test
    fun routesWebAuthnCredentialLookupToItsDedicatedOperation() = runTest {
        val fixture = IdentityFixtures.credential()
        val json = defaultPostgresqlJson()
        val transport = RecordingTransport { request ->
            if (request.operation == PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName) {
                successFor(request)
            } else {
                PostgresqlRpcResponseEnvelope(
                    operation = request.operation,
                    outcome = PostgresqlRpcOutcome.SUCCESS,
                    result = json.encodeToJsonElement(Credential.serializer(), fixture)
                )
            }
        }
        val store = PostgresqlIdentityStore(config(), transport, json)
        assertIs<StoreResult.Success<Unit>>(store.initialize())

        val found = assertIs<StoreResult.Success<Credential?>>(store.findCredentialByWebAuthnId(fixture.webAuthnId))
        assertEquals(fixture, found.value)
        val lookup = transport.requests.last()
        assertEquals(PostgresqlRpcOperation.FIND_CREDENTIAL_BY_WEB_AUTHN_ID.wireName, lookup.operation)
        assertEquals(fixture.webAuthnId.encoded, lookup.payload.jsonObject.getValue("webAuthnId").jsonPrimitive.content)
    }

    @Test
    fun propagatesOnlySafeFailureEnvelope() = runTest {
        val transport = RecordingTransport { request ->
            if (request.operation == PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName) {
                successFor(request)
            } else {
                PostgresqlRpcResponseEnvelope(
                    operation = request.operation,
                    outcome = PostgresqlRpcOutcome.FAILURE,
                    error = IdentityStoreError(IdentityStoreErrorCode.LAST_OWNER)
                )
            }
        }
        val store = PostgresqlIdentityStore(config(), transport)
        assertIs<StoreResult.Success<Unit>>(store.initialize())

        val result = assertIs<StoreResult.Failure>(store.findUser(IdentityFixtures.userId("user-1")))
        assertEquals(IdentityStoreErrorCode.LAST_OWNER, result.error.code)
    }

    @Test
    fun routesBoundedOrganizationAuditPageThroughTheFixedRpc() = runTest {
        val organizationId = IdentityFixtures.organizationId("organization-audit")
        val event = IdentityFixtures.auditEvent(IdentityFixtures.auditEventId("audit-page")).copy(
            organizationId = organizationId
        )
        val page = OrganizationAuditEventPage(organizationId, listOf(event))
        val json = defaultPostgresqlJson()
        val transport = RecordingTransport { request ->
            if (request.operation == PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName) {
                successFor(request)
            } else {
                PostgresqlRpcResponseEnvelope(
                    operation = request.operation,
                    outcome = PostgresqlRpcOutcome.SUCCESS,
                    result = json.encodeToJsonElement(OrganizationAuditEventPage.serializer(), page)
                )
            }
        }
        val store = PostgresqlIdentityStore(config(), transport, json)
        assertIs<StoreResult.Success<Unit>>(store.initialize())

        assertEquals(
            page,
            assertIs<StoreResult.Success<OrganizationAuditEventPage>>(
                store.listAuditEventsForOrganization(
                    OrganizationAuditEventPageRequest(organizationId, limit = 25)
                )
            ).value
        )
        val request = transport.requests.last()
        assertEquals(PostgresqlRpcOperation.LIST_AUDIT_EVENTS_FOR_ORGANIZATION.wireName, request.operation)
        assertEquals(organizationId.value, request.payload.jsonObject.getValue("organizationId").jsonPrimitive.content)
        assertEquals("25", request.payload.jsonObject.getValue("limit").jsonPrimitive.content)
    }

    @Test
    fun routesBoundedAuditRetentionThroughTheFixedRpc() = runTest {
        val expected = PurgeAuditEventsCommit(deletedCount = 250, hasMore = true)
        val json = defaultPostgresqlJson()
        val transport = RecordingTransport { request ->
            if (request.operation == PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName) {
                successFor(request)
            } else {
                PostgresqlRpcResponseEnvelope(
                    operation = request.operation,
                    outcome = PostgresqlRpcOutcome.SUCCESS,
                    result = json.encodeToJsonElement(PurgeAuditEventsCommit.serializer(), expected)
                )
            }
        }
        val store = PostgresqlIdentityStore(config(), transport, json)
        assertIs<StoreResult.Success<Unit>>(store.initialize())
        val command = PurgeAuditEventsCommand(
            occurredBefore = IdentityFixtures.instant(50_000),
            maximumEvents = 250
        )

        assertEquals(expected, assertIs<StoreResult.Success<PurgeAuditEventsCommit>>(
            store.purgeAuditEvents(command)
        ).value)
        val request = transport.requests.last()
        assertEquals(PostgresqlRpcOperation.PURGE_AUDIT_EVENTS.wireName, request.operation)
        assertEquals("250", request.payload.jsonObject.getValue("maximumEvents").jsonPrimitive.content)
        assertEquals(
            command.occurredBefore.toString(),
            request.payload.jsonObject.getValue("occurredBefore").jsonPrimitive.content
        )
    }

    @Test
    fun routesAuditFreeIdentitySessionTouchThroughTheFixedRpc() = runTest {
        val original = IdentityFixtures.session(id = IdentityFixtures.sessionId("session-touch-rpc"))
        val command = TouchIdentitySessionCommand(
            sessionId = original.id,
            expectedVersion = original.version,
            lastUsedAt = IdentityFixtures.instant(60_000),
            idleExpiresAt = IdentityFixtures.instant(3_660_000)
        )
        val expected = original.copy(
            version = 1,
            lastUsedAt = command.lastUsedAt,
            idleExpiresAt = command.idleExpiresAt
        )
        val json = defaultPostgresqlJson()
        val transport = RecordingTransport { request ->
            if (request.operation == PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName) {
                successFor(request)
            } else {
                PostgresqlRpcResponseEnvelope(
                    operation = request.operation,
                    outcome = PostgresqlRpcOutcome.SUCCESS,
                    result = json.encodeToJsonElement(IdentitySession.serializer(), expected)
                )
            }
        }
        val store = PostgresqlIdentityStore(config(), transport, json)
        assertIs<StoreResult.Success<Unit>>(store.initialize())

        assertEquals(
            expected,
            assertIs<StoreResult.Success<IdentitySession>>(store.touchIdentitySession(command)).value
        )
        val request = transport.requests.last()
        assertEquals(PostgresqlRpcOperation.TOUCH_IDENTITY_SESSION.wireName, request.operation)
        assertEquals(
            setOf("sessionId", "expectedVersion", "lastUsedAt", "idleExpiresAt"),
            request.payload.jsonObject.keys
        )
        assertEquals(original.id.value, request.payload.jsonObject.getValue("sessionId").jsonPrimitive.content)
        assertEquals("0", request.payload.jsonObject.getValue("expectedVersion").jsonPrimitive.content)
    }

    @Test
    fun routesFederationProviderLookupsAndLeasesThroughFixedRpcs() = runTest {
        val control = IdentityFixtures.federationProviderControl()
        val lease = IdentityFixtures.federationProviderLease(control)
        val json = defaultPostgresqlJson()
        val transport = RecordingTransport { request ->
            when (request.operation) {
                PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName -> successFor(request)
                PostgresqlRpcOperation.FIND_FEDERATION_PROVIDER_CONTROL.wireName,
                PostgresqlRpcOperation.FIND_FEDERATION_PROVIDER_CONTROL_BY_STORAGE_KEY.wireName ->
                    PostgresqlRpcResponseEnvelope(
                        operation = request.operation,
                        outcome = PostgresqlRpcOutcome.SUCCESS,
                        result = json.encodeToJsonElement(FederationProviderControl.serializer(), control)
                    )
                else -> PostgresqlRpcResponseEnvelope(
                    operation = request.operation,
                    outcome = PostgresqlRpcOutcome.SUCCESS,
                    result = json.encodeToJsonElement(FederationProviderLease.serializer(), lease)
                )
            }
        }
        val store = PostgresqlIdentityStore(config(), transport, json)
        assertIs<StoreResult.Success<Unit>>(store.initialize())

        assertEquals(
            control,
            assertIs<StoreResult.Success<FederationProviderControl?>>(
                store.findFederationProviderControl(control.organizationId, control.providerId)
            ).value
        )
        assertEquals(
            setOf("organizationId", "providerId"),
            transport.requests.last().payload.jsonObject.keys
        )
        assertEquals(
            control,
            assertIs<StoreResult.Success<FederationProviderControl?>>(
                store.findFederationProviderControlByStorageKey(control.storageKey)
            ).value
        )
        assertEquals(
            setOf("storageKey"),
            transport.requests.last().payload.jsonObject.keys
        )

        val acquired = assertIs<StoreResult.Success<FederationProviderLease>>(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organizationId = control.organizationId,
                    kind = control.kind,
                    providerId = control.providerId,
                    storageKey = control.storageKey,
                    acquiredAt = IdentityFixtures.instant()
                )
            )
        )
        assertEquals(lease, acquired.value)
        assertEquals(
            PostgresqlRpcOperation.ACQUIRE_FEDERATION_PROVIDER_LEASE.wireName,
            transport.requests.last().operation
        )

        assertEquals(
            lease,
            assertIs<StoreResult.Success<FederationProviderLease>>(
                store.validateFederationProviderLease(lease)
            ).value
        )
        assertEquals(
            setOf("organizationId", "kind", "providerId", "storageKey", "sessionEpoch", "version"),
            transport.requests.last().payload.jsonObject.keys
        )
    }

    private fun config(): PostgresqlIdentityConfig = PostgresqlIdentityConfig(
        environment = IdentityEnvironment.TEST,
        namespace = "aether_test"
    )

    private fun successFor(request: PostgresqlRpcRequestEnvelope): PostgresqlRpcResponseEnvelope =
        PostgresqlRpcResponseEnvelope(
            operation = request.operation,
            outcome = PostgresqlRpcOutcome.SUCCESS,
            result = if (request.operation == PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName) {
                buildJsonObject {
                    put("verified", true)
                    put("environment", "test")
                    put("namespace", "aether_test")
                }
            } else {
                JsonNull
            }
        )
}

private class RecordingTransport(
    private val responder: (PostgresqlRpcRequestEnvelope) -> PostgresqlRpcResponseEnvelope
) : PostgresqlRpcTransport {
    val requests = mutableListOf<PostgresqlRpcRequestEnvelope>()

    override suspend fun execute(request: PostgresqlRpcRequestEnvelope): PostgresqlRpcResponseEnvelope {
        requests += request
        return responder(request)
    }
}
