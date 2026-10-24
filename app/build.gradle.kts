import java.util.Properties

plugins {
    alias(libs.plugins.oasis.android.application)
}

// Per-machine values that are never committed (secrets.properties, see secrets.properties.example) or CI secrets.
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun secret(name: String): String = System.getenv(name) ?: secrets.getProperty(name, "")

android {
    namespace = "de.velwebsolutions.oasis.app"

    defaultConfig {
        applicationId = "de.velwebsolutions.oasis"
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "OASIS_GATE_KEY", "\"\"")
        buildConfigField("String", "REVERB_HOST_OVERRIDE", "\"\"")
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
        release {
            buildConfigField("String", "OASIS_BASE_URL", "\"https://get-oasis.com\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
