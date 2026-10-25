plugins {
    alias(libs.plugins.oasis.android.feature)
}

android {
    namespace = "de.velwebsolutions.oasis.chat"
}

dependencies {
    implementation(project(":chat-crypto"))
    // libsodium for the crypto core; JNA as AAR, because only the AAR carries the Android native dispatch libraries.
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(variantOf(libs.jna) { artifactType("aar") })
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)

    // The unit tests run the real crypto on the JVM (lazysodium-java through the chat-crypto test fixture).
    testImplementation(testFixtures(project(":chat-crypto")))
    testImplementation(libs.jna)
    // The screens' entry points with their real Hilt view models, on a test graph (Robolectric).
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}

// On the JVM the Android binding is replaced by lazysodium-java, which ships Java 21 bytecode (the tests run on the
// JDK 21 of the build).
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath") || name.endsWith("UnitTestCompileClasspath")) {
        exclude(group = "com.goterl", module = "lazysodium-android")
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
