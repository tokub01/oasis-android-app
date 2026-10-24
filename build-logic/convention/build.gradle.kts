plugins {
    `kotlin-dsl`
}

group = "de.velwebsolutions.oasis.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.kover.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "oasis.android.application"
            implementationClass = "de.velwebsolutions.oasis.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "oasis.android.library"
            implementationClass = "de.velwebsolutions.oasis.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "oasis.android.feature"
            implementationClass = "de.velwebsolutions.oasis.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "oasis.jvm.library"
            implementationClass = "de.velwebsolutions.oasis.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
