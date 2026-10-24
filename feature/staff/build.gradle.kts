plugins {
    alias(libs.plugins.oasis.android.feature)
}

android {
    namespace = "de.velwebsolutions.oasis.staff"
}

// core-ktx for the FileProvider of the evidence archive.
dependencies {
    implementation(libs.androidx.core.ktx)
}
