// Boot spike continuation (spec 04 task 2): does a REAL Kotlin/JS-compiled Zipline
// module, sharing the same QuickJS globalThis as our hand-esbuilt pouchdb bundle,
// actually install a working setTimeout/console/event-loop the way GlobalBridge.kt
// (zipline's own jsMain source) does for every genuine Zipline app? Not production
// code - same throwaway status as the rest of spike/.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("app.cash.zipline")
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("app.cash.zipline:zipline:1.27.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
    }
}

zipline {
    mainFunction.set("app.cash.zipline.docstack.spike.bootstrap.launchZipline")
}
