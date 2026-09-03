plugins {
    id("readylytics.compose-feature-conventions")
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    namespace = "app.readylytics.health.feature.settings"
}

dependencies {
    implementation(project(":core:scoring"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.material.color.utilities)
    implementation(libs.play.services.oss.licenses)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
