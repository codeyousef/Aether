# Manual hardware-passkey smoke test

Run this checklist against the release candidate after automated virtual-authenticator tests pass.
Use a disposable test identity and a hardware authenticator that is not needed for any production
account. Record browser version, operating system, authenticator model, result, and tester/date in
the release evidence. Do not record credential IDs, assertions, recovery codes, cookies, or tokens.

## Firefox

1. Open the HTTPS identity URL in a current Firefox release with a clean profile.
2. Register the hardware passkey. Confirm the browser shows the expected RP name and requires user
   verification; cancel once and verify Aether reports a generic error without leaking details.
3. Complete registration, sign out, and perform username-free sign-in with the same key.
4. Trigger a sensitive action and complete passkey step-up. Confirm the action is rejected before
   step-up and accepted after it.
5. Generate recovery codes, save one temporarily, sign out, recover with that code, and enroll a new
   passkey. Confirm the recovery session cannot access normal account or organization actions.
6. Verify the used code cannot be reused and that the previous session is revoked.

## Safari

1. Repeat the Firefox checklist in a current Safari release on supported Apple hardware.
2. Confirm the registration sheet names the expected RP and origin, user verification is required,
   and no password or bearer-token fallback is offered.
3. Verify keyboard focus, VoiceOver labels, cancel/error handling, username-free sign-in, step-up,
   recovery restriction, replacement passkey enrollment, and single-use code behavior.

Firefox and Safari are the required manual hardware-passkey release smoke targets. Chromium's
hardware behavior is not a substitute for these checks; Chromium is already covered automatically
with a CDP virtual authenticator.
