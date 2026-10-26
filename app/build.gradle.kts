import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.oasis.android.application)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.play.publisher)
}

// Push through Firebase Cloud Messaging (project oasis-86acd) when app/google-services.json is there – locally copied,
// in CI written from the GOOGLE_SERVICES_JSON_B64 secret, never committed. Without it the app builds without push.
// The file names only de.velwebsolutions.oasis, so the ".staging" variant goes without Firebase.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    tasks.matching { it.name.startsWith("process") && it.name.contains("Staging") && it.name.endsWith("GoogleServices") }
        .configureEach { enabled = false }
}

// Per-machine values that are never committed (secrets.properties, see secrets.properties.example) or CI secrets.
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun secret(name: String): String = System.getenv(name) ?: secrets.getProperty(name, "")

// The upload key for Google Play: ~/.android-keys/oasis-upload.jks with its passwords in
// ~/.android-keys/oasis-upload.properties (both outside the repository), or the ANDROID_UPLOAD_* variables in CI.
val uploadKey = Properties().apply {
    val file = File(System.getProperty("user.home"), ".android-keys/oasis-upload.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun upload(name: String): String = System.getenv("ANDROID_UPLOAD_$name") ?: uploadKey.getProperty(name.lowercase(), "")

/**
 * The version code is the commit time in UTC as yyDDDHHmm – year, day of the year, hour, minute (e.g. 262980815 for
 * 25 October 2026, 08:15) – like the TestFlight build number: every new develop commit gets a higher one. yyMMddHHmm
 * would not fit (2610250815 is above Int.MAX_VALUE and Play's limit of 2100000000); this stays below 993662359 until
 * 2099. VERSION_CODE overrides it.
 */
val commitVersionCode: Int = System.getenv("VERSION_CODE")?.toInt() ?: run {
    val seconds = providers.exec { commandLine("git", "log", "-1", "--format=%ct") }.standardOutput.asText.get().trim()
    DateTimeFormatter.ofPattern("yyDDDHHmm").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(seconds.toLong())).toInt()
}

android {
    namespace = "de.velwebsolutions.oasis.app"

    defaultConfig {
        applicationId = "de.velwebsolutions.oasis"
        versionCode = commitVersionCode
        versionName = "1.0"
        buildConfigField("String", "OASIS_GATE_KEY", "\"\"")
        buildConfigField("String", "REVERB_HOST_OVERRIDE", "\"\"")
    }

    signingConfigs {
        create("upload") {
            val store = upload("KEYSTORE_PATH").ifEmpty { "${System.getProperty("user.home")}/.android-keys/oasis-upload.jks" }
            storeFile = file(store)
            storePassword = upload("KEYSTORE_PASSWORD")
            keyAlias = upload("KEY_ALIAS").ifEmpty { "oasis-upload" }
            keyPassword = upload("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // The local Sail backend on the Mac; the emulator reaches the Mac's localhost as 10.0.2.2.
            buildConfigField("String", "OASIS_BASE_URL", "\"${secret("OASIS_DEBUG_BASE_URL").ifEmpty { "http://10.0.2.2" }}\"")
            buildConfigField("String", "REVERB_HOST_OVERRIDE", "\"${secret("OASIS_DEBUG_REVERB_HOST").ifEmpty { "10.0.2.2" }}\"")
        }
        create("staging") {
            // staging.get-oasis.com (deployed from Laravel's develop branch) behind the staging gate (`X-Oasis-Gate`).
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("String", "OASIS_BASE_URL", "\"https://staging.get-oasis.com\"")
            buildConfigField("String", "OASIS_GATE_KEY", "\"${secret("OASIS_GATE_KEY")}\"")
        }
        create("play") {
            // The Google Play test build: the real package name, but staging.get-oasis.com behind the staging gate –
            // the same as the TestFlight build of iOS. Signed with the upload key; Google re-signs it for devices.
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("String", "OASIS_BASE_URL", "\"https://staging.get-oasis.com\"")
            buildConfigField("String", "OASIS_GATE_KEY", "\"${secret("OASIS_GATE_KEY")}\"")
            signingConfig = signingConfigs.getByName("upload").takeIf { upload("KEYSTORE_PASSWORD").isNotEmpty() }
        }
        release {
            buildConfigField("String", "OASIS_BASE_URL", "\"https://get-oasis.com\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Only the Play variant is published.
    playConfigs {
        register("play") {
            enabled.set(true)
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(project(":realtime"))
    implementation(project(":feature:network"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:account"))
    implementation(project(":feature:live"))
    implementation(project(":feature:membership"))
    implementation(project(":feature:staff"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(project(":core-testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Upload to Google Play's internal test track (`./gradlew publishPlayBundle`, .github/workflows/play-internal.yml).
// The store listing lives in src/main/play; the service account key comes from PLAY_SERVICE_ACCOUNT_JSON (a file path).
play {
    enabled.set(false)
    track.set("internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(ReleaseStatus.COMPLETED)
    System.getenv("PLAY_SERVICE_ACCOUNT_JSON")?.let { serviceAccountCredentials.set(file(it)) }
}
