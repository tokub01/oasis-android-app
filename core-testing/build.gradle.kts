plugins {
    alias(libs.plugins.oasis.android.library)
}

android {
    namespace = "de.velwebsolutions.oasis.testing"
}

// Test doubles shared by the unit tests of every module (like OasisCore's StubTransport on iOS).
dependencies {
    api(project(":core"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    api(libs.okhttp)
}

// Test helpers only: they run inside the tests of every other module and never ship, so they have no coverage bound.
tasks.matching { it.name.startsWith("koverVerify") }.configureEach { enabled = false }
