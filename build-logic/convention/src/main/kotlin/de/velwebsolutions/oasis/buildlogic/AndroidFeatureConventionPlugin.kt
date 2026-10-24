package de.velwebsolutions.oasis.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * A feature module (network, chat, account, live, membership, staff) like one OasisKit target: Compose with the Oasis
 * design, Hilt view models, and the core API client. Features never depend on each other; they meet through the routes
 * in `:design`, as on iOS.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("oasis.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        pluginManager.apply("io.github.takahirom.roborazzi")
        extensions.configure<LibraryExtension> {
            buildFeatures.compose = true
        }
        dependencies {
            add("implementation", project(":core"))
            add("implementation", project(":design"))
            val bom = platform(libs.library("compose-bom"))
            add("implementation", bom)
            add("androidTestImplementation", bom)
            add("implementation", libs.library("compose-ui"))
            add("implementation", libs.library("compose-foundation"))
            add("implementation", libs.library("compose-material3"))
            add("implementation", libs.library("compose-material-icons-extended"))
            add("implementation", libs.library("compose-ui-tooling-preview"))
            add("debugImplementation", libs.library("compose-ui-tooling"))
            add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("hilt-android"))
            add("ksp", libs.library("hilt-compiler"))
            add("testImplementation", project(":core-testing"))
            add("testImplementation", libs.library("robolectric"))
            add("testImplementation", libs.library("roborazzi"))
            add("testImplementation", libs.library("roborazzi-compose"))
            add("testImplementation", libs.library("roborazzi-junit-rule"))
            add("testImplementation", libs.library("compose-ui-test-junit4"))
            add("testImplementation", bom)
            add("debugImplementation", libs.library("compose-ui-test-manifest"))
            add("androidTestImplementation", libs.library("compose-ui-test-junit4"))
            add("androidTestImplementation", libs.library("androidx-test-ext-junit"))
            add("androidTestImplementation", libs.library("androidx-test-runner"))
        }
    }
}
