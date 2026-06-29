plugins { id("readylytics.compose-feature-conventions") }
android { namespace = "app.readylytics.health.feature.onboarding" }
dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.health.connect.client)
    testImplementation(kotlin("test"))
}
