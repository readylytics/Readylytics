plugins { id("readylytics.compose-feature-conventions") }

android {
    namespace = "app.readylytics.health.feature.dashboard"
}

dependencies {
    implementation(project(":core:scoring"))
    implementation(libs.androidx.compose.material.icons.extended)
    androidTestImplementation(project(":feature:insights"))
}
