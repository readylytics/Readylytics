plugins {
    id("readylytics.compose-feature-conventions")
    id("kotlin-parcelize")
}

android {
    namespace = "app.readylytics.health.feature.settings"
}

dependencies {
    implementation(project(":core:scoring"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.material.color.utilities)
    implementation(libs.play.services.oss.licenses)
}
