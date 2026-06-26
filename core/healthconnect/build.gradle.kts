plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("jacoco")
}

android {
    namespace = "app.readylytics.health.core.healthconnect"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }
}

val fileFilter =
    listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/*_Factory*",
        "**/*_Impl*",
        "**/di/**",
    )

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("testDebugUnitTest")

    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
    classDirectories.setFrom(
        fileTree("${project.buildDir}/tmp/kotlin-classes/debug") {
            fileFilter.forEach { exclude(it) }
        },
    )
    sourceDirectories.setFrom(
        files(
            "${project.projectDir}/src/main/java",
            "${project.projectDir}/src/main/kotlin",
        ),
    )

    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("app.readylytics.health.domain.sync")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = 0.70.toBigDecimal()
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:scoring"))
    implementation(project(":core:database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.health.connect.client)
}
