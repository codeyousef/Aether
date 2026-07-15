package codes.yousef.aether.auth.summon

import codes.yousef.summon.modifier.Modifier
import codes.yousef.summon.runtime.PlatformRenderer

/**
 * Renders the common identity surface as a complete Summon hydration document.
 *
 * The state is rendered into escaped HTML only; it is not passed to Summon's generic state
 * serializer. That prevents accidental serialization of future server-only state alongside the
 * browser shell.
 */
class IdentitySsrRenderer(
    private val rendererFactory: () -> PlatformRenderer = ::PlatformRenderer
) {
    fun render(
        state: IdentityUiState,
        dispatcher: IdentityUiDispatcher,
        language: String = "en",
        direction: String = "ltr",
        modifier: Modifier = Modifier()
    ): String {
        require(LANGUAGE_PATTERN.matches(language)) { "Invalid document language" }
        require(direction == "ltr" || direction == "rtl") { "Invalid document direction" }
        return rendererFactory().renderComposableRootWithHydration(language, direction) {
            IdentityUi(state, dispatcher, modifier)
        }
    }
}

private val LANGUAGE_PATTERN = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
