plugins {
    alias(libs.plugins.oasis.android.feature)
}

android {
    namespace = "de.velwebsolutions.oasis.live"
}

// Hinweise, their settings, the live counters of the app shell and push (OasisLive on iOS). Firebase Cloud Messaging is
// only prepared: without google-services.json in the app nothing is set up, and nothing is registered with the server
// until Laravel takes FCM tokens (PushDeviceController accepts APNs tokens only).
dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.firebase.messaging)
}
