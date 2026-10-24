plugins {
    alias(libs.plugins.oasis.jvm.library)
    `java-test-fixtures`
}

// End-to-end chat crypto, byte-compatible with resources/js/chat in the Laravel project and OasisChatCrypto on iOS.
// The libsodium binding is passed in ([Sodium]); the tests use lazysodium-java on the JVM (test fixture `JvmSodium`,
// also used by the unit tests of :feature:chat), the app lazysodium-android.
dependencies {
    testFixturesApi(libs.lazysodium.java)
    testFixturesApi(libs.jna)
}

// lazysodium-java 5.2.0 ships Java 21 bytecode. Only the tests need it, and they run on the JDK 21 of the build, so the
// test classpaths accept Java 21 libraries while the module itself stays at Java 17.
listOf("testFixturesCompileClasspath", "testFixturesRuntimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
