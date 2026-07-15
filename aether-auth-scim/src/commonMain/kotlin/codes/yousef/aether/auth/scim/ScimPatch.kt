package codes.yousef.aether.auth.scim

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class MutableUserDocument(
    var externalId: String?,
    var userName: String,
    var name: ScimName?,
    var displayName: String?,
    var nickName: String?,
    var profileUrl: String?,
    var title: String?,
    var userType: String?,
    var preferredLanguage: String?,
    var locale: String?,
    var timezone: String?,
    var active: Boolean,
    val emails: MutableList<ScimEmail>,
    val phoneNumbers: MutableList<ScimMultiValue>,
    val ims: MutableList<ScimMultiValue>,
    val photos: MutableList<ScimMultiValue>,
    val addresses: MutableList<ScimAddress>,
    val entitlements: MutableList<ScimMultiValue>,
    val roles: MutableList<ScimMultiValue>,
    val x509Certificates: MutableList<ScimMultiValue>
) {
    fun immutable(): ScimUserDocument = ScimUserDocument(
        schemas = listOf(ScimSchemas.USER),
        externalId = externalId,
        userName = userName,
        name = name,
        displayName = displayName,
        nickName = nickName,
        profileUrl = profileUrl,
        title = title,
        userType = userType,
        preferredLanguage = preferredLanguage,
        locale = locale,
        timezone = timezone,
        active = active,
        emails = emails.toList(),
        phoneNumbers = phoneNumbers.toList(),
        ims = ims.toList(),
        photos = photos.toList(),
        addresses = addresses.toList(),
        entitlements = entitlements.toList(),
        roles = roles.toList(),
        x509Certificates = x509Certificates.toList()
    )
}

internal data class MutableGroupDocument(
    var externalId: String?,
    var displayName: String,
    val members: LinkedHashMap<String, ScimMember>
) {
    fun immutable(): ScimGroupDocument = ScimGroupDocument(
        schemas = listOf(ScimSchemas.GROUP),
        externalId = externalId,
        displayName = displayName,
        members = members.values.toList()
    )
}

internal object ScimPatchApplicator {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
    private val memberFilter = Regex(
        "^members\\[value\\s+eq\\s+\"((?:[^\"\\\\]|\\\\.)+)\"]$",
        RegexOption.IGNORE_CASE
    )

    fun user(record: ScimUserRecord, request: ScimPatchRequest): ScimUserDocument {
        val target = MutableUserDocument(
            externalId = record.externalId,
            userName = record.userName,
            name = record.name,
            displayName = record.displayName,
            nickName = record.nickName,
            profileUrl = record.profileUrl,
            title = record.title,
            userType = record.userType,
            preferredLanguage = record.preferredLanguage,
            locale = record.locale,
            timezone = record.timezone,
            active = record.active,
            emails = record.emails.toMutableList(),
            phoneNumbers = record.phoneNumbers.toMutableList(),
            ims = record.ims.toMutableList(),
            photos = record.photos.toMutableList(),
            addresses = record.addresses.toMutableList(),
            entitlements = record.entitlements.toMutableList(),
            roles = record.roles.toMutableList(),
            x509Certificates = record.x509Certificates.toMutableList()
        )
        request.operations.forEach { operation -> applyUser(target, operation) }
        return try {
            target.immutable()
        } catch (_: IllegalArgumentException) {
            throw ScimPatchException(ScimErrorType.INVALID_VALUE)
        }
    }

    fun group(record: ScimGroupRecord, request: ScimPatchRequest): ScimGroupDocument {
        val target = MutableGroupDocument(
            externalId = record.externalId,
            displayName = record.displayName,
            members = record.memberUserResourceIds.associateWithTo(linkedMapOf()) { ScimMember(it) }
        )
        request.operations.forEach { operation -> applyGroup(target, operation) }
        return try {
            target.immutable()
        } catch (_: IllegalArgumentException) {
            throw ScimPatchException(ScimErrorType.INVALID_VALUE)
        }
    }

    private fun applyUser(target: MutableUserDocument, operation: ScimPatchOperation) {
        val path = operation.path?.lowercase()
        if (path == null) {
            if (operation.op == ScimPatchAction.REMOVE) throw ScimPatchException(ScimErrorType.NO_TARGET)
            val fields = operation.value as? JsonObject ?: throw ScimPatchException(ScimErrorType.INVALID_VALUE)
            fields.forEach { (name, value) ->
                applyUser(target, operation.copy(path = name, value = value))
            }
            return
        }
        when (path) {
            "username" -> when (operation.op) {
                ScimPatchAction.REMOVE -> throw ScimPatchException(ScimErrorType.MUTABILITY)
                else -> target.userName = requiredString(operation.value)
            }
            "externalid" -> target.externalId = nullableString(operation)
            "displayname" -> target.displayName = nullableString(operation)
            "nickname" -> target.nickName = nullableString(operation)
            "profileurl" -> target.profileUrl = nullableString(operation)
            "title" -> target.title = nullableString(operation)
            "usertype" -> target.userType = nullableString(operation)
            "preferredlanguage" -> target.preferredLanguage = nullableString(operation)
            "locale" -> target.locale = nullableString(operation)
            "timezone" -> target.timezone = nullableString(operation)
            "active" -> when (operation.op) {
                ScimPatchAction.REMOVE -> throw ScimPatchException(ScimErrorType.MUTABILITY)
                else -> target.active = operation.value?.jsonPrimitive?.booleanOrNull
                    ?: throw ScimPatchException(ScimErrorType.INVALID_VALUE)
            }
            "name" -> target.name = when (operation.op) {
                ScimPatchAction.REMOVE -> null
                else -> decode(operation.value, ScimName.serializer())
            }
            "name.formatted", "name.familyname", "name.givenname", "name.middlename",
            "name.honorificprefix", "name.honorificsuffix" -> {
                val current = target.name ?: ScimName()
                val value = nullableString(operation)
                target.name = when (path) {
                    "name.formatted" -> current.copy(formatted = value)
                    "name.familyname" -> current.copy(familyName = value)
                    "name.givenname" -> current.copy(givenName = value)
                    "name.middlename" -> current.copy(middleName = value)
                    "name.honorificprefix" -> current.copy(honorificPrefix = value)
                    else -> current.copy(honorificSuffix = value)
                }
            }
            "emails" -> {
                when (operation.op) {
                    ScimPatchAction.REMOVE -> target.emails.clear()
                    ScimPatchAction.REPLACE -> {
                        target.emails.clear()
                        target.emails += emails(operation.value)
                    }
                    ScimPatchAction.ADD -> {
                        emails(operation.value).forEach { added ->
                            val existing = target.emails.indexOfFirst { it.value.equals(added.value, ignoreCase = true) }
                            if (existing >= 0) target.emails[existing] = added else target.emails += added
                        }
                    }
                }
            }
            "phonenumbers" -> applyMultiValues(target.phoneNumbers, operation)
            "ims" -> applyMultiValues(target.ims, operation)
            "photos" -> applyMultiValues(target.photos, operation)
            "entitlements" -> applyMultiValues(target.entitlements, operation)
            "roles" -> applyMultiValues(target.roles, operation)
            "x509certificates" -> applyMultiValues(target.x509Certificates, operation)
            "addresses" -> when (operation.op) {
                ScimPatchAction.REMOVE -> target.addresses.clear()
                ScimPatchAction.REPLACE -> {
                    target.addresses.clear()
                    target.addresses += addresses(operation.value)
                }
                ScimPatchAction.ADD -> target.addresses += addresses(operation.value)
            }
            "password" -> throw ScimPatchException(ScimErrorType.SENSITIVE)
            "schemas", "id", "meta", "groups" -> throw ScimPatchException(ScimErrorType.MUTABILITY)
            else -> throw ScimPatchException(ScimErrorType.INVALID_PATH)
        }
    }

    private fun applyGroup(target: MutableGroupDocument, operation: ScimPatchOperation) {
        val originalPath = operation.path
        val path = originalPath?.lowercase()
        if (path == null) {
            if (operation.op == ScimPatchAction.REMOVE) throw ScimPatchException(ScimErrorType.NO_TARGET)
            val fields = operation.value as? JsonObject ?: throw ScimPatchException(ScimErrorType.INVALID_VALUE)
            fields.forEach { (name, value) -> applyGroup(target, operation.copy(path = name, value = value)) }
            return
        }
        when (path) {
            "displayname" -> when (operation.op) {
                ScimPatchAction.REMOVE -> throw ScimPatchException(ScimErrorType.MUTABILITY)
                else -> target.displayName = requiredString(operation.value)
            }
            "externalid" -> target.externalId = nullableString(operation)
            "members" -> when (operation.op) {
                ScimPatchAction.REMOVE -> target.members.clear()
                ScimPatchAction.REPLACE -> {
                    target.members.clear()
                    members(operation.value).forEach { target.members[it.value] = it }
                }
                ScimPatchAction.ADD -> members(operation.value).forEach { target.members[it.value] = it }
            }
            "schemas", "id", "meta" -> throw ScimPatchException(ScimErrorType.MUTABILITY)
            else -> {
                val match = memberFilter.matchEntire(originalPath)
                    ?: throw ScimPatchException(ScimErrorType.INVALID_PATH)
                if (operation.op != ScimPatchAction.REMOVE) throw ScimPatchException(ScimErrorType.INVALID_PATH)
                val memberId = decodeJsonString(match.groupValues[1])
                // RFC 7644 section 3.5.2.2: removing a member that is not present is a successful no-op.
                target.members.remove(memberId)
            }
        }
    }

    private fun nullableString(operation: ScimPatchOperation): String? = when (operation.op) {
        ScimPatchAction.REMOVE -> null
        else -> requiredString(operation.value)
    }

    private fun requiredString(element: JsonElement?): String {
        val primitive = element as? JsonPrimitive ?: throw ScimPatchException(ScimErrorType.INVALID_VALUE)
        if (!primitive.isString) throw ScimPatchException(ScimErrorType.INVALID_VALUE)
        return primitive.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw ScimPatchException(ScimErrorType.INVALID_VALUE)
    }

    private fun emails(value: JsonElement?): List<ScimEmail> = when (value) {
        is JsonArray -> decode(value, ListSerializer(ScimEmail.serializer()))
        is JsonObject -> listOf(decode(value, ScimEmail.serializer()))
        else -> throw ScimPatchException(ScimErrorType.INVALID_VALUE)
    }

    private fun multiValues(value: JsonElement?): List<ScimMultiValue> = when (value) {
        is JsonArray -> decode(value, ListSerializer(ScimMultiValue.serializer()))
        is JsonObject -> listOf(decode(value, ScimMultiValue.serializer()))
        else -> throw ScimPatchException(ScimErrorType.INVALID_VALUE)
    }

    private fun addresses(value: JsonElement?): List<ScimAddress> = when (value) {
        is JsonArray -> decode(value, ListSerializer(ScimAddress.serializer()))
        is JsonObject -> listOf(decode(value, ScimAddress.serializer()))
        else -> throw ScimPatchException(ScimErrorType.INVALID_VALUE)
    }

    private fun applyMultiValues(target: MutableList<ScimMultiValue>, operation: ScimPatchOperation) {
        when (operation.op) {
            ScimPatchAction.REMOVE -> target.clear()
            ScimPatchAction.REPLACE -> {
                target.clear()
                target += multiValues(operation.value)
            }
            ScimPatchAction.ADD -> multiValues(operation.value).forEach { added ->
                val existing = target.indexOfFirst { it.value == added.value && it.type == added.type }
                if (existing >= 0) target[existing] = added else target += added
            }
        }
    }

    private fun members(value: JsonElement?): List<ScimMember> = when (value) {
        is JsonArray -> decode(value, ListSerializer(ScimMember.serializer()))
        is JsonObject -> listOf(decode(value, ScimMember.serializer()))
        else -> throw ScimPatchException(ScimErrorType.INVALID_VALUE)
    }

    private fun decodeJsonString(escapedContent: String): String = try {
        json.parseToJsonElement("\"$escapedContent\"").jsonPrimitive.content
    } catch (_: Throwable) {
        throw ScimPatchException(ScimErrorType.INVALID_PATH)
    }

    private fun <T> decode(value: JsonElement?, deserializer: DeserializationStrategy<T>): T = try {
        json.decodeFromJsonElement(deserializer, value ?: throw ScimPatchException(ScimErrorType.INVALID_VALUE))
    } catch (failure: ScimPatchException) {
        throw failure
    } catch (_: Throwable) {
        throw ScimPatchException(ScimErrorType.INVALID_VALUE)
    }
}

internal class ScimPatchException(val type: ScimErrorType) : IllegalArgumentException("Invalid SCIM PATCH operation")
