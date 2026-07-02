import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

plugins {
    id("readylytics.compose-library-conventions")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(libs.findLibrary("hilt-android").get())
    ksp(libs.findLibrary("hilt-compiler").get())
    implementation(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    implementation(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    implementation(libs.findLibrary("androidx-hilt-navigation-compose").get())
    implementation(libs.findLibrary("kotlinx-coroutines-android").get())
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
    testImplementation(libs.findLibrary("mockk").get())
    testImplementation(libs.findLibrary("robolectric").get())
    testImplementation(libs.findLibrary("androidx-junit").get())
    testImplementation(libs.findLibrary("androidx-test-core").get())
    androidTestImplementation(platform(libs.findLibrary("androidx-compose-bom").get()))
    androidTestImplementation(libs.findLibrary("androidx-junit").get())
    androidTestImplementation(libs.findLibrary("androidx-compose-ui-test-junit4").get())
    androidTestImplementation(libs.findLibrary("mockk-android").get())
    debugImplementation(libs.findLibrary("androidx-compose-ui-test-manifest").get())
}
