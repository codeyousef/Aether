package codes.yousef.aether.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityEntryUiStateTest {
    @Test
    fun `bootstrap state validates the one-time request without exposing its secret`() {
        val secret = "example-bootstrap-secret-value"
        val state = listOf(
            BootstrapIdentityUiAction.ChangeSecret(secret),
            BootstrapIdentityUiAction.ChangeDisplayName("Example Owner"),
            BootstrapIdentityUiAction.ChangePrimaryEmail("owner@example.test"),
            BootstrapIdentityUiAction.ChangeOrganizationName("Example Organization"),
            BootstrapIdentityUiAction.ChangeOrganizationSlug("Example-Org")
        ).fold(BootstrapIdentityUiState(), ::reduceBootstrapIdentityUiState)

        assertTrue(state.canSubmit)
        assertEquals(secret, assertNotNull(state.toRequest()).secret)
        assertEquals("example-org", state.organizationSlug)
        assertFalse(state.toString().contains(secret))
        assertFalse(BootstrapIdentityUiAction.ChangeSecret(secret).toString().contains(secret))
    }

    @Test
    fun `recovery state retains a bounded code only in its redacted wrapper`() {
        val code = "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF"
        val state = reduceRecoveryIdentityUiState(
            RecoveryIdentityUiState(),
            RecoveryIdentityUiAction.ChangeCode(code)
        )

        assertTrue(state.canSubmit)
        assertEquals(code, assertNotNull(state.toRequest()).code)
        assertFalse(state.toString().contains(code))
        assertFalse(RecoveryIdentityUiAction.ChangeCode(code).toString().contains(code))
    }
}
