package codes.yousef.aether.example

import codes.yousef.aether.auth.RecoveryCodeUseRequest
import codes.yousef.summon.annotation.Composable
import codes.yousef.summon.components.display.Label
import codes.yousef.summon.components.display.Text
import codes.yousef.summon.components.html.H1
import codes.yousef.summon.components.html.Main
import codes.yousef.summon.components.html.P
import codes.yousef.summon.components.input.Button
import codes.yousef.summon.components.input.TextField
import codes.yousef.summon.components.input.TextFieldType
import codes.yousef.summon.components.layout.Column
import codes.yousef.summon.modifier.Modifier
import codes.yousef.summon.modifier.ariaLabel
import codes.yousef.summon.modifier.attribute
import codes.yousef.summon.modifier.gap
import codes.yousef.summon.modifier.id
import codes.yousef.summon.modifier.margin
import codes.yousef.summon.modifier.maxWidth
import codes.yousef.summon.modifier.padding
import kotlin.jvm.JvmInline

@JvmInline
value class RecoveryCodeInput private constructor(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "RecoveryCodeInput(<redacted>)"

    companion object {
        val Empty = RecoveryCodeInput("")
        fun of(value: String): RecoveryCodeInput = RecoveryCodeInput(value.take(MAX_RECOVERY_CODE_LENGTH))
    }
}

data class RecoveryIdentityUiState(
    val code: RecoveryCodeInput = RecoveryCodeInput.Empty,
    val busy: Boolean = false,
    val feedback: String? = null,
    val failed: Boolean = false
) {
    val canSubmit: Boolean get() = !busy && code.reveal().length in 20..MAX_RECOVERY_CODE_LENGTH
    fun toRequest(): RecoveryCodeUseRequest? = if (canSubmit) RecoveryCodeUseRequest(code.reveal()) else null
}

sealed interface RecoveryIdentityUiAction {
    data class ChangeCode(val value: String) : RecoveryIdentityUiAction {
        override fun toString(): String = "ChangeCode(<redacted>)"
    }
    data object Submit : RecoveryIdentityUiAction
}

fun interface RecoveryIdentityUiDispatcher {
    fun dispatch(action: RecoveryIdentityUiAction)
}

fun reduceRecoveryIdentityUiState(
    state: RecoveryIdentityUiState,
    action: RecoveryIdentityUiAction
): RecoveryIdentityUiState = when (action) {
    is RecoveryIdentityUiAction.ChangeCode -> state.copy(
        code = RecoveryCodeInput.of(action.value),
        feedback = null,
        failed = false
    )
    RecoveryIdentityUiAction.Submit -> state
}

@Composable
fun RecoveryIdentityUi(
    state: RecoveryIdentityUiState,
    dispatcher: RecoveryIdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Main(
        modifier = Modifier()
            .id(RECOVERY_ROOT_ID)
            .ariaLabel("Recover an Aether identity")
            .maxWidth("40rem")
            .margin("0 auto")
            .padding("1.5rem")
            .then(modifier)
    ) {
        Column(modifier = Modifier().gap("1rem")) {
            H1 { Text("Recover with a single-use code") }
            P {
                Text(
                    "A valid code creates a restricted 15-minute session. It can only enroll a " +
                        "replacement passkey or sign out."
                )
            }
            Label("Recovery code", forElement = "aether-recovery-code")
            TextField(
                value = state.code.reveal(),
                onValueChange = { dispatcher.dispatch(RecoveryIdentityUiAction.ChangeCode(it)) },
                label = "Recovery code",
                type = TextFieldType.Password,
                isEnabled = !state.busy,
                // Keep the SSR boundary secret-safe despite Summon 0.7.0.2's JVM text-type default.
                modifier = Modifier()
                    .id("aether-recovery-code")
                    .attribute("name", "aether-recovery-code")
                    .attribute("type", "password")
                    .ariaLabel("Recovery code")
            )
            state.feedback?.let { message ->
                P(modifier = Modifier().ariaLabel(if (state.failed) "Recovery error" else "Recovery status")) {
                    Text(message)
                }
            }
            Button(
                onClick = { dispatcher.dispatch(RecoveryIdentityUiAction.Submit) },
                label = if (state.busy) "Recovering…" else "Recover account",
                disabled = !state.canSubmit,
                dataAttributes = mapOf("identity-action" to "recover-account"),
                modifier = Modifier().ariaLabel("Recover account")
            )
        }
    }
}

const val RECOVERY_ROOT_ID: String = "aether-recovery-entry"
private const val MAX_RECOVERY_CODE_LENGTH = 128
