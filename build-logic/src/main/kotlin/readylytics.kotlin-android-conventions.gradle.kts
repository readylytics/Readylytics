import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("io.gitlab.arturbosch.detekt")
}

pluginManager.withPlugin("com.android.library") {
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(17)
        compilerOptions {
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }
}

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    // Per-module baseline. A single shared root baseline cannot work: `detektBaseline` is a
    // per-project task, every module writes the same path, and the last writer wins — so the
    // file only ever holds one module's findings and `./gradlew detektBaseline` cannot
    // reproduce it. Config stays shared; only the baseline is split.
    baseline = layout.projectDirectory.file("detekt-baseline.xml").asFile
}
