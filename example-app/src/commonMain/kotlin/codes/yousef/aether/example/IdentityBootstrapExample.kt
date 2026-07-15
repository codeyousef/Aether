package codes.yousef.aether.example

import codes.yousef.aether.auth.BootstrapIdentityRequest
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.summon.annotation.Composable
import codes.yousef.summon.components.display.Label
import codes.yousef.summon.components.display.Text
import codes.yousef.summon.components.html.H1
import codes.yousef.summon.components.html.H2
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

/** A browser-only input wrapper whose string representation never reveals the bootstrap secret. */
@JvmInline
value class BootstrapSecretInput private constructor(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "BootstrapSecretInput(<redacted>)"

    companion object {
        val Empty = BootstrapSecretInput("")
        fun of(value: String): BootstrapSecretInput = BootstrapSecretInput(value.take(MAX_BOOTSTRAP_SECRET_LENGTH))
    }
}

data class BootstrapIdentityUiState(
    val secret: BootstrapSecretInput = BootstrapSecretInput.Empty,
    val displayName: String = "",
    val primaryEmail: String = "",
    val organizationName: String = "",
    val organizationSlug: String = "",
    val busy: Boolean = false,
    val feedback: String? = null,
    val failed: Boolean = false
) {
    val canSubmit: Boolean
        get() = !busy && secret.reveal().length in 16..MAX_BOOTSTRAP_SECRET_LENGTH &&
            displayName.isNotBlank() && displayName.length <= MAX_NAME_LENGTH &&
            runCatching { EmailAddress(primaryEmail) }.isSuccess &&
            organizationName.isNotBlank() && organizationName.length <= MAX_NAME_LENGTH &&
            ORGANIZATION_SLUG.matches(organizationSlug)

    fun toRequest(): BootstrapIdentityRequest? = if (!canSubmit) null else runCatching {
        BootstrapIdentityRequest(
            secret = secret.reveal(),
            displayName = displayName,
            primaryEmail = EmailAddress(primaryEmail),
            organizationName = organizationName,
            organizationSlug = organizationSlug
        )
    }.getOrNull()
}

sealed interface BootstrapIdentityUiAction {
    data class ChangeSecret(val value: String) : BootstrapIdentityUiAction {
        override fun toString(): String = "ChangeSecret(<redacted>)"
    }

    data class ChangeDisplayName(val value: String) : BootstrapIdentityUiAction
    data class ChangePrimaryEmail(val value: String) : BootstrapIdentityUiAction
    data class ChangeOrganizationName(val value: String) : BootstrapIdentityUiAction
    data class ChangeOrganizationSlug(val value: String) : BootstrapIdentityUiAction
    data object Submit : BootstrapIdentityUiAction
}

fun interface BootstrapIdentityUiDispatcher {
    fun dispatch(action: BootstrapIdentityUiAction)
}

fun reduceBootstrapIdentityUiState(
    state: BootstrapIdentityUiState,
    action: BootstrapIdentityUiAction
): BootstrapIdentityUiState = when (action) {
    is BootstrapIdentityUiAction.ChangeSecret -> state.copy(
        secret = BootstrapSecretInput.of(action.value),
        feedback = null,
        failed = false
    )
    is BootstrapIdentityUiAction.ChangeDisplayName -> state.copy(
        displayName = action.value.take(MAX_NAME_LENGTH),
        feedback = null,
        failed = false
    )
    is BootstrapIdentityUiAction.ChangePrimaryEmail -> state.copy(
        primaryEmail = action.value.take(MAX_EMAIL_LENGTH),
        feedback = null,
        failed = false
    )
    is BootstrapIdentityUiAction.ChangeOrganizationName -> state.copy(
        organizationName = action.value.take(MAX_NAME_LENGTH),
        feedback = null,
        failed = false
    )
    is BootstrapIdentityUiAction.ChangeOrganizationSlug -> state.copy(
        organizationSlug = action.value.lowercase().filter { it.isLetterOrDigit() || it == '-' }
            .take(MAX_SLUG_LENGTH),
        feedback = null,
        failed = false
    )
    BootstrapIdentityUiAction.Submit -> state
}

/** Development bootstrap form shared by JVM SSR and wasmJs hydration. */
@Composable
fun BootstrapIdentityUi(
    state: BootstrapIdentityUiState,
    dispatcher: BootstrapIdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Main(
        modifier = Modifier()
            .id(BOOTSTRAP_ROOT_ID)
            .ariaLabel("Bootstrap the Aether identity example")
            .maxWidth("48rem")
            .margin("0 auto")
            .padding("1.5rem")
            .then(modifier)
    ) {
        Column(modifier = Modifier().gap("1rem")) {
            H1 { Text("Create the first owner") }
            P {
                Text(
                    "This development-only flow consumes AETHER_IDENTITY_BOOTSTRAP_SECRET once, " +
                        "then immediately asks you to enroll and verify a passkey."
                )
            }
            H2 { Text("Owner and organization") }
            BootstrapTextField(
                id = "aether-bootstrap-secret",
                label = "Bootstrap secret",
                value = state.secret.reveal(),
                onValueChange = { dispatcher.dispatch(BootstrapIdentityUiAction.ChangeSecret(it)) },
                type = TextFieldType.Password,
                enabled = !state.busy
            )
            BootstrapTextField(
                id = "aether-bootstrap-display-name",
                label = "Owner display name",
                value = state.displayName,
                onValueChange = { dispatcher.dispatch(BootstrapIdentityUiAction.ChangeDisplayName(it)) },
                enabled = !state.busy
            )
            BootstrapTextField(
                id = "aether-bootstrap-email",
                label = "Owner email",
                value = state.primaryEmail,
                onValueChange = { dispatcher.dispatch(BootstrapIdentityUiAction.ChangePrimaryEmail(it)) },
                type = TextFieldType.Email,
                enabled = !state.busy
            )
            BootstrapTextField(
                id = "aether-bootstrap-organization-name",
                label = "Organization name",
                value = state.organizationName,
                onValueChange = { dispatcher.dispatch(BootstrapIdentityUiAction.ChangeOrganizationName(it)) },
                enabled = !state.busy
            )
            BootstrapTextField(
                id = "aether-bootstrap-organization-slug",
                label = "Organization slug",
                value = state.organizationSlug,
                onValueChange = { dispatcher.dispatch(BootstrapIdentityUiAction.ChangeOrganizationSlug(it)) },
                enabled = !state.busy
            )
            state.feedback?.let { message ->
                P(modifier = Modifier().ariaLabel(if (state.failed) "Bootstrap error" else "Bootstrap status")) {
                    Text(message)
                }
            }
            Button(
                onClick = { dispatcher.dispatch(BootstrapIdentityUiAction.Submit) },
                label = if (state.busy) "Creating owner…" else "Create owner and enroll passkey",
                disabled = !state.canSubmit,
                dataAttributes = mapOf("identity-action" to "bootstrap"),
                modifier = Modifier().ariaLabel("Create the first owner and continue to passkey enrollment")
            )
            P { Text("The bootstrap secret is never stored by this page.") }
        }
    }
}

@Composable
private fun BootstrapTextField(
    id: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    type: TextFieldType = TextFieldType.Text
) {
    Label(label, forElement = id)
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        type = type,
        isEnabled = enabled,
        // Summon 0.7.0.2's JVM renderer otherwise replaces the requested type and generated ID.
        modifier = Modifier()
            .id(id)
            .attribute("name", id)
            .attribute("type", type.name.lowercase())
            .ariaLabel(label)
    )
}

const val BOOTSTRAP_ROOT_ID: String = "aether-bootstrap"
private const val MAX_BOOTSTRAP_SECRET_LENGTH = 512
private const val MAX_NAME_LENGTH = 200
private const val MAX_EMAIL_LENGTH = 320
private const val MAX_SLUG_LENGTH = 63
private val ORGANIZATION_SLUG = Regex("[a-z0-9][a-z0-9-]{1,62}")
