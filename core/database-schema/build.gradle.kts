plugins {
    id("readylytics.android-library-conventions")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.readylytics.health.core.databaseschema"
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.kotlinx.serialization.json)
}
