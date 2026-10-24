package de.velwebsolutions.oasis.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(name: String): Int = findVersion(name).get().requiredVersion.toInt()

internal fun VersionCatalog.library(name: String) = findLibrary(name).get()

/** SDK levels, Java 17 bytecode and the compiler flags every Android module of Oasis shares. */
internal fun Project.configureAndroid(extension: CommonExtension) {
    extension.apply {
        compileSdk = libs.version("compileSdk")
        defaultConfig.minSdk = libs.version("minSdk")
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.isReturnDefaultValues = true
        lint.abortOnError = true
        lint.warningsAsErrors = true
        lint.checkDependencies = false
        lint.disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    configureKotlin()
    // Robolectric (Compose UI tests on the JVM) reads FileDescriptor internals, closed by default since JDK 17.
    tasks.withType<Test>().configureEach {
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED")
        maxHeapSize = "2g"
    }
}

internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                // Interface defaults as real JVM defaults, without DefaultImpls bridges nobody calls.
                "-jvm-default=no-compatibility",
            )
        }
    }
}

/**
 * Coverage of one module under the shared variant "oasis": the debug unit tests of Android modules, the tests of JVM
 * modules. The root project merges the variant of all modules (`koverHtmlReportOasis`, `koverVerifyOasis`).
 */
internal fun Project.configureKover(sourceVariant: String) {
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension>("kover") {
        currentProject.createVariant("oasis") { add(sourceVariant) }
        reports.filters.excludes {
            classes(GeneratedClasses)
            annotatedBy(*GeneratedAnnotations)
        }
    }
}

/**
 * Code the compilers and annotation processors generate, excluded from coverage: Hilt/Dagger components, factories and
 * aggregation stubs, kotlinx.serialization serializers, Compose's lambda singletons, BuildConfig and R.
 */
internal val GeneratedClasses = listOf(
    "*_HiltModules*", "*Hilt_*", "hilt_aggregated_deps.*", "dagger.hilt.*", "_*", "*_*Factory", "*_*Factory\$*",
    "*_MembersInjector", "*_GeneratedInjector", "*_ComponentTreeDeps", "*\$\$serializer", "*ComposableSingletons*",
    "*.BuildConfig", "*.R", "*.R\$*",
)

/** Hilt modules only wire objects together; @Preview functions are design-time only. */
internal val GeneratedAnnotations = arrayOf("dagger.Module", "androidx.compose.ui.tooling.preview.Preview")
