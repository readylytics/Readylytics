import readylytics.buildlogic.DebugInstallIdentity

plugins {
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.ktlint)
}

val useConnectedProfileDevice =
    providers
        .gradleProperty("readylytics.baselineprofile.connected")
        .map(String::toBoolean)
        .orElse(false)

android {
    namespace = "app.readylytics.health.benchmark"
    compileSdk = 37
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "DEBUGGABLE"
        testInstrumentationRunnerArguments["readylytics.machineIdSegment"] = DebugInstallIdentity.machineIdSegment
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel9Api36") {
                    device = "Pixel 9"
                    apiLevel = 36
                    systemImageSource = "aosp"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    if (useConnectedProfileDevice.get()) {
        useConnectedDevices = true
    } else {
        managedDevices += "pixel9Api36"
        useConnectedDevices = false
    }
}

ktlint {
    version.set("1.5.0")
}

dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
