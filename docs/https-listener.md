# HTTPS and private correlation IDs

`VertxServer` can bind HTTPS through the existing Vert.x 4.5.11 TLS transport:

```kotlin
val config = VertxServerConfig(
    host = "127.0.0.1",
    port = 8080,
    tls = HttpsListenerConfig(
        certificateChainPath = runtimeCertificatePath,
        privateKeyPath = runtimePrivateKeyPath,
        minimumProtocol = HttpsMinimumProtocol.TLS_1_2
    ),
    diagnostics = RequestDiagnosticsPolicy(requestIdSource = RequestIdSource.SERVER_GENERATED_ONLY)
)
```

The certificate-chain PEM starts with the leaf. The private-key PEM must contain an unencrypted
PKCS#8 RSA or EC key. Runtime file paths and material belong in deployment configuration, outside
source control. Each file is bounded to 1 MiB. Startup snapshots material, checks certificate
validity and key matching, and initializes TLS before accepting requests. Missing, malformed or
mismatched material yields `HttpsListenerException` with no provider cause or private paths.
Configured TLS never falls back to plaintext. `tls = null` explicitly selects plaintext.

The minimum is TLS 1.2; only 1.2 and 1.3 are enabled. Selecting `TLS_1_3` disables 1.2. There is no
in-place reload: restart the listener after changing files. The listener encrypts transport and
presents server identity; the client must trust the certificate chain and validate its hostname.
It does not request or validate client certificates. Existing decoded-byte body limits, streaming
backpressure, deadlines and graceful shutdown apply over TLS. The router's existing `SslConfig`
remains a separate API; this typed configuration targets `VertxServer`.

Both JVM listeners accept `diagnostics` in their server configuration. The default
`ACCEPT_VALID_CALLER` policy preserves valid caller correlation IDs. Private consumers should set
`SERVER_GENERATED_ONLY` at the listener, so body/parser failures as well as handler, recovery,
Identity middleware and call logging use internally generated IDs. Valid-looking caller tokens
and UUIDs are ignored in that profile. No trusted-ingress authentication is inferred. Request IDs
are diagnostic correlation values, never authentication credentials.
