import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

val releaseSigningEnvironmentVariables =
    listOf(
        "READYLYTICS_UPLOAD_STORE_FILE",
        "READYLYTICS_UPLOAD_STORE_PASSWORD",
        "READYLYTICS_UPLOAD_KEY_ALIAS",
        "READYLYTICS_UPLOAD_KEY_PASSWORD",
        "READYLYTICS_UPLOAD_CERT_SHA256",
    )

val releaseSigningEnvironmentValues =
    releaseSigningEnvironmentVariables.associateWith {
        providers.environmentVariable(it).orNull?.trim().orEmpty()
    }

val releaseUploadSigningReady =
    releaseSigningEnvironmentVariables
        .dropLast(1)
        .all { releaseSigningEnvironmentValues.getValue(it).isNotBlank() }

abstract class VerifyReleaseSigningInputsTask : DefaultTask() {
    @get:Input
    abstract val signingInputs: MapProperty<String, String>

    @TaskAction
    fun verify() {
        val missingVariables =
            signingInputs
                .get()
                .filterValues(String::isBlank)
                .keys
                .sorted()
        if (missingVariables.isNotEmpty()) {
            throw GradleException(
                "Missing required release signing environment variables: ${missingVariables.joinToString(", ")}",
            )
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.google.oss.licenses)
    id("kotlin-parcelize")
    id("jacoco")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

android {
    namespace = "app.readylytics.health"
    compileSdk = 37

    signingConfigs {
        create("releaseUpload") {
            storeFile = providers.environmentVariable("READYLYTICS_UPLOAD_STORE_FILE").map(::file).orNull
            storePassword = providers.environmentVariable("READYLYTICS_UPLOAD_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("READYLYTICS_UPLOAD_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("READYLYTICS_UPLOAD_KEY_PASSWORD").orNull
        }
    }

    defaultConfig {
        applicationId = "app.readylytics.health"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        testOptions {
            unitTests.isReturnDefaultValues = true
            unitTests.isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseUploadSigningReady) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            enableUnitTestCoverage = true
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
        warningsAsErrors = true
        xmlReport = true
        baseline = file("lint-baseline.xml")
    }
}

val verifyReleaseSigningInputs =
    tasks.register<VerifyReleaseSigningInputsTask>("verifyReleaseSigningInputs") {
        group = "verification"
        description = "Fails release artifact tasks when required signing inputs are missing."
        signingInputs.putAll(releaseSigningEnvironmentValues)
    }

listOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "signReleaseBundle",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(verifyReleaseSigningInputs)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ktlint {
    version.set("1.5.0")
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
    }

    val fileFilter =
        listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            "**/hilt_aggregated_deps/**",
            "**/*_HiltModules*",
            "**/*_MembersInjector*",
            "**/*Hilt_*",
            "**/*_Factory*",
            "**/Dagger*Component*.*",
            "**/*ComposableSingletons*",
            "**/*_Impl*",
            "**/databinding/**",
            "**/di/**",
            "**/*Proto*.*",
            "**/*Serializer*.*",
        )

    // Search broadly across all known AGP output locations for compiled Kotlin/Java class files
    val debugTree =
        fileTree(layout.buildDirectory.get()) {
            include(
                "tmp/kotlin-classes/debug/**/*.class",
                "intermediates/classes/debug/**/*.class",
                "intermediates/kotlinc/debug/**/*.class",
                "intermediates/javac/debug/**/*.class",
            )
            fileFilter.forEach { exclude(it) }
        }

    doFirst {
        val count = debugTree.files.size
        println("jacocoTestReport: classDirectories has $count class file(s)")
        if (count == 0) {
            val buildDir =
                project.layout.buildDirectory
                    .get()
                    .asFile
            buildDir
                .walkTopDown()
                .filter { it.extension == "class" }
                .take(20)
                .forEach { println("  class: ${it.relativeTo(buildDir)}") }
        }
    }

    val mainSrc =
        files(
            "${project.projectDir}/src/main/java",
            "${project.projectDir}/src/main/kotlin",
        )

    sourceDirectories.setFrom(mainSrc)
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("jacocoTestReport")

    val reportXmlFile = layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml")

    val fileFilter =
        listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            "**/hilt_aggregated_deps/**",
            "**/*_HiltModules*",
            "**/*_MembersInjector*",
            "**/*Hilt_*",
            "**/*_Factory*",
            "**/Dagger*Component*.*",
            "**/*ComposableSingletons*",
            "**/*_Impl*",
            "**/databinding/**",
            "**/di/**",
            "**/*Proto*.*",
            "**/*Serializer*.*",
        )

    val debugTree =
        fileTree(layout.buildDirectory.get()) {
            include(
                "tmp/kotlin-classes/debug/**/*.class",
                "intermediates/classes/debug/**/*.class",
                "intermediates/kotlinc/debug/**/*.class",
                "intermediates/javac/debug/**/*.class",
            )
            fileFilter.forEach { exclude(it) }
        }

    val mainSrc =
        files(
            "${project.projectDir}/src/main/java",
            "${project.projectDir}/src/main/kotlin",
        )

    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
    classDirectories.setFrom(files(debugTree))
    sourceDirectories.setFrom(mainSrc)

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = 0.25.toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("app.readylytics.health.domain.scoring")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = 0.80.toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("app.readylytics.health.domain.sync")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = 0.70.toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("app.readylytics.health.workers")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = 0.60.toBigDecimal()
            }
        }
    }

    doFirst {
        val reportFile = reportXmlFile.get().asFile
        if (!reportFile.exists()) {
            throw GradleException("Coverage report XML not found at ${reportFile.absolutePath}")
        }
        val xml = reportFile.readText()
        val requiredClasses =
            listOf(
                "app/readylytics/health/data/repository/ScoringRepositoryImpl",
                "app/readylytics/health/domain/sync/HealthSyncUseCase",
                "app/readylytics/health/workers/DataCleanupWorker",
                "app/readylytics/health/ui/heartrate/HeartRateDetailViewModel",
                "app/readylytics/health/ui/sleep/SleepViewModel",
                "app/readylytics/health/ui/steps/StepDetailViewModel",
            )
        for (cls in requiredClasses) {
            if (!xml.contains("name=\"$cls\"")) {
                throw GradleException("Verification failed: Coverage report does not contain class $cls")
            }
        }
        println("Coverage report contains all required classes.")
    }
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.material)
    implementation(libs.material.color.utilities)

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
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    implementation(libs.play.services.oss.licenses)

    // WorkManager + Hilt integration
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    implementation(libs.zip4j)
    ksp(libs.hilt.work.compiler)

    // Security
    implementation(libs.google.tink.android)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation("com.lemonappdev:konsist:0.13.0")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.arch.core.testing)
    kspTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.compose.ui:ui-test")
    androidTestImplementation(libs.androidx.benchmark.macro)
    androidTestImplementation(libs.play.services.stats)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<org.gradle.api.tasks.testing.Test> {
    systemProperty("robolectric.coverage.enabled", "true")
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
