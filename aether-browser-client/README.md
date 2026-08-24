# Aether Browser Client

`aether-browser-client` is Aether's small, browser-only HTTP and bootstrap layer for Kotlin/JS and
Kotlin/Wasm-JS applications. Browser interop stays inside Aether: applications use Kotlin APIs and
do not need handwritten JavaScript or direct DOM access.

```kotlin
val client = BrowserHttpClient(
    BrowserHttpClientConfig(
        csrfProvider = BrowserCsrfProvider.sessionStorage("portfolio.csrf")
    )
)

val result: RunResponse = client.post("/api/run", RunRequest(source))
val state: InitialState = BrowserBootstrap.decode("portfolio-bootstrap")
BrowserHistory.replace("/playground#output")
```

Only same-origin relative request paths are accepted. Fetch always uses
`credentials = "same-origin"`; request and response limits, timeout, and redirect handling are
explicit configuration.
