rootProject.name = "aether"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(
    ":aether-core",
    ":aether-signals",
    ":aether-db",
    ":aether-tasks",
    ":aether-channels",
    ":aether-web",
    ":aether-ui",
    ":aether-net",
    ":aether-plugin",
    ":aether-cli",
    ":aether-ksp",
    ":aether-auth",
    ":aether-auth-testkit",
    ":aether-auth-postgresql",
    ":aether-auth-firestore",
    ":aether-auth-summon",
    ":aether-auth-oidc",
    ":aether-auth-saml",
    ":aether-auth-scim",
    ":aether-forms",
    ":aether-admin",
    ":aether-grpc",
    ":example-app"
)
