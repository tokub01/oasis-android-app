plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.roborazzi) apply false
}

// Style and static analysis for every module: ktlint (formatting, `./gradlew ktlintCheck`) and detekt
// (`./gradlew detekt`, rules in config/detekt/detekt.yml).
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.get())
        android.set(true)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(
        files(
            subprojects.flatMap { listOf(it.file("src/main/kotlin"), it.file("src/main/java"), it.file("src/test/kotlin")) },
        ),
    )
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
}

// Coverage over all modules (`./gradlew koverHtmlReport koverVerify`). Excluded is only code the compilers generate:
// Hilt/Dagger components and factories, kotlinx.serialization serializers, Compose's lambda singletons, BuildConfig,
// and @Preview functions (design-time only, never shipped behaviour).
dependencies {
    subprojects.filter { it.path !in setOf(":core-testing", ":feature") }.forEach { kover(project(it.path)) }
}

kover {
    currentProject.createVariant("oasis") {}
    reports {
        filters {
            excludes {
                classes(
                    "*_HiltModules*", "*Hilt_*", "hilt_aggregated_deps.*", "dagger.hilt.*", "*_Factory", "*_Factory\$*",
                    "*_MembersInjector", "*_Provide*Factory", "*_GeneratedInjector", "*Module_*", "*_ComponentTreeDeps",
                    "*\$\$serializer", "*ComposableSingletons*", "*.BuildConfig", "*.R", "*.R\$*",
                )
                annotatedBy("androidx.compose.ui.tooling.preview.Preview", "dagger.Module")
            }
        }
    }
}
