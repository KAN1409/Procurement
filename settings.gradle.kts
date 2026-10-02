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
    }
}

rootProject.name = "procurement"

// :app is the shipped Procurement Library application.
include(":app")

// :legacy keeps the previous host application (speech dictation + document RAG)
// compiling and testable without contributing anything to the shipped APK.
include(":legacy")
