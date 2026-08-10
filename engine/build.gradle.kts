// Real (non-spike) headless carrier: HeadlessCarrier's host side wired to the real
// StorageDispatcher, PouchDbFacade's guest side against the real
// @docstack/pouchdb-adapter-native bundle (js-bundle/). See android/specs/04-docstack-headless.md
// "Kotlin API surface" for the design; docstack-headless/SPIKE-NOTES.md for the
// setTimeout/event-loop mechanism this builds on.
//
// AGP 9's `com.android.library` plugin is not compatible with kotlin("multiplatform")
// anymore - `com.android.kotlin.multiplatform.library` replaces it, with its own
// `kotlin { android { ... } }` DSL (not a separate top-level `android {}` block) and
// renamed source sets (`androidHostTest` instead of `androidUnitTest`/`src/test`).
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.zipline")
}

kotlin {
    android {
        namespace = "ac.onyx.docstack.headless"
        // Matches docstack-store's own override (io.maryk.rocksdb:rocksdb-android's
        // AAR metadata requires 36+); kept aligned even though this module has no
        // RocksDB dependency itself, since it depends on docstack-store.
        compileSdk = 36
        minSdk = 24

        withHostTestBuilder {}.configure {}
    }

    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation("app.cash.zipline:zipline:1.27.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation("ac.onyx.docstack:docstack-store")
                implementation("app.cash.zipline:zipline-loader:1.27.0")
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("junit:junit:4.13.2")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        }
    }
}

zipline {
    mainFunction.set("ac.onyx.docstack.headless.launchZipline")
}
