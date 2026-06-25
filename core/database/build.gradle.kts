plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.readylytics.health.core.database"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:scoring"))
    
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.serialization.json)
    
    // Room & SQLCipher
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
}
