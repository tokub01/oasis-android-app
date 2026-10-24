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
