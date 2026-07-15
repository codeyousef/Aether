package codes.yousef.aether.auth.summon

import codes.yousef.summon.modifier.Modifier
import codes.yousef.summon.runtime.PlatformRenderer

/**
 * Hydrates the exact common component tree emitted by [IdentitySsrRenderer] on the JVM.
 * [rootElementId] identifies Summon's outer hydration container, not the root component rendered
 * inside it. Passing the component ID makes the renderer remove its own mount point during the
 * initial reconciliation.
 */
fun hydrateIdentityUi(
    rootElementId: String,
    state: IdentityUiState,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    require(ROOT_ID_PATTERN.matches(rootElementId)) { "Invalid hydration root ID" }
    PlatformRenderer().hydrateComposableRoot(rootElementId) {
        IdentityUi(state, dispatcher, modifier)
    }
}

private val ROOT_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9_-]{0,127}")
