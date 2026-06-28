import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

plugins {
    id("readylytics.compose-library-conventions")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    add("implementation", project(":core:model"))
    add("implementation", project(":core:ui"))
    add("implementation", libs.findLibrary("hilt-android").get())
    add("ksp", libs.findLibrary("hilt-compiler").get())
    add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
    add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
    add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    add("testImplementation", libs.findLibrary("mockk").get())
    add("testImplementation", libs.findLibrary("robolectric").get())
    add("testImplementation", libs.findLibrary("androidx-junit").get())
    add("testImplementation", libs.findLibrary("androidx-test-core").get())
    add("androidTestImplementation", platform(libs.findLibrary("androidx-compose-bom").get()))
    add("androidTestImplementation", libs.findLibrary("androidx-junit").get())
    add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
    add("androidTestImplementation", libs.findLibrary("mockk-android").get())
    add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
}
