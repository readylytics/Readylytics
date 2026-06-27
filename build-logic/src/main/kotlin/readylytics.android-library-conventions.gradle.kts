import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
    id("readylytics.kotlin-android-conventions")
}

extensions.configure<LibraryExtension> {
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
