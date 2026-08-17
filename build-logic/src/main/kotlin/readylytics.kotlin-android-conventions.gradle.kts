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
    baseline = rootProject.layout.projectDirectory.file("config/detekt/baseline.xml").asFile
}
