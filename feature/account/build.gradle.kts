plugins {
    alias(libs.plugins.oasis.android.feature)
}

android {
    namespace = "de.velwebsolutions.oasis.account"
}

// Sign-in, registration, settings, privacy, safety and the member pages (OasisAccount on iOS).
dependencies {
    implementation(libs.androidx.activity.compose)
    // The screens' Hilt view models under Robolectric (a test graph replaces :core's module).
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
