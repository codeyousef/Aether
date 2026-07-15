package codes.yousef.aether.example

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.installCallLogging
import codes.yousef.aether.core.pipeline.installRecovery
import codes.yousef.aether.core.pipeline.installSecurityHeaders
import codes.yousef.aether.auth.summon.IdentitySsrRenderer
import codes.yousef.aether.auth.summon.IdentityUiDispatcher
import codes.yousef.aether.auth.summon.IdentityUiFeedback
import codes.yousef.aether.auth.summon.IdentityUiState
import codes.yousef.aether.auth.summon.RegistrationUiState
import codes.yousef.aether.auth.AuthenticationAssurance
import codes.yousef.aether.auth.identityContext
import codes.yousef.summon.runtime.PlatformRenderer
import codes.yousef.aether.ui.a
import codes.yousef.aether.ui.body
import codes.yousef.aether.ui.div
import codes.yousef.aether.ui.h1
import codes.yousef.aether.ui.h2
import codes.yousef.aether.ui.head
import codes.yousef.aether.ui.li
import codes.yousef.aether.ui.p
import codes.yousef.aether.ui.render
import codes.yousef.aether.ui.title
import codes.yousef.aether.ui.ul
import codes.yousef.aether.web.router
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Passkey-first example shell. The browser client uses the fixed identity API contract; a host
 * maps those routes to the selected authority services and store. No password, JWT, legacy
 * session, group, or global-permission compatibility path exists.
 */
fun main() = runBlocking(AetherDispatcher.dispatcher) {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val bootstrapSecret = System.getenv(BOOTSTRAP_SECRET_ENV)
        ?: error("Set $BOOTSTRAP_SECRET_ENV to a 16..512 character development bootstrap secret")
    val identity = ExampleIdentityAuthority.create(port, bootstrapSecret)
    identity.start()
    val contract = IdentityExampleContract()
    val browserAssets = browserAssetRoot()
    val routes = router {
        get("/") { exchange -> exchange.renderLandingPage(contract) }
        get(contract.identityUi) { exchange -> exchange.renderIdentityPage() }
        get(contract.bootstrapUi) { exchange -> exchange.renderBootstrapPage() }
        get(contract.recoveryUi) { exchange -> exchange.renderRecoveryPage() }
        get("/identity/v1/client-config") { exchange -> exchange.respondJson(contract) }
        get("/identity-client/*") { exchange -> exchange.respondBrowserAsset(browserAssets) }
        get("/health") { exchange -> exchange.respondJson(mapOf("status" to "ok")) }
    }
    val pipeline = Pipeline().apply {
        installRecovery()
        installCallLogging()
        installSecurityHeaders()
        use(identity.identityMiddleware())
        use(identity.httpApi.asMiddleware())
        use(routes.asMiddleware())
    }
    val server = VertxServer(
        VertxServerConfig(port = port, maxRequestBodySize = MAX_IDENTITY_REQUEST_BODY_BYTES),
        pipeline
    ) { exchange ->
        exchange.respond(404, "Not found")
    }
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { server.stop() } })
    server.start()
    println("Aether passkey identity example listening on http://localhost:$port")
}

private suspend fun Exchange.renderLandingPage(contract: IdentityExampleContract) {
    render {
        element("html") {
            head { title { text("Aether passkey identity") } }
            body {
                h1 { text("Aether passkey-first identity") }
                p { text("Passwords and bearer JWT fallback are intentionally unavailable.") }
                div {
                    h2 { text("Identity flows") }
                    ul {
                        li { text("Discoverable passkey registration and sign-in") }
                        li { text("Organization-scoped membership and authorization") }
                        li { text("RFC 8628 CLI device authorization") }
                        li { text("Single-use recovery codes and passkey re-enrollment") }
                    }
                }
                a(contract.identityUi) { text("Open the passkey identity UI") }
                a(contract.bootstrapUi) { text("Bootstrap the first owner (development only)") }
                a(contract.recoveryUi) { text("Recover an account with a single-use code") }
                a(contract.clientConfig) { text("View public client configuration") }
            }
        }
    }
}

private suspend fun Exchange.renderIdentityPage() {
    val renderer = IdentitySsrRenderer()
    val restrictedEnrollment = identityContext.session?.assurance == AuthenticationAssurance.RECOVERY
    val document = renderer.render(
        state = IdentityUiState(
            registration = RegistrationUiState(signInEnabled = !restrictedEnrollment),
            feedback = if (restrictedEnrollment) {
                IdentityUiFeedback(
                    "Restricted recovery session: enroll a passkey to continue. Other identity actions are unavailable."
                )
            } else null
        ),
        dispatcher = IdentityUiDispatcher { }
    ).withBrowserEntryPoint()
    response.setHeader("Cache-Control", "no-store")
    respondHtml(html = document)
}

private suspend fun Exchange.renderBootstrapPage() {
    val document = PlatformRenderer().renderComposableRootWithHydration("en", "ltr") {
        BootstrapIdentityUi(
            state = BootstrapIdentityUiState(),
            dispatcher = BootstrapIdentityUiDispatcher { }
        )
    }.withBrowserEntryPoint()
    response.setHeader("Cache-Control", "no-store")
    respondHtml(html = document)
}

private suspend fun Exchange.renderRecoveryPage() {
    val document = PlatformRenderer().renderComposableRootWithHydration("en", "ltr") {
        RecoveryIdentityUi(
            state = RecoveryIdentityUiState(),
            dispatcher = RecoveryIdentityUiDispatcher { }
        )
    }.withBrowserEntryPoint()
    response.setHeader("Cache-Control", "no-store")
    respondHtml(html = document)
}

private fun String.withBrowserEntryPoint(): String =
    replace("<link rel=\"preload\" href=\"/summon-hydration.js\" as=\"script\">", "")
        .replace("<script src=\"/summon-bootloader.js\" defer></script>", "")
        .replace(
            "</body>",
            "<script src=\"/identity-client/example-app.js\" defer></script></body>"
        )

private fun browserAssetRoot(): Path? = System.getProperty("aether.example.webAssets")
    ?.takeIf { it.isNotBlank() }
    ?.let(Path::of)
    ?.toAbsolutePath()
    ?.normalize()

private suspend fun Exchange.respondBrowserAsset(root: Path?) {
    val name = request.path.removePrefix("/identity-client/")
    if (root == null || !Regex("[A-Za-z0-9_.-]{1,200}").matches(name)) {
        notFound()
        return
    }
    val file = root.resolve(name).normalize()
    if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > MAX_BROWSER_ASSET_BYTES) {
        notFound()
        return
    }
    val contentType = when (file.fileName.toString().substringAfterLast('.', "")) {
        "js" -> "text/javascript; charset=utf-8"
        "wasm" -> "application/wasm"
        "map" -> "application/json; charset=utf-8"
        else -> "application/octet-stream"
    }
    response.setHeader("Cache-Control", "no-store")
    respondBytes(contentType = contentType, bytes = Files.readAllBytes(file))
}

private suspend inline fun <reified T> Exchange.respondJson(value: T) {
    response.statusCode = 200
    response.setHeader("Content-Type", "application/json; charset=utf-8")
    response.setHeader("Cache-Control", "no-store")
    response.write(EXAMPLE_JSON.encodeToString(value))
    response.end()
}

private const val MAX_BROWSER_ASSET_BYTES = 16L * 1024 * 1024
private const val MAX_IDENTITY_REQUEST_BODY_BYTES = 1_048_576
private val EXAMPLE_JSON = Json { encodeDefaults = true }
