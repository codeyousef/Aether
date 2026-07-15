package codes.yousef.aether.example

import codes.yousef.summon.runtime.PlatformRenderer
import kotlin.test.Test
import kotlin.test.assertContains

class IdentityEntrySsrTest {
    @Test
    fun `bootstrap SSR preserves password email and stable field attributes`() {
        val html = PlatformRenderer().renderComposableRootWithHydration("en", "ltr") {
            BootstrapIdentityUi(BootstrapIdentityUiState(), BootstrapIdentityUiDispatcher { })
        }

        assertContains(html, "type=\"password\"")
        assertContains(html, "id=\"aether-bootstrap-secret\"")
        assertContains(html, "name=\"aether-bootstrap-secret\"")
        assertContains(html, "type=\"email\"")
        assertContains(html, "id=\"aether-bootstrap-email\"")
    }

    @Test
    fun `recovery SSR never renders its recovery code as plain text`() {
        val html = PlatformRenderer().renderComposableRootWithHydration("en", "ltr") {
            RecoveryIdentityUi(RecoveryIdentityUiState(), RecoveryIdentityUiDispatcher { })
        }

        assertContains(html, "type=\"password\"")
        assertContains(html, "id=\"aether-recovery-code\"")
        assertContains(html, "name=\"aether-recovery-code\"")
    }
}
