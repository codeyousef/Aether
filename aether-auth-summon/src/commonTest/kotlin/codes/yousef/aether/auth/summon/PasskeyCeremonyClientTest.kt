package codes.yousef.aether.auth.summon

import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.webauthn.AuthenticationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.AuthenticatorAssertionResponseDto
import codes.yousef.aether.auth.webauthn.AuthenticatorAttestationResponseDto
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialCreationOptions
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialRequestOptions
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialRpEntity
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialUserEntity
import codes.yousef.aether.auth.webauthn.RegistrationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.WebAuthnAuthenticationStartResponse
import codes.yousef.aether.auth.webauthn.WebAuthnRegistrationStartResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PasskeyCeremonyClientTest {
    @Test
    fun registrationPassesOnlyPublicOptionsAndOpaqueCeremonyId() = runTest {
        val options = creationOptions()
        val credential = registrationCredential()
        val browser = RecordingBrowser(registration = credential)
        val gateway = RecordingGateway(registrationOptions = options)

        PasskeyCeremonyClient(gateway, browser).register("Laptop")

        assertSame(options, browser.createdWith)
        assertEquals("Laptop", gateway.registrationName)
        assertEquals(ChallengeId("registration-ceremony"), gateway.finishedRegistrationId)
        assertSame(credential, gateway.finishedRegistrationCredential)
    }

    @Test
    fun discoverableSignInAndStepUpPreservePurpose() = runTest {
        val options = requestOptions()
        val credential = authenticationCredential()
        val browser = RecordingBrowser(authentication = credential)
        val gateway = RecordingGateway(authenticationOptions = options)
        val client = PasskeyCeremonyClient(gateway, browser)

        client.authenticate(PasskeyAuthenticationPurpose.DISCOVERABLE_SIGN_IN)
        client.authenticate(PasskeyAuthenticationPurpose.STEP_UP)

        assertSame(options, browser.gotWith)
        assertEquals(
            listOf(PasskeyAuthenticationPurpose.DISCOVERABLE_SIGN_IN, PasskeyAuthenticationPurpose.STEP_UP),
            gateway.startedPurposes
        )
        assertEquals(gateway.startedPurposes, gateway.finishedPurposes)
        assertEquals(ChallengeId("authentication-ceremony"), gateway.finishedAuthenticationId)
        assertSame(credential, gateway.finishedAuthenticationCredential)
    }

    private class RecordingBrowser(
        private val registration: RegistrationPublicKeyCredentialDto = registrationCredential(),
        private val authentication: AuthenticationPublicKeyCredentialDto = authenticationCredential()
    ) : PasskeyBrowserClient {
        var createdWith: PublicKeyCredentialCreationOptions? = null
        var gotWith: PublicKeyCredentialRequestOptions? = null

        override suspend fun create(options: PublicKeyCredentialCreationOptions): RegistrationPublicKeyCredentialDto {
            createdWith = options
            return registration
        }

        override suspend fun get(options: PublicKeyCredentialRequestOptions): AuthenticationPublicKeyCredentialDto {
            gotWith = options
            return authentication
        }
    }

    private class RecordingGateway(
        private val registrationOptions: PublicKeyCredentialCreationOptions = creationOptions(),
        private val authenticationOptions: PublicKeyCredentialRequestOptions = requestOptions()
    ) : PasskeyCeremonyGateway {
        var registrationName: String? = null
        var finishedRegistrationId: ChallengeId? = null
        var finishedRegistrationCredential: RegistrationPublicKeyCredentialDto? = null
        val startedPurposes = mutableListOf<PasskeyAuthenticationPurpose>()
        val finishedPurposes = mutableListOf<PasskeyAuthenticationPurpose>()
        var finishedAuthenticationId: ChallengeId? = null
        var finishedAuthenticationCredential: AuthenticationPublicKeyCredentialDto? = null

        override suspend fun startRegistration(passkeyName: String): WebAuthnRegistrationStartResponse {
            registrationName = passkeyName
            return WebAuthnRegistrationStartResponse(ChallengeId("registration-ceremony"), registrationOptions)
        }

        override suspend fun finishRegistration(
            ceremonyId: ChallengeId,
            credential: RegistrationPublicKeyCredentialDto
        ) {
            finishedRegistrationId = ceremonyId
            finishedRegistrationCredential = credential
        }

        override suspend fun startAuthentication(
            purpose: PasskeyAuthenticationPurpose
        ): WebAuthnAuthenticationStartResponse {
            startedPurposes += purpose
            return WebAuthnAuthenticationStartResponse(ChallengeId("authentication-ceremony"), authenticationOptions)
        }

        override suspend fun finishAuthentication(
            ceremonyId: ChallengeId,
            purpose: PasskeyAuthenticationPurpose,
            credential: AuthenticationPublicKeyCredentialDto
        ) {
            finishedAuthenticationId = ceremonyId
            finishedPurposes += purpose
            finishedAuthenticationCredential = credential
        }
    }

    companion object {
        private fun creationOptions() = PublicKeyCredentialCreationOptions(
            challenge = "AQIDBA",
            rp = PublicKeyCredentialRpEntity("login.example.test", "Example"),
            user = PublicKeyCredentialUserEntity("dXNlcg", "person@example.test", "Person"),
            timeout = 300_000
        )

        private fun requestOptions() = PublicKeyCredentialRequestOptions(
            challenge = "BQYHCA",
            timeout = 300_000,
            rpId = "login.example.test"
        )

        private fun registrationCredential() = RegistrationPublicKeyCredentialDto(
            id = "Y3JlZGVudGlhbA",
            rawId = "Y3JlZGVudGlhbA",
            type = "public-key",
            response = AuthenticatorAttestationResponseDto(
                clientDataJSON = "Y2xpZW50",
                attestationObject = "YXR0ZXN0YXRpb24"
            )
        )

        private fun authenticationCredential() = AuthenticationPublicKeyCredentialDto(
            id = "Y3JlZGVudGlhbA",
            rawId = "Y3JlZGVudGlhbA",
            type = "public-key",
            response = AuthenticatorAssertionResponseDto(
                clientDataJSON = "Y2xpZW50",
                authenticatorData = "YXV0aGVudGljYXRvcg",
                signature = "c2lnbmF0dXJl"
            )
        )
    }
}
