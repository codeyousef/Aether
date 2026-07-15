package codes.yousef.aether.auth.summon

import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.webauthn.AuthenticationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialCreationOptions
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialRequestOptions
import codes.yousef.aether.auth.webauthn.RegistrationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.WebAuthnAuthenticationStartResponse
import codes.yousef.aether.auth.webauthn.WebAuthnRegistrationStartResponse

/**
 * Narrow browser authority. Implementations may invoke `navigator.credentials`, but can receive
 * only public ceremony options and return only browser credential envelopes.
 */
interface PasskeyBrowserClient {
    suspend fun create(options: PublicKeyCredentialCreationOptions): RegistrationPublicKeyCredentialDto
    suspend fun get(options: PublicKeyCredentialRequestOptions): AuthenticationPublicKeyCredentialDto
}

enum class PasskeyAuthenticationPurpose { DISCOVERABLE_SIGN_IN, STEP_UP }

/** Server JSON API boundary. It never returns a cookie, token, secret reference, or key material. */
interface PasskeyCeremonyGateway {
    suspend fun startRegistration(passkeyName: String): WebAuthnRegistrationStartResponse

    suspend fun finishRegistration(
        ceremonyId: ChallengeId,
        credential: RegistrationPublicKeyCredentialDto
    )

    suspend fun startAuthentication(purpose: PasskeyAuthenticationPurpose): WebAuthnAuthenticationStartResponse

    suspend fun finishAuthentication(
        ceremonyId: ChallengeId,
        purpose: PasskeyAuthenticationPurpose,
        credential: AuthenticationPublicKeyCredentialDto
    )
}

/** Coordinates the public start/browser/finish exchange without retaining ceremony material. */
class PasskeyCeremonyClient(
    private val gateway: PasskeyCeremonyGateway,
    private val browser: PasskeyBrowserClient
) {
    suspend fun register(passkeyName: String) {
        require(passkeyName.isNotBlank() && passkeyName.length <= 200) { "Invalid passkey name" }
        val start = gateway.startRegistration(passkeyName)
        val credential = browser.create(start.publicKey)
        gateway.finishRegistration(start.ceremonyId, credential)
    }

    suspend fun authenticate(purpose: PasskeyAuthenticationPurpose) {
        val start = gateway.startAuthentication(purpose)
        val credential = browser.get(start.publicKey)
        gateway.finishAuthentication(start.ceremonyId, purpose, credential)
    }
}

enum class PasskeyBrowserErrorCode {
    NOT_SUPPORTED,
    NOT_ALLOWED,
    ABORTED,
    SECURITY_ERROR,
    INVALID_RESPONSE,
    UNKNOWN
}

class PasskeyBrowserException(val code: PasskeyBrowserErrorCode) : IllegalStateException(
    "Passkey browser ceremony failed"
)
