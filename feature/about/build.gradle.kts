plugins {
    id("readylytics.compose-feature-conventions")
}

android {
    namespace = "app.readylytics.health.feature.about"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.play.services.oss.licenses)
}
