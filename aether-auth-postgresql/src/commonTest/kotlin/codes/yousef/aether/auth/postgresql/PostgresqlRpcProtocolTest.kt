package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.IdentityStoreErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresqlRpcProtocolTest {
    @Test
    fun everyOperationUsesAUniqueFixedVersionedFunction() {
        val operations = PostgresqlRpcOperation.entries

        assertEquals(operations.size, operations.map { it.wireName }.toSet().size)
        assertEquals(operations.size, operations.map { it.functionName }.toSet().size)
        assertTrue(operations.all { it.functionName == "v1_${it.wireName}" })
        assertTrue(PostgresqlRpcOperation.FIND_CREDENTIAL_BY_WEB_AUTHN_ID in operations)
        assertTrue(PostgresqlRpcOperation.BOOTSTRAP_IDENTITY in operations)
        assertTrue(PostgresqlRpcOperation.ENROLL_INVITATION in operations)
        assertTrue(PostgresqlRpcOperation.LIST_AUDIT_EVENTS_FOR_ORGANIZATION in operations)
        assertTrue(PostgresqlRpcOperation.PURGE_AUDIT_EVENTS in operations)
        assertTrue(PostgresqlRpcOperation.TOUCH_IDENTITY_SESSION in operations)
        assertTrue(PostgresqlRpcOperation.FIND_FEDERATION_PROVIDER_CONTROL in operations)
        assertTrue(PostgresqlRpcOperation.FIND_FEDERATION_PROVIDER_CONTROL_BY_STORAGE_KEY in operations)
        assertTrue(PostgresqlRpcOperation.ACQUIRE_FEDERATION_PROVIDER_LEASE in operations)
        assertTrue(PostgresqlRpcOperation.VALIDATE_FEDERATION_PROVIDER_LEASE in operations)
        assertTrue(PostgresqlRpcOperation.COMPARE_AND_SET_FEDERATION_PROVIDER_STATE in operations)
        assertTrue(PostgresqlRpcOperation.ACTIVATE_ADMINISTRATIVE_RECOVERY_TICKET in operations)
        assertTrue(PostgresqlRpcOperation.FIND_SCIM_GROUP in operations)
        assertTrue(PostgresqlRpcOperation.APPLY_SCIM_BATCH in operations)
    }

    @Test
    fun providerFailuresMapToStableSafeStoreErrors() {
        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            PostgresqlFailureMapper.fromProviderCode("23505").code
        )
        assertEquals(
            IdentityStoreErrorCode.REPLAY_DETECTED,
            PostgresqlFailureMapper.fromProviderCode("A0005").code
        )
        assertEquals(
            IdentityStoreErrorCode.LAST_OWNER,
            PostgresqlFailureMapper.fromProviderCode("A0004").code
        )
        assertEquals(
            IdentityStoreErrorCode.ALREADY_EXISTS,
            PostgresqlFailureMapper.fromProviderCode("A0013").code
        )
        val disabledProvider = PostgresqlFailureMapper.fromProviderCode("A0015")
        assertEquals(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED, disabledProvider.code)
        assertFalse(disabledProvider.retryable)
        val serializationFailure = PostgresqlFailureMapper.fromProviderCode("40001")
        assertEquals(IdentityStoreErrorCode.VERSION_CONFLICT, serializationFailure.code)
        assertTrue(serializationFailure.retryable)

        val unknown = PostgresqlStoreException(
            PostgresqlFailureMapper.fromProviderCode("raw-secret-provider-code")
        )
        assertEquals(IdentityStoreErrorCode.INTERNAL, unknown.safeError.code)
        assertFalse(unknown.toString().contains("raw-secret-provider-code"))
    }
}
