rootProject.name = "aether"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(
    ":aether-core",
    ":aether-db",
    ":aether-web",
    ":aether-ui",
    ":aether-net",
    ":aether-plugin",
    ":aether-cli",
    ":aether-ksp",
    ":aether-auth",
    ":aether-forms",
    ":aether-admin",
    ":example-app"
)
