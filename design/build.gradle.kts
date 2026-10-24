plugins {
    alias(libs.plugins.oasis.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.velwebsolutions.oasis.design"
    buildFeatures.compose = true
}

// Colours, fonts, shared components and the navigation routes of all areas (OasisDesign on iOS).
dependencies {
    implementation(project(":core"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.compose.ui.tooling.preview)
    api(libs.androidx.navigation3.runtime)
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.browser)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
