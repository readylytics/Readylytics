plugins { id("readylytics.compose-feature-conventions") }

android {
    namespace = "app.readylytics.health.feature.sleep"
}

dependencies {
    implementation(project(":core:scoring"))
    implementation(project(":core:designsystem"))
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
}
