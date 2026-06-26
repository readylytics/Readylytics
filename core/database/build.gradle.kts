plugins {
    id("readylytics.android-library-conventions")
    id("readylytics.room-conventions")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.readylytics.health.core.database"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:scoring"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)

    // Room & SQLCipher
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
