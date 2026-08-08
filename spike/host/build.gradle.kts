plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation("app.cash.zipline:zipline:1.27.0")
    implementation("app.cash.zipline:zipline-loader:1.27.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

application {
    mainClass.set("app.cash.zipline.docstack.spike.MainKt")
}
