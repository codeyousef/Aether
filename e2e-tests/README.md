# Aether identity browser tests

This directory separates deterministic scaffold checks from browser tests and state-changing live
identity journeys. Nothing here silently falls back to a fake WebAuthn implementation.

## Test lanes

`npm test` runs dependency-free checks over the E2E configuration and security gates. It neither
starts a server nor opens a browser.

`npm run test:browser` starts the fully wired `:example-app:run`, including its wasm distribution,
unless `AETHER_E2E_EXTERNAL_EXAMPLE=1`. It checks the SSR/hydrated identity, first-owner bootstrap,
recovery-entry, navigation, responsive layout, keyboard, generic-error, and public-config surfaces
in Chromium, Firefox, and WebKit. Install all three declared browser binaries first:

```text
npm install
npm run typecheck
npm run install:browsers
npm run test:browser
```

Dependencies are pinned by the committed `package-lock.json`. CI and release verification must use
`npm ci --prefix e2e-tests`; do not replace it with an unconstrained install.

`npm run test:live` is Chromium-only because it uses Chrome DevTools Protocol's WebAuthn virtual
authenticator. It is destructive and refuses to run unless the target is explicitly marked
disposable:

```text
AETHER_E2E_EPHEMERAL=1 \
AETHER_E2E_BASE_URL=http://localhost:8080 \
AETHER_IDENTITY_BOOTSTRAP_SECRET=a-disposable-test-secret \
npm run test:live
```

The runner always sets `AETHER_E2E_LIVE_IDENTITY=1` and `PLAYWRIGHT_NO_COPY_PROMPT=1`; callers cannot
accidentally re-enable Playwright's automatic page snapshot for this lane. For a loopback URL, the
cross-platform live runner starts the runnable example unless
`AETHER_E2E_EXTERNAL_EXAMPLE=1`. The process uses the required bootstrap secret to create the first
owner, captures the session-bound CSRF token in `sessionStorage`, enrolls and authenticates a
virtual passkey, manually enters the RFC 8628 user code without putting it in a URL, completes
explicit-organization approval and opaque bearer-token reads,
uses the distinct step-up endpoints, then exercises single-use recovery and restricted passkey
re-enrollment. The target must start with a fresh disposable store. The preflight
accepts authentication/validation failures for malformed probes, but rejects missing (`404`),
methodless (`405`), and unimplemented (`501`) handlers. A non-loopback target must use HTTPS, set
`AETHER_E2E_ALLOW_REMOTE=1`, and normally set `AETHER_E2E_EXTERNAL_EXAMPLE=1`.

The recovery-code generation, recovery entry, constrained enrollment, and one-time replacement-code
display are wired in the example. `npm run test:release` additionally requires
`AETHER_E2E_RECOVERY_FLOW=1` as an explicit no-skips release acknowledgement.

The non-secret UI lane writes failure evidence to `test-results/` and `playwright-report/`; both are
ignored and may be uploaded by CI. The secret-bearing live lane disables traces, screenshots,
videos, retries, HTML reports, and Playwright's automatic `error-context.md` page snapshot. A
partial state-changing journey is never replayed against its already-mutated store, and Playwright
refuses to reuse an existing local server for this lane. Its isolated `.live-sensitive-results/`
directory is ignored, is never uploaded, and the Node runner deletes it before and after every local
or CI live run while preserving the test process exit status. CI performs an additional defensive
deletion before uploading non-secret browser evidence.
