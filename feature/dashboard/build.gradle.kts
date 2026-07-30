plugins { id("readylytics.compose-feature-conventions") }

android {
    namespace = "app.readylytics.health.feature.dashboard"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:scoring"))
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
