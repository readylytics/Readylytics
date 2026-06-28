import com.github.triplet.gradle.androidpublisher.ReleaseStatus
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
        providers
            .environmentVariable(it)
            .orNull
            ?.trim()
            .orEmpty()
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
    alias(libs.plugins.play.publisher)
    id("kotlin-parcelize")
    id("jacoco")
}

fun envVar(name: String): String? = providers.environmentVariable(name).orNull

fun computeVersion(): Pair<Int, String> {
    val isCI = envVar("GITHUB_ACTIONS") == "true"
    val isTag = isCI && envVar("GITHUB_REF_TYPE") == "tag"
    val refName = envVar("GITHUB_REF_NAME") ?: ""
    val isReleaseTag = isTag && refName.matches(Regex("^v?\\d+\\.\\d+\\.\\d+$"))

    val baseVersionName = project.findProperty("baseVersionName")?.toString() ?: "0.1.0"

    return if (isReleaseTag) {
        val cleanTag = refName.removePrefix("v")
        val parts = cleanTag.split(".")
        val major = parts[0].toIntOrNull() ?: 1
        val minor = parts[1].toIntOrNull() ?: 0
        val patch = parts[2].toIntOrNull() ?: 0
        val code = major * 1000000 + minor * 10000 + patch
        Pair(code, cleanTag)
    } else if (isCI) {
        val runNumber = envVar("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        val code = 100000 + runNumber
        Pair(code, "$baseVersionName.$runNumber")
    } else {
        Pair(1, "$baseVersionName-local")
    }
}

val resolvedVersion = computeVersion()
val computedVersionCode = resolvedVersion.first
val computedVersionName = resolvedVersion.second

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
        versionCode = computedVersionCode
        versionName = computedVersionName

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
        disable += listOf("GradleDependency", "NewerVersionAvailable")
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

play {
    serviceAccountCredentials.set(
        providers
            .environmentVariable("PLAY_SERVICE_ACCOUNT_JSON_FILE")
            .map { layout.projectDirectory.file(it) },
    )
    track.set("production")
    releaseStatus.set(ReleaseStatus.COMPLETED)
    defaultToAppBundles.set(true)
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
            // Kotlin compiler-generated synthetic classes (not directly unit-testable)
            "**/*\$WhenMappings.*",
            // Android Application/Activity classes (integration-tested, not unit-tested)
            "app/readylytics/health/HealthDashboardApplication*",
            "app/readylytics/health/MainActivity*",
            // Compose-only UI packages — deferred to E2E tests (plan: coverage_improvement_10_to_30)
            "**/ui/settings/backup/**",
            "**/ui/settings/physiologyprofile/**",
            "**/ui/about/**",
            "**/ui/recovery/**",
            "**/ui/health/**",
            "**/ui/common/**",
            "**/ui/vitals/**",
            "**/ui/scaffold/**",
            "**/ui/accessibility/**",
            "**/ui/bodyfat/**",
            "**/ui/insights/**",
            "**/ui/navigation/**",
            // Direct files in ui/workouts and ui/components only (NOT subdirs like mappers/reorder)
            "**/ui/workouts/*",
            "**/ui/components/*",
            // Utility classes with no testable logic beyond trivial wrappers
            "**/health/util/**",
        )

    sourceDirectories.setFrom(
        layout.projectDirectory.dir("src/main/java"),
        layout.projectDirectory.dir("src/main/kotlin"),
    )
    classDirectories.setFrom(
        fileTree("${project.buildDir}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").matching {
            exclude(fileFilter)
        },
    )
    executionData.setFrom(
        layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
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
            // Kotlin compiler-generated synthetic classes (not directly unit-testable)
            "**/*\$WhenMappings.*",
            // Android Application/Activity classes (integration-tested, not unit-tested)
            "app/readylytics/health/HealthDashboardApplication*",
            "app/readylytics/health/MainActivity*",
            // Compose-only UI packages — deferred to E2E tests (plan: coverage_improvement_10_to_30)
            "**/ui/settings/backup/**",
            "**/ui/settings/physiologyprofile/**",
            "**/ui/about/**",
            "**/ui/recovery/**",
            "**/ui/health/**",
            "**/ui/common/**",
            "**/ui/vitals/**",
            "**/ui/scaffold/**",
            "**/ui/accessibility/**",
            "**/ui/bodyfat/**",
            "**/ui/insights/**",
            "**/ui/navigation/**",
            // Direct files in ui/workouts and ui/components only (NOT subdirs like mappers/reorder)
            "**/ui/workouts/*",
            "**/ui/components/*",
            // Utility and data.util (no testable logic beyond trivial wrappers)
            "**/health/util/**",
        )

    sourceDirectories.setFrom(
        layout.projectDirectory.dir("src/main/java"),
        layout.projectDirectory.dir("src/main/kotlin"),
    )

    executionData.setFrom(
        layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
    )
    classDirectories.setFrom(
        fileTree("${project.buildDir}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").matching {
            exclude(fileFilter)
        },
    )

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = 0.24.toBigDecimal()
            }
        }
        // domain.scoring and domain.sync moved to :core:scoring and :core:healthconnect;
        // those modules carry their own jacocoCoverageVerification tasks.
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
    implementation(project(":feature:about"))
    implementation(project(":feature:insights"))
    implementation(project(":feature:sleep"))
    implementation(project(":feature:workouts"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:scoring"))
    implementation(project(":core:database"))
    implementation(project(":core:healthconnect"))
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

    // Installs the AOT baseline profile generated by BaselineProfileGenerator
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.konsist)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.arch.core.testing)
    testRuntimeOnly(
        files(
            layout.buildDirectory.file(
                "intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
            ),
        ),
    )
    kspTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.benchmark.macro)
    androidTestImplementation(libs.play.services.stats)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("robolectric.coverage.enabled", "true")
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
