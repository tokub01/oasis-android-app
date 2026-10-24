plugins {
    alias(libs.plugins.oasis.android.feature)
}

android {
    namespace = "de.velwebsolutions.oasis.network"
}

// Tropfen, Oasen, profiles, discovery and search (OasisNetwork on iOS). The photo picker and the camera need the
// activity result APIs; the camera shares its file through androidx.core's FileProvider.
dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
