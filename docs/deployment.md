# Deployment

## Aether Identity

Use only the [Aether Identity deployment guide](identity/deployment.md) for the passkey authority.
It defines the required RP/origin, proxy, cookie/CSRF, secret, cryptography, storage-isolation,
migration, audit, and release-gate controls. Generic framework deployment examples are not a safe
substitute.

## Other Aether applications

This repository no longer ships a copy-paste application image or orchestration manifest. The old
samples invoked nonexistent `shadowJar`/`installDist` tasks and configured legacy JWT, password
session, and raw-client-IP behavior, so they were removed for `0.6.0.0`. Package an unrelated
`aether-core` application with tasks and configuration owned by that application, and do not infer
identity-authority settings from a generic server deployment.
