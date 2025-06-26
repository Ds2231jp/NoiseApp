pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Correct Kotlin DSL syntax for adding a repository
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "NoiseApp"
include(":app")
