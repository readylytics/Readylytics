import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

plugins {
    id("readylytics.android-library-conventions")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    jacoco
}

extensions.configure<LibraryExtension> {
    buildFeatures.compose = true
    testOptions.unitTests.isIncludeAndroidResources = true
}

extensions.configure<ComposeCompilerGradlePluginExtension> {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_compiler_config.conf"),
    )
    // Metrics/reports are opt-in via -PenableComposeReports so normal builds pay zero extra I/O cost.
    if (project.hasProperty("enableComposeReports")) {
        metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    implementation(platform(libs.findLibrary("androidx-compose-bom").get()))
    implementation(libs.findLibrary("androidx-compose-ui").get())
    implementation(libs.findLibrary("androidx-compose-foundation").get())
    implementation(libs.findLibrary("androidx-compose-material3").get())
    implementation(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    testImplementation(libs.findLibrary("junit").get())
    debugImplementation(libs.findLibrary("androidx-compose-ui-tooling").get())
}
