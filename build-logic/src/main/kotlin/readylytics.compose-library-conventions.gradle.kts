import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

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
