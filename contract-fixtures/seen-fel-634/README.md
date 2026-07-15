# Seen FEL-634 identity contract fixtures

These fixtures define the browser-safe and authorization-safe portion of the unreleased Aether
Identity `0.6.0.0` target contract that Seen may consume during integration. Publication remains
blocked by Aether's release gates. They do not expose persistence models. In particular, they contain no
session selector/secret, credential public key, token digest, recovery code, raw IP address, or
provider assertion.

The `user-publisher-context.json`, `device-publisher-context.json`, and
`service-publisher-context.json` files demonstrate that all authority is bound to the explicit
organization in the request. `package.publish` is an application-owned capability resolved by Seen;
it is not a global Aether role or permission. The user fixture records the organization role for
display and policy input. Device and service principals have direct, allowlisted scopes and no
membership role.

`not-found-error.json` is the response for both a nonexistent tenant resource and an unauthorized
tenant resource. Consumers must not distinguish the two cases.

Wire rules are camelCase fields, lowercase snake-case enum values, RFC 3339 UTC timestamps,
canonical UUID strings, and unpadded base64url binary values. Additive fields may be introduced in a
future minor fixture version; removing or changing an existing field requires a new major identity
contract version.

RFC 8628 token errors retain the required top-level `error` code and also carry only Aether's fixed
generic `message`, correlation `requestId`, and `retryable` flag. Polling cadence comes from the
device-authorization response; token errors never echo device codes or internal state.

Authentication and passkey step-up use distinct start/finish routes. The finish body therefore
contains only `ceremonyId`, the browser credential envelope, and optional device metadata; it does
not carry a caller-selected authentication purpose.
