plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation("app.cash.zipline:zipline-cli:1.27.0")
}

application {
    mainClass.set("app.cash.zipline.cli.Main")
}
