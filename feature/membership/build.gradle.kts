plugins {
    alias(libs.plugins.oasis.android.feature)
}

android {
    namespace = "de.velwebsolutions.oasis.membership"
}

// Google Play Billing for the in-app membership (PlayBillingGateway); core-ktx for the FileProvider of invoices.
dependencies {
    implementation(libs.billing.ktx)
    implementation(libs.androidx.core.ktx)
}
