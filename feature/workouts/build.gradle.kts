plugins {
    id("readylytics.compose-feature-conventions")
}

android {
    namespace = "app.readylytics.health.feature.workouts"
}

dependencies {
    implementation(project(":core:scoring"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
