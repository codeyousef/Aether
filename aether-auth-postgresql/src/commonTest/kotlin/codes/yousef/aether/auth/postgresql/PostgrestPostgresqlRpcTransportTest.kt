package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.IdentityEnvironment
import codes.yousef.aether.auth.IdentityHttpResponse
import codes.yousef.aether.auth.IdentityStoreErrorCode
import codes.yousef.aether.auth.SecretReference
import codes.yousef.aether.auth.testkit.DeterministicIdentityHttpClient
import codes.yousef.aether.auth.testkit.DeterministicIdentityRuntime
import codes.yousef.aether.auth.testkit.DeterministicIdentitySecretResolver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgrestPostgresqlRpcTransportTest {
    private val json = defaultPostgresqlJson()
    private val authorizationReference = SecretReference(
        provider = "test",
        name = "postgrest-token",
        version = "1",
        environment = IdentityEnvironment.TEST
    )

    @Test
    fun sendsStablePostgrestEnvelopeAndProfileHeaders() = runTest {
        val responseEnvelope = PostgresqlRpcResponseEnvelope(
            operation = PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName,
            outcome = PostgresqlRpcOutcome.SUCCESS,
            result = buildJsonObject {
                put("verified", true)
                put("environment", "test")
                put("namespace", "aether_test")
            }
        )
        val http = DeterministicIdentityHttpClient(
            listOf(IdentityHttpResponse(200, body = json.encodeToString(responseEnvelope).encodeToByteArray()))
        )
        val secrets = DeterministicIdentitySecretResolver(
            mapOf(authorizationReference to "header.token-value".encodeToByteArray())
        )
        val runtime = DeterministicIdentityRuntime(
            deterministicHttp = http,
            deterministicSecrets = secrets
        )
        val transport = PostgrestPostgresqlRpcTransport(config(), runtime.runtime, json)
        val requestEnvelope = request(PostgresqlRpcOperation.ASSERT_ENVIRONMENT)

        assertEquals(responseEnvelope, transport.execute(requestEnvelope))
        val recorded = http.recordedRequests().single()
        assertEquals("http://localhost:3000/rest/v1/rpc/v1_assert_environment", recorded.url)
        assertEquals("aether_identity", recorded.headers["Accept-Profile"])
        assertEquals("aether_identity", recorded.headers["Content-Profile"])
        assertEquals("Bearer header.token-value", recorded.headers["Authorization"])

        val document = json.parseToJsonElement(recorded.bodyBytes().decodeToString()).jsonObject
        val pRequest = document.getValue("p_request").jsonObject
        assertEquals(1, pRequest.getValue("protocolVersion").jsonPrimitive.content.toInt())
        assertEquals("assert_environment", pRequest.getValue("operation").jsonPrimitive.content)
        assertEquals("test", pRequest.getValue("environment").jsonPrimitive.content)
        assertEquals("aether_test", pRequest.getValue("namespace").jsonPrimitive.content)
    }

    @Test
    fun mapsPostgrestFailureWithoutRetainingProviderText() = runTest {
        val providerBody = """{"code":"23505","message":"credential-secret-leaked"}"""
        val http = DeterministicIdentityHttpClient(
            listOf(IdentityHttpResponse(409, body = providerBody.encodeToByteArray()))
        )
        val runtime = DeterministicIdentityRuntime(deterministicHttp = http)
        val transport = PostgrestPostgresqlRpcTransport(config(withAuthorization = false), runtime.runtime, json)

        val failure = assertFailsWith<PostgresqlStoreException> {
            transport.execute(request(PostgresqlRpcOperation.FIND_USER))
        }
        assertEquals(IdentityStoreErrorCode.UNIQUE_CONSTRAINT, failure.safeError.code)
        assertFalse(failure.toString().contains("credential-secret-leaked"))
        assertFalse(failure.message.orEmpty().contains("credential-secret-leaked"))
    }

    @Test
    fun tenantAuditReadUsesItsFixedPostgrestFunction() = runTest {
        val operation = PostgresqlRpcOperation.LIST_AUDIT_EVENTS_FOR_ORGANIZATION
        val responseEnvelope = PostgresqlRpcResponseEnvelope(
            operation = operation.wireName,
            outcome = PostgresqlRpcOutcome.SUCCESS,
            result = buildJsonObject {
                put("organizationId", "organization-audit")
                put("events", kotlinx.serialization.json.buildJsonArray { })
                put("nextCursor", kotlinx.serialization.json.JsonNull)
            }
        )
        val http = DeterministicIdentityHttpClient(
            listOf(IdentityHttpResponse(200, body = json.encodeToString(responseEnvelope).encodeToByteArray()))
        )
        val runtime = DeterministicIdentityRuntime(deterministicHttp = http)
        val transport = PostgrestPostgresqlRpcTransport(config(withAuthorization = false), runtime.runtime, json)

        assertEquals(responseEnvelope, transport.execute(request(operation)))
        val recorded = http.recordedRequests().single()
        assertEquals(
            "http://localhost:3000/rest/v1/rpc/v1_list_audit_events_for_organization",
            recorded.url
        )
        assertEquals(
            operation.wireName,
            json.parseToJsonElement(recorded.bodyBytes().decodeToString()).jsonObject
                .getValue("p_request").jsonObject.getValue("operation").jsonPrimitive.content
        )
    }

    @Test
    fun rejectsHeaderInjectionBeforeCallingHttp() = runTest {
        val http = DeterministicIdentityHttpClient()
        val secrets = DeterministicIdentitySecretResolver(
            mapOf(authorizationReference to "valid\r\nInjected: true".encodeToByteArray())
        )
        val runtime = DeterministicIdentityRuntime(
            deterministicHttp = http,
            deterministicSecrets = secrets
        )
        val transport = PostgrestPostgresqlRpcTransport(config(), runtime.runtime, json)

        val failure = assertFailsWith<PostgresqlStoreException> {
            transport.execute(request(PostgresqlRpcOperation.FIND_USER))
        }
        assertEquals(IdentityStoreErrorCode.INTERNAL, failure.safeError.code)
        assertTrue(http.recordedRequests().isEmpty())
    }

    private fun config(withAuthorization: Boolean = true): PostgresqlIdentityConfig = PostgresqlIdentityConfig(
        environment = IdentityEnvironment.TEST,
        namespace = "aether_test",
        postgrestBaseUrl = "http://localhost:3000/rest/v1/",
        postgrestAuthorizationSecret = authorizationReference.takeIf { withAuthorization }
    )

    private fun request(operation: PostgresqlRpcOperation): PostgresqlRpcRequestEnvelope =
        PostgresqlRpcRequestEnvelope(
            operation = operation.wireName,
            environment = IdentityEnvironment.TEST,
            namespace = "aether_test",
            requestId = "request-1",
            payload = buildJsonObject { put("id", JsonPrimitive("subject-1")) }
        )
}
