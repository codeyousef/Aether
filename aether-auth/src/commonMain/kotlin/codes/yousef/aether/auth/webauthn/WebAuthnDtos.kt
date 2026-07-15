package codes.yousef.aether.auth.webauthn

import codes.yousef.aether.auth.ChallengeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PublicKeyCredentialRpEntity(
    val id: String,
    val name: String
)

@Serializable
data class PublicKeyCredentialUserEntity(
    /** Unpadded base64url user handle; browser binary conversion happens in the UI/client layer. */
    val id: String,
    val name: String,
    val displayName: String
) {
    override fun toString(): String =
        "PublicKeyCredentialUserEntity(id=<redacted>, name=<redacted>, displayName=<redacted>)"
}

@Serializable
data class PublicKeyCredentialParameter(
    val type: String = PUBLIC_KEY_TYPE,
    val alg: Int = ES256_ALGORITHM
)

@Serializable
data class PublicKeyCredentialDescriptor(
    val type: String = PUBLIC_KEY_TYPE,
    val id: String,
    val transports: List<String> = emptyList()
) {
    override fun toString(): String =
        "PublicKeyCredentialDescriptor(type=$type, id=<redacted>, transports=$transports)"
}

@Serializable
data class AuthenticatorSelectionCriteria(
    val authenticatorAttachment: String? = null,
    val residentKey: String = REQUIRED,
    val requireResidentKey: Boolean = true,
    val userVerification: String = REQUIRED
)

@Serializable
data class PublicKeyCredentialCreationOptions(
    val challenge: String,
    val rp: PublicKeyCredentialRpEntity,
    val user: PublicKeyCredentialUserEntity,
    val pubKeyCredParams: List<PublicKeyCredentialParameter> = listOf(PublicKeyCredentialParameter()),
    val timeout: Long,
    val excludeCredentials: List<PublicKeyCredentialDescriptor> = emptyList(),
    val authenticatorSelection: AuthenticatorSelectionCriteria = AuthenticatorSelectionCriteria(),
    val attestation: String = NONE,
    val hints: List<String> = emptyList()
) {
    override fun toString(): String =
        "PublicKeyCredentialCreationOptions(challenge=<redacted>, rp=$rp, user=<redacted>, " +
            "pubKeyCredParams=$pubKeyCredParams, timeout=$timeout, excludeCredentials=<redacted>, " +
            "authenticatorSelection=$authenticatorSelection, attestation=$attestation, hints=$hints)"
}

@Serializable
data class PublicKeyCredentialRequestOptions(
    val challenge: String,
    val timeout: Long,
    val rpId: String,
    /** Empty for username-free discoverable authentication. */
    val allowCredentials: List<PublicKeyCredentialDescriptor> = emptyList(),
    val userVerification: String = REQUIRED,
    val hints: List<String> = emptyList()
) {
    override fun toString(): String =
        "PublicKeyCredentialRequestOptions(challenge=<redacted>, timeout=$timeout, rpId=$rpId, " +
            "allowCredentials=<redacted>, userVerification=$userVerification, hints=$hints)"
}

@Serializable
data class WebAuthnRegistrationStartResponse(
    val ceremonyId: ChallengeId,
    val publicKey: PublicKeyCredentialCreationOptions
) {
    override fun toString(): String =
        "WebAuthnRegistrationStartResponse(ceremonyId=$ceremonyId, publicKey=<redacted>)"
}

@Serializable
data class WebAuthnAuthenticationStartResponse(
    val ceremonyId: ChallengeId,
    val publicKey: PublicKeyCredentialRequestOptions
) {
    override fun toString(): String =
        "WebAuthnAuthenticationStartResponse(ceremonyId=$ceremonyId, publicKey=<redacted>)"
}

@Serializable
data class AuthenticatorAttestationResponseDto(
    val clientDataJSON: String,
    val attestationObject: String,
    val transports: List<String> = emptyList()
) {
    override fun toString(): String =
        "AuthenticatorAttestationResponseDto(clientDataJSON=<redacted>, attestationObject=<redacted>, " +
            "transports=$transports)"
}

@Serializable
data class AuthenticatorAssertionResponseDto(
    val clientDataJSON: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String? = null
) {
    override fun toString(): String =
        "AuthenticatorAssertionResponseDto(clientDataJSON=<redacted>, authenticatorData=<redacted>, " +
            "signature=<redacted>, userHandle=${if (userHandle == null) "none" else "<redacted>"})"
}

@Serializable
data class RegistrationPublicKeyCredentialDto(
    val id: String,
    val rawId: String,
    val type: String,
    val response: AuthenticatorAttestationResponseDto,
    val authenticatorAttachment: String? = null,
    val clientExtensionResults: Map<String, JsonElement> = emptyMap()
) {
    override fun toString(): String =
        "RegistrationPublicKeyCredentialDto(id=<redacted>, rawId=<redacted>, type=$type, " +
            "response=<redacted>, authenticatorAttachment=$authenticatorAttachment, " +
            "clientExtensionResults=<redacted>)"
}

@Serializable
data class AuthenticationPublicKeyCredentialDto(
    val id: String,
    val rawId: String,
    val type: String,
    val response: AuthenticatorAssertionResponseDto,
    val authenticatorAttachment: String? = null,
    val clientExtensionResults: Map<String, JsonElement> = emptyMap()
) {
    override fun toString(): String =
        "AuthenticationPublicKeyCredentialDto(id=<redacted>, rawId=<redacted>, type=$type, " +
            "response=<redacted>, authenticatorAttachment=$authenticatorAttachment, " +
            "clientExtensionResults=<redacted>)"
}

internal const val PUBLIC_KEY_TYPE = "public-key"
internal const val ES256_ALGORITHM = -7
internal const val REQUIRED = "required"
internal const val NONE = "none"

internal fun requireValidBrowserCredentialEnvelope(
    id: String,
    rawId: String,
    type: String,
    authenticatorAttachment: String?,
    extensions: Map<String, JsonElement>
) {
    if (id != rawId || id.length !in 2..5_464 || type != PUBLIC_KEY_TYPE || extensions.isNotEmpty()) {
        throw WebAuthnDecodingException()
    }
    if (authenticatorAttachment != null &&
        authenticatorAttachment != "platform" && authenticatorAttachment != "cross-platform"
    ) throw WebAuthnDecodingException()
}
