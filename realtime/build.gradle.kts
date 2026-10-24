plugins {
    alias(libs.plugins.oasis.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "de.velwebsolutions.oasis.realtime"
}

// Reverb (Pusher protocol) client implementing `:core`'s RealtimeService (OasisRealtime on iOS).
dependencies {
    implementation(project(":core"))
    implementation(libs.hilt.android)
    implementation(libs.androidx.lifecycle.process)
    ksp(libs.hilt.compiler)
    testImplementation(project(":core-testing"))
    testImplementation(libs.okhttp.mockwebserver)
}
