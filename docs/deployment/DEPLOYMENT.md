# Legacy deployment samples removed

The Docker, Compose, Nginx, SQL, and Kubernetes samples previously stored in this directory were
removed because they invoked nonexistent example distribution tasks and configured legacy JWT,
password-session, and raw-IP behavior. They are not compatible with the Aether Identity `0.6.0.0`
release.

For the passkey authority, follow only the [Aether Identity deployment
guide](../identity/deployment.md). For an unrelated `aether-core` application, own the application
image, configuration, secret model, observability policy, and orchestration manifests in that
application repository.
