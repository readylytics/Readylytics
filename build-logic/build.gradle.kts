plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:9.2.1")
    compileOnly("org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:2.3.21")
    compileOnly("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.7")
    compileOnly("androidx.room:room-gradle-plugin:2.8.4")
}
