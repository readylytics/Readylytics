plugins {
    id("readylytics.compose-library-conventions")
}

android {
    namespace = "app.readylytics.health.core.designsystem"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.material)
    implementation(libs.material.color.utilities)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
