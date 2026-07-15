package codes.yousef.aether.auth.scim

import codes.yousef.aether.auth.ScimOperationId
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object ScimSchemas {
    const val USER = "urn:ietf:params:scim:schemas:core:2.0:User"
    const val GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group"
    const val PATCH_OPERATION = "urn:ietf:params:scim:api:messages:2.0:PatchOp"
    const val LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
    const val ERROR = "urn:ietf:params:scim:api:messages:2.0:Error"
}

@Serializable
data class ScimName(
    val formatted: String? = null,
    val familyName: String? = null,
    val givenName: String? = null,
    val middleName: String? = null,
    val honorificPrefix: String? = null,
    val honorificSuffix: String? = null
) {
    init {
        listOf(formatted, familyName, givenName, middleName, honorificPrefix, honorificSuffix).forEach {
            require(it == null || it.length <= 200) { "SCIM name component is too long" }
        }
    }
}

@Serializable
data class ScimEmail(
    val value: String,
    val type: String? = null,
    val primary: Boolean = false,
    val display: String? = null
) {
    init {
        require(value.length in 3..320 && '@' in value && value.none(Char::isWhitespace)) {
            "Invalid SCIM email"
        }
        require(type == null || (type.isNotBlank() && type.length <= 64)) { "Invalid SCIM email type" }
        require(display == null || display.length <= 200) { "SCIM email display is too long" }
    }
}

@Serializable
data class ScimMultiValue(
    val value: String,
    val type: String? = null,
    val primary: Boolean = false,
    val display: String? = null,
    @SerialName("\$ref") val reference: String? = null
) {
    init {
        require(value.isNotBlank() && value.length <= 16_384) { "Invalid SCIM multi-valued attribute" }
        require(type == null || (type.isNotBlank() && type.length <= 64)) { "Invalid SCIM attribute type" }
        require(display == null || display.length <= 200) { "SCIM display value is too long" }
        require(reference == null || reference.length <= 2_048) { "SCIM reference is too long" }
    }
}

@Serializable
data class ScimAddress(
    val formatted: String? = null,
    val streetAddress: String? = null,
    val locality: String? = null,
    val region: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val type: String? = null,
    val primary: Boolean = false
) {
    init {
        listOf(formatted, streetAddress, locality, region, postalCode).forEach {
            require(it == null || it.length <= 1_000) { "SCIM address component is too long" }
        }
        require(country == null || country.length == 2) { "SCIM address country must be a two-letter code" }
        require(type == null || (type.isNotBlank() && type.length <= 64)) { "Invalid SCIM address type" }
    }
}

@Serializable
data class ScimMember(
    val value: String,
    val display: String? = null,
    @SerialName("\$ref") val reference: String? = null,
    val type: String? = null
) {
    init {
        require(Regex("[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}").matches(value)) { "Invalid SCIM member value" }
        require(display == null || display.length <= 200) { "SCIM member display is too long" }
        require(reference == null || reference.length <= 2_048) { "SCIM member reference is too long" }
        require(type == null || type in setOf("User", "Group", "direct", "indirect")) {
            "Invalid SCIM member type"
        }
    }
}

/** Strict writable representation accepted for User POST and PUT. */
@Serializable
data class ScimUserDocument(
    val schemas: List<String>,
    /** Read-only when supplied by a client and ignored by the engine. */
    val id: String? = null,
    val externalId: String? = null,
    val userName: String,
    val name: ScimName? = null,
    val displayName: String? = null,
    val nickName: String? = null,
    val profileUrl: String? = null,
    val title: String? = null,
    val userType: String? = null,
    val preferredLanguage: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val active: Boolean = true,
    /** Password provisioning is deliberately unsupported by Aether's passkey-only identity model. */
    val password: String? = null,
    val emails: List<ScimEmail> = emptyList(),
    val phoneNumbers: List<ScimMultiValue> = emptyList(),
    val ims: List<ScimMultiValue> = emptyList(),
    val photos: List<ScimMultiValue> = emptyList(),
    val addresses: List<ScimAddress> = emptyList(),
    val entitlements: List<ScimMultiValue> = emptyList(),
    /** Informational only; tenant authorization is derived exclusively from mapped Groups. */
    val roles: List<ScimMultiValue> = emptyList(),
    val x509Certificates: List<ScimMultiValue> = emptyList(),
    /** Read-only when supplied by a client and ignored by the engine. */
    val groups: List<ScimMember> = emptyList(),
    /** Read-only when supplied by a client and ignored by the engine. */
    val meta: ScimMeta? = null
) {
    init {
        require(schemas == listOf(ScimSchemas.USER)) { "Unsupported SCIM User schema" }
        require(userName.isNotBlank() && userName.length <= 320) { "userName must be 1..320 characters" }
        require(externalId == null || (externalId.isNotBlank() && externalId.length <= 1_024)) {
            "externalId must be absent or 1..1024 characters"
        }
        require(displayName == null || (displayName.isNotBlank() && displayName.length <= 200)) {
            "displayName must be absent or 1..200 characters"
        }
        listOf(nickName, title, userType, preferredLanguage, locale, timezone).forEach {
            require(it == null || (it.isNotBlank() && it.length <= 200)) { "Invalid SCIM User attribute" }
        }
        require(profileUrl == null || (profileUrl.isNotBlank() && profileUrl.length <= 2_048)) {
            "Invalid SCIM profileUrl"
        }
        require(password == null) { "Password provisioning is not supported" }
        require(emails.size <= 20) { "Too many SCIM emails" }
        require(emails.count { it.primary } <= 1) { "At most one SCIM email may be primary" }
        listOf(phoneNumbers, ims, photos, entitlements, roles, x509Certificates).forEach {
            require(it.size <= 100) { "Too many SCIM attribute values" }
            require(it.count { value -> value.primary } <= 1) { "At most one SCIM attribute value may be primary" }
        }
        require(addresses.size <= 20 && addresses.count { it.primary } <= 1) { "Too many SCIM addresses" }
    }
}

/** Strict writable representation accepted for Group POST and PUT. */
@Serializable
data class ScimGroupDocument(
    val schemas: List<String>,
    /** Read-only when supplied by a client and ignored by the engine. */
    val id: String? = null,
    val externalId: String? = null,
    val displayName: String,
    val members: List<ScimMember> = emptyList(),
    /** Read-only when supplied by a client and ignored by the engine. */
    val meta: ScimMeta? = null
) {
    init {
        require(schemas == listOf(ScimSchemas.GROUP)) { "Unsupported SCIM Group schema" }
        require(displayName.isNotBlank() && displayName.length <= 200) { "displayName must be 1..200 characters" }
        require(externalId == null || (externalId.isNotBlank() && externalId.length <= 1_024)) {
            "externalId must be absent or 1..1024 characters"
        }
        require(members.size <= 5_000) { "Too many SCIM group members" }
        require(members.map { it.value }.toSet().size == members.size) { "Duplicate SCIM group member" }
    }
}

@Serializable
data class ScimMeta(
    val resourceType: String,
    val created: Instant,
    val lastModified: Instant,
    val location: String,
    val version: String
) {
    init {
        require(resourceType == "User" || resourceType == "Group")
        require(lastModified >= created)
        require(location.length <= 2_048 && (location.startsWith("https://") || location.startsWith("http://")))
        require(version.isNotBlank() && version.length <= 200)
    }
}

@Serializable
data class ScimUserResource(
    val schemas: List<String> = listOf(ScimSchemas.USER),
    val id: String,
    val externalId: String? = null,
    val userName: String,
    val name: ScimName? = null,
    val displayName: String? = null,
    val nickName: String? = null,
    val profileUrl: String? = null,
    val title: String? = null,
    val userType: String? = null,
    val preferredLanguage: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val active: Boolean,
    val emails: List<ScimEmail> = emptyList(),
    val phoneNumbers: List<ScimMultiValue> = emptyList(),
    val ims: List<ScimMultiValue> = emptyList(),
    val photos: List<ScimMultiValue> = emptyList(),
    val addresses: List<ScimAddress> = emptyList(),
    val entitlements: List<ScimMultiValue> = emptyList(),
    val roles: List<ScimMultiValue> = emptyList(),
    val x509Certificates: List<ScimMultiValue> = emptyList(),
    val groups: List<ScimMember> = emptyList(),
    val meta: ScimMeta
)

@Serializable
data class ScimGroupResource(
    val schemas: List<String> = listOf(ScimSchemas.GROUP),
    val id: String,
    val externalId: String? = null,
    val displayName: String,
    val members: List<ScimMember> = emptyList(),
    val meta: ScimMeta
)

@Serializable
data class ScimUserListResponse(
    val schemas: List<String> = listOf(ScimSchemas.LIST_RESPONSE),
    val totalResults: Int,
    val startIndex: Int,
    val itemsPerPage: Int,
    @SerialName("Resources") val resources: List<ScimUserResource>
)

@Serializable
data class ScimGroupListResponse(
    val schemas: List<String> = listOf(ScimSchemas.LIST_RESPONSE),
    val totalResults: Int,
    val startIndex: Int,
    val itemsPerPage: Int,
    @SerialName("Resources") val resources: List<ScimGroupResource>
)

@Serializable
enum class ScimPatchAction {
    @SerialName("add") ADD,
    @SerialName("remove") REMOVE,
    @SerialName("replace") REPLACE
}

@Serializable
data class ScimPatchOperation(
    val op: ScimPatchAction,
    val path: String? = null,
    val value: JsonElement? = null
)

@Serializable
data class ScimPatchRequest(
    val schemas: List<String>,
    @SerialName("Operations") val operations: List<ScimPatchOperation>
) {
    init {
        require(schemas == listOf(ScimSchemas.PATCH_OPERATION)) { "Unsupported SCIM PATCH schema" }
        require(operations.isNotEmpty() && operations.size <= 100) { "PATCH must contain 1..100 operations" }
    }
}

@Serializable
enum class ScimErrorType(val wireName: String) {
    @SerialName("invalidFilter") INVALID_FILTER("invalidFilter"),
    @SerialName("tooMany") TOO_MANY("tooMany"),
    @SerialName("uniqueness") UNIQUENESS("uniqueness"),
    @SerialName("mutability") MUTABILITY("mutability"),
    @SerialName("invalidSyntax") INVALID_SYNTAX("invalidSyntax"),
    @SerialName("invalidPath") INVALID_PATH("invalidPath"),
    @SerialName("noTarget") NO_TARGET("noTarget"),
    @SerialName("invalidValue") INVALID_VALUE("invalidValue"),
    @SerialName("sensitive") SENSITIVE("sensitive"),
}

@Serializable
data class ScimErrorResponse(
    val schemas: List<String> = listOf(ScimSchemas.ERROR),
    val status: String,
    val scimType: String? = null,
    val detail: String
)

enum class ScimHttpMethod { GET, POST, PUT, PATCH, DELETE }

class ScimRequest(
    val method: ScimHttpMethod,
    val path: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0),
    val operationId: ScimOperationId? = null,
    val requestId: String? = null
) {
    val query: Map<String, String> = query.toMap()
    private val normalizedHeaders = headers.mapKeys { it.key.lowercase() }
    private val bodyValue = body.copyOf()

    init {
        require(path.startsWith('/') && '?' !in path && '#' !in path && path.length <= 2_048) {
            "SCIM path must be an absolute path without query or fragment"
        }
        require(query.keys.all { it.isNotBlank() && it.length <= 100 }) { "Invalid SCIM query parameter" }
        require(query.values.all { it.length <= 2_048 }) { "SCIM query parameter is too long" }
        require(normalizedHeaders.all { (name, value) -> name.length <= 100 && value.length <= 8_192 }) {
            "SCIM header is too long"
        }
        require(requestId == null || (requestId.isNotBlank() && requestId.length <= 255)) { "Invalid request ID" }
    }

    fun header(name: String): String? = normalizedHeaders[name.lowercase()]
    fun bodyBytes(): ByteArray = bodyValue.copyOf()
}

class ScimResponse(
    val status: Int,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0)
) {
    val headers: Map<String, String> = headers.toMap()
    private val bodyValue = body.copyOf()

    init { require(status in 100..599) }

    fun bodyBytes(): ByteArray = bodyValue.copyOf()
}

internal fun weakEtag(version: Long): String = "W/\"$version\""
