rootProject.name = "docstack-headless"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// docstack-store is a sibling git submodule with its own Gradle root (own
// settings.gradle.kts) - a composite build, not a subproject. Its own build.gradle.kts
// sets no explicit `group`, so match by an explicit substitution instead of guessing
// whatever group Gradle would otherwise infer.
includeBuild("../docstack-store") {
    dependencySubstitution {
        substitute(module("ac.onyx.docstack:docstack-store")).using(project(":"))
    }
}

include(":engine")
