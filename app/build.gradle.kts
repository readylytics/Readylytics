plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.protobuf)
    id("kotlin-parcelize")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

android {
    namespace = "com.gregor.lauritz.healthdashboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gregor.lauritz.healthdashboard"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        testOptions {
            unitTests.isReturnDefaultValues = true
            unitTests.isIncludeAndroidResources = true
        }

        buildConfigField(
            "String",
            "OAUTH_WEB_CLIENT_ID",
            "\"${project.findProperty("OAUTH_WEB_CLIENT_ID") ?: "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        xmlReport = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ktlint {
    version.set("1.5.0")
}

protobuf {
    protoc {
        artifact =
            libs.protobuf.protoc
                .get()
                .toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.material)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // DataStore
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.kotlin.lite)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Health Connect
    implementation(libs.androidx.health.connect.client)

    // Lifecycle extras
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Serialization (type-safe nav destinations)
    implementation(libs.kotlinx.serialization.json)

    // Vico charts
    implementation(libs.vico.compose.m3)

    // Google Drive backup
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)

    // WorkManager + Hilt integration
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Security
    implementation(libs.google.tink.android)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
