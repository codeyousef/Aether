# Aether identity WASI crypto host

This non-published native library is versioned with Aether `0.6.0.0` and is the reference host
implementation for the SemVer WIT package `aether:identity-crypto@0.6.0`. It uses OpenSSL 3 for
SHA-256, HMAC-SHA-256,
constant-time comparison, ES256, RSA-SHA256, key validation, opaque-handle
signing, and CSPRNG self-tests. It returns stable host status values and clears
OpenSSL's error queue instead of forwarding provider text into the guest.

The required embedding WASI runtime maps these functions to
`aether-auth/src/wasmWasiMain/resources/aether-identity-crypto.wit`. That world
also imports `wasi:http/outgoing-handler@0.2.0`, so outbound transport is an
explicit component requirement rather than an embedding convention. Wall
clock and random values must originate in the native host. A Node-only Kotlin
test double or Fetch shim is not an accepted production host.

Important: this repository currently supplies the hardened native library, WIT
contract, and guest-side ABI/readiness tests, but not a runnable component that
binds the Kotlin 2.3.x Preview1 guest to that WIT world. The C test below proves
the OpenSSL primitives only. It is not the wasmWasi production release gate.
The `0.6.0.0` artifacts include the experimental wasmWasi target, but production
wasmWasi identity-authority hosting is unsupported until a component-model binding
and combined host/guest runner exercise the real library and `wasi:http` in CI.
This matches the [Kotlin/Wasm WASI documentation](https://kotlinlang.org/docs/wasm-wasi.html),
which identifies the Kotlin 2.3 toolchain target as WASI 0.1/Preview 1 and leaves
WASI 0.2 component-model support to a later toolchain.

The C boundary accepts uncompressed 65-byte P-256 public keys and 64-byte raw
`r || s` signatures for ES256. RSA keys are strict, fully consumed DER SPKI;
RSA PKCS#1 v1.5 with SHA-256 is limited to validated 2,048–16,384-bit keys.
Guest-controlled messages are capped at 16 MiB, random requests at 1 MiB,
HMAC keys at 64 KiB, SPKI at 16 KiB, and RSA signatures at 2 KiB. Inputs that
exceed a ceiling fail before parsing. Public calls clear the OpenSSL per-thread
error queue on entry and exit and expose only `aether_identity_host_status`.

For WIT `sign`, the embedding host creates an
`aether_identity_signing_key_store` and registers provider-owned `EVP_PKEY`
objects under versioned opaque handles. The store retains OpenSSL references,
so provider or HSM keys do not need to export private bytes. Guest code can
request ES256 or RSA-SHA256 signatures by handle only. ES256 results are
canonical fixed-width `r || s`; RSA results use PKCS#1 v1.5 with SHA-256.
Removing a handle makes it immediately unavailable to subsequent operations.

`timespec_get(TIME_UTC)` supplies the native wall clock; a WASI libc maps that
call to the host realtime clock. `RAND_priv_bytes_ex` supplies guest secrets
from OpenSSL's private CSPRNG, which must itself be seeded by the WASI host.

Build and run the native primitive gate with:

```text
cmake -S aether-identity-wasi-host -B build/identity-wasi-host -DCMAKE_BUILD_TYPE=Release
cmake --build build/identity-wasi-host --config Release
ctest --test-dir build/identity-wasi-host --output-on-failure
```
