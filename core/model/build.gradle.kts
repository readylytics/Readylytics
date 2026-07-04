plugins {
    id("readylytics.android-library-conventions")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.readylytics.health.core.model"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
