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
val updated: RunResponse = client.patch("/api/run/42", PatchRunRequest(name = "nightly"))
val metadata = client.head("/api/run/42")
val retryAfter = metadata.retryAfter

val state: InitialState = BrowserBootstrap.decode("portfolio-bootstrap")
BrowserHistory.replace("/playground#output")
```

`get`, `post`, `put`, `patch`, `delete`, `head`, `options`, and raw `execute` all use one
checked request path. Unsafe methods (`POST`, `PUT`, `PATCH`, and `DELETE`) receive the configured
CSRF header; `HEAD` and `OPTIONS` reject request bodies. Raw responses expose status, headers,
`ETag`, `Retry-After`, and an empty body without requiring JSON decoding.

Only root-relative same-origin paths are accepted; schemes, authority-relative paths, fragments,
backslashes, and control characters are rejected before transport. Fetch always uses
`credentials = "same-origin"` and defaults to `redirect = "error"`. Request limits are checked as
UTF-8 bytes before fetch. Response streams are counted as UTF-8 bytes while read and aborted as
soon as their configured limit is exceeded. Timeout and coroutine cancellation abort the fetch.

This client intentionally does **not** accept absolute provider URLs. Presigned ciphertext object
transfers need a separate credential-free transport (`credentials = "omit"`) owned by the
application so browser session cookies, CSRF material, and generic API headers cannot cross the
object-storage boundary.
