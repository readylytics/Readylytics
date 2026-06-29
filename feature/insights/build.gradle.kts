plugins {
    id("readylytics.compose-feature-conventions")
}

android {
    namespace = "app.readylytics.health.feature.insights"
}

dependencies {
    implementation(project(":core:scoring"))
}
