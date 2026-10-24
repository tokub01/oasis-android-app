plugins {
    alias(libs.plugins.oasis.jvm.library)
}

// End-to-end chat crypto, byte-compatible with resources/js/chat in the Laravel project and OasisChatCrypto on iOS.
// The libsodium binding is passed in ([Sodium]); the tests use lazysodium-java on the JVM, the app lazysodium-android.
dependencies {
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
}
