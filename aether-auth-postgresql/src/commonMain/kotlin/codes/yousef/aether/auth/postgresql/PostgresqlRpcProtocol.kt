package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.IdentityEnvironment
import codes.yousef.aether.auth.IdentityStoreError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Every store method has a fixed, versioned database function; callers cannot inject RPC names. */
enum class PostgresqlRpcOperation(
    val wireName: String,
    val functionName: String = "v1_$wireName"
) {
    ASSERT_ENVIRONMENT("assert_environment"),
    FIND_USER("find_user"),
    FIND_USER_BY_EMAIL("find_user_by_email"),
    FIND_CREDENTIAL("find_credential"),
    FIND_CREDENTIAL_BY_WEB_AUTHN_ID("find_credential_by_web_authn_id"),
    LIST_CREDENTIALS_FOR_USER("list_credentials_for_user"),
    FIND_SESSION("find_session"),
    LIST_SESSIONS_FOR_USER("list_sessions_for_user"),
    FIND_ORGANIZATION("find_organization"),
    FIND_ORGANIZATION_BY_SLUG("find_organization_by_slug"),
    LIST_ORGANIZATIONS_FOR_USER("list_organizations_for_user"),
    FIND_MEMBERSHIP("find_membership"),
    FIND_MEMBERSHIP_FOR_USER("find_membership_for_user"),
    LIST_MEMBERSHIPS_FOR_ORGANIZATION("list_memberships_for_organization"),
    FIND_INVITATION("find_invitation"),
    FIND_INVITATION_BY_TOKEN_DIGEST("find_invitation_by_token_digest"),
    LIST_INVITATIONS_FOR_ORGANIZATION("list_invitations_for_organization"),
    FIND_SERVICE_IDENTITY("find_service_identity"),
    LIST_SERVICE_IDENTITIES_FOR_ORGANIZATION("list_service_identities_for_organization"),
    FIND_SERVICE_CREDENTIAL_BY_PREFIX("find_service_credential_by_prefix"),
    LIST_SERVICE_CREDENTIALS_FOR_IDENTITY("list_service_credentials_for_identity"),
    FIND_EXTERNAL_IDENTITY("find_external_identity"),
    FIND_FEDERATION_PROVIDER_CONTROL("find_federation_provider_control"),
    FIND_FEDERATION_PROVIDER_CONTROL_BY_STORAGE_KEY("find_federation_provider_control_by_storage_key"),
    FIND_SCIM_GROUP("find_scim_group"),
    FIND_CHALLENGE("find_challenge"),
    FIND_RECOVERY_CODE_BY_SELECTOR("find_recovery_code_by_selector"),
    LIST_RECOVERY_CODES_FOR_USER("list_recovery_codes_for_user"),
    FIND_DEVICE_GRANT("find_device_grant"),
    FIND_DEVICE_GRANT_BY_DEVICE_CODE_DIGEST("find_device_grant_by_device_code_digest"),
    FIND_DEVICE_GRANT_BY_USER_CODE_DIGEST("find_device_grant_by_user_code_digest"),
    FIND_DEVICE_TOKEN_FAMILY("find_device_token_family"),
    FIND_DEVICE_ACCESS_TOKEN_BY_SELECTOR("find_device_access_token_by_selector"),
    FIND_DEVICE_REFRESH_TOKEN_BY_SELECTOR("find_device_refresh_token_by_selector"),
    LIST_AUDIT_EVENTS_FOR_ORGANIZATION("list_audit_events_for_organization"),
    PURGE_AUDIT_EVENTS("purge_audit_events"),
    CREATE_CHALLENGE("create_challenge"),
    CONSUME_CHALLENGE("consume_challenge"),
    APPEND_AUDIT_EVENT("append_audit_event"),
    BOOTSTRAP_IDENTITY("bootstrap_identity"),
    COMPLETE_CREDENTIAL_REGISTRATION("complete_credential_registration"),
    COMPLETE_CREDENTIAL_AUTHENTICATION("complete_credential_authentication"),
    QUARANTINE_CREDENTIAL_AUTHENTICATION("quarantine_credential_authentication"),
    MUTATE_CREDENTIAL("mutate_credential"),
    CREATE_SESSION("create_session"),
    TOUCH_IDENTITY_SESSION("touch_identity_session"),
    ROTATE_SESSION("rotate_session"),
    REVOKE_SESSION("revoke_session"),
    REVOKE_USER_SESSIONS("revoke_user_sessions"),
    ACQUIRE_FEDERATION_PROVIDER_LEASE("acquire_federation_provider_lease"),
    VALIDATE_FEDERATION_PROVIDER_LEASE("validate_federation_provider_lease"),
    COMPARE_AND_SET_FEDERATION_PROVIDER_STATE("compare_and_set_federation_provider_state"),
    REPLACE_RECOVERY_CODES("replace_recovery_codes"),
    CONSUME_RECOVERY_CODE("consume_recovery_code"),
    ACTIVATE_ADMINISTRATIVE_RECOVERY_TICKET("activate_administrative_recovery_ticket"),
    REDEEM_ADMINISTRATIVE_RECOVERY_TICKET("redeem_administrative_recovery_ticket"),
    COMPLETE_RECOVERY_ENROLLMENT("complete_recovery_enrollment"),
    CREATE_ORGANIZATION("create_organization"),
    MUTATE_ORGANIZATION("mutate_organization"),
    CREATE_INVITATION("create_invitation"),
    MUTATE_INVITATION("mutate_invitation"),
    ENROLL_INVITATION("enroll_invitation"),
    CREATE_MEMBERSHIP("create_membership"),
    MUTATE_MEMBERSHIP("mutate_membership"),
    CREATE_SERVICE_IDENTITY("create_service_identity"),
    MUTATE_SERVICE_IDENTITY("mutate_service_identity"),
    CREATE_SERVICE_CREDENTIAL("create_service_credential"),
    REVOKE_SERVICE_CREDENTIAL("revoke_service_credential"),
    COMPARE_AND_SET_DEVICE_GRANT("compare_and_set_device_grant"),
    EXCHANGE_DEVICE_GRANT("exchange_device_grant"),
    ROTATE_DEVICE_REFRESH_TOKEN("rotate_device_refresh_token"),
    REVOKE_DEVICE_TOKEN_FAMILY("revoke_device_token_family"),
    ROTATE_SERVICE_CREDENTIAL("rotate_service_credential"),
    LINK_EXTERNAL_IDENTITY("link_external_identity"),
    RECORD_EXTERNAL_IDENTITY_REPLAY("record_external_identity_replay"),
    APPLY_SCIM_MUTATION("apply_scim_mutation"),
    APPLY_SCIM_BATCH("apply_scim_batch")
}

@Serializable
data class PostgresqlRpcRequestEnvelope(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val operation: String,
    val environment: IdentityEnvironment,
    val namespace: String,
    val requestId: String? = null,
    val payload: JsonElement
) {
    init {
        require(protocolVersion == CURRENT_PROTOCOL_VERSION) { "Unsupported PostgreSQL identity RPC protocol" }
        require(PostgresqlRpcOperation.entries.any { it.wireName == operation }) { "Unknown PostgreSQL identity RPC operation" }
        require(namespace.isNotBlank() && namespace.length <= 63) { "Invalid PostgreSQL identity namespace" }
        require(requestId == null || (requestId.isNotBlank() && requestId.length <= 255)) { "Invalid PostgreSQL RPC request ID" }
    }
}

@Serializable
enum class PostgresqlRpcOutcome {
    @SerialName("success") SUCCESS,
    @SerialName("failure") FAILURE
}

@Serializable
data class PostgresqlRpcResponseEnvelope(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val operation: String,
    val outcome: PostgresqlRpcOutcome,
    val result: JsonElement = JsonNull,
    val error: IdentityStoreError? = null
) {
    init {
        require(protocolVersion == CURRENT_PROTOCOL_VERSION) { "Unsupported PostgreSQL identity RPC protocol" }
        require(PostgresqlRpcOperation.entries.any { it.wireName == operation }) { "Unknown PostgreSQL identity RPC operation" }
        when (outcome) {
            PostgresqlRpcOutcome.SUCCESS -> require(error == null) { "Successful RPC responses must not contain an error" }
            PostgresqlRpcOutcome.FAILURE -> require(result == JsonNull && error != null) {
                "Failed RPC responses require only a safe store error"
            }
        }
    }
}

@Serializable
internal data class PostgrestFunctionRequest(
    @SerialName("p_request") val pRequest: PostgresqlRpcRequestEnvelope
)

@Serializable
internal data class PostgrestErrorDocument(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null,
    val hint: String? = null
)

internal const val CURRENT_PROTOCOL_VERSION: Int = 1
