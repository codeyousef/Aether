package codes.yousef.aether.example

import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.OrganizationId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityExampleWasmContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `wasm client compiles against the explicit organization and device contract`() {
        val contract = IdentityExampleContract()
        val organizationId = OrganizationId("018f47d2-8d4d-7abc-8def-1234567890ae")
        val encoded = json.encodeToString(contract)

        assertEquals(
            "/identity/v1/organizations/${organizationId.value}/invitations",
            contract.invitations(organizationId)
        )
        assertTrue("/oauth/device_authorization" in encoded)
        assertTrue("/oauth/token" in encoded)
        assertTrue("/identity/v1/invitations/enroll" in encoded)
        assertTrue("/identity/v1/recovery/codes/use" in encoded)
        assertTrue("/identity/v1/recovery/codes/replace" in encoded)
        assertTrue("/identity/v1/passkeys/step-up/start" in encoded)
        assertTrue("/identity/v1/passkeys/step-up/finish" in encoded)
        assertTrue("/identity/v1/passkeys" in encoded)
        assertTrue("/identity/v1/sessions/revoke-others" in encoded)
        assertTrue("/identity/v1/sessions/revoke-all" in encoded)
        assertTrue("/identity/v1/logout" in encoded)
        assertTrue("/identity/v1/recovery/admin/tickets" in encoded)
        assertTrue("/identity/v1/device/approve" in encoded)
        assertTrue("/identity/v1/device/deny" in encoded)
        assertTrue(contract.serviceCredentialCapabilities.none {
            it in Capability.IDENTITY_MANAGEMENT || it == Capability.ACCOUNT_RECOVERY_ADMIN
        })
    }
}
