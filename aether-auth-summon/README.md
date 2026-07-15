# Aether Auth Summon

`aether-auth-summon` is the optional passkey-first identity UI. It provides small, data-driven
Summon components in `commonMain`, a JVM SSR renderer, and a wasmJs hydration/browser credential
adapter.

The module intentionally has no wasmWasi target. A wasmWasi identity authority exposes the same
`/identity/v1` JSON APIs from `aether-auth`; it does not embed browser components or
`navigator.credentials`.

The common component set also includes organization selection, membership and invitation
management, service-identity and public credential-prefix management, and RFC 8628 device
approval. Signed-in users enter the short human code into a bounded POST-backed form; the code is
never sourced from a URL. Device approval always requires a separate explicit organization selection and at
least one requested capability; it never inherits the organization selected in the management
panel.
Each offered device organization carries its own intersection of requested and approvable
capabilities. Changing the organization clears the scope selection, and scopes not granted for
that organization cannot be selected.

## Security boundary

- Browser code receives only public WebAuthn creation/request options and returns the standard
  browser credential envelope plus its opaque ceremony ID through the host gateway.
- `IdentityRuntime`, identity stores, authority keys, secret references, token digests, session
  cookies, and administrative enrollment-ticket secrets are not accepted by browser-facing APIs.
- Recovery codes are intentional one-time user-facing secrets. The UI displays exactly ten in a
  focused live region and removes them when `DismissRecoveryCodes` is handled. The recovery-code
  state redacts its string representation.
- JVM SSR does not use Summon's generic hydration-state serializer. The host supplies the same
  browser-safe UI state when hydrating.
- Organization resource models and mutation actions carry an explicit organization ID. The UI
  state rejects memberships, invitations, or service identities that do not belong to the
  selected organization, so hosts do not need a server-side "selected organization" session.
- Device approval state contains the human verification code, organization choices, and requested
  public capability names only. It does not contain the device code, access/refresh tokens, token
  digests, or the server-side grant object; diagnostics redact the human code as well. Manual entry
  normalizes the eight-character code locally and resolves it through JSON `POST /identity/v1/device`.
- Full-width roots, panels, flex children, lists, and controls use border-box sizing with a zero
  minimum width. Buttons reserve Summon's inline margin, preventing the identity surface from
  exceeding a 390px phone viewport.

Applications own routing and JSON transport. Implement `PasskeyCeremonyGateway`, inject a
`PasskeyBrowserClient` (use `NavigatorCredentialsPasskeyClient` on wasmJs), and dispatch the
`IdentityUiAction` values to their state holder.
