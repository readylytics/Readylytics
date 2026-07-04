plugins {
    id("readylytics.compose-feature-conventions")
}

android {
    namespace = "app.readylytics.health.feature.vitals"
}

dependencies {
    implementation(project(":core:scoring"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
}
