import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.google.oss.licenses) apply false
    alias(libs.plugins.play.publisher) apply false
    jacoco
}

val coverageProjects = listOf(
    ":app",
    ":core:model", ":core:scoring", ":core:database", ":core:healthconnect",
    ":core:designsystem", ":core:ui",
    ":feature:about", ":feature:insights", ":feature:sleep", ":feature:workouts",
    ":feature:vitals", ":feature:dashboard", ":feature:settings", ":feature:onboarding",
)
val coverageExclusions = listOf(
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
    "**/*\$WhenMappings.*",
    // Android Application/Activity classes
    "app/readylytics/health/HealthDashboardApplication*",
    "app/readylytics/health/MainActivity*",
    "app/readylytics/health/PrivacyRationaleActivity*",
    // Compose-only UI packages and libraries
    "**/feature/about/**",
    "**/feature/insights/**",
    "**/feature/sleep/**",
    "**/feature/workouts/**",
    "**/feature/vitals/**",
    "**/feature/dashboard/**",
    "**/feature/settings/**",
    "**/feature/onboarding/**",
    "**/core/designsystem/**",
    "**/core/ui/**",
    "**/ui/navigation/**",
    "**/ui/scaffold/**",
    "**/ui/sync/**",
    "**/health/util/**",
)
val coveredProjects = coverageProjects.map(::project)
val coveredClasses = coveredProjects.map { module ->
    module.fileTree("${module.layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
        exclude(coverageExclusions)
    }
}
val coveredSources = coveredProjects.flatMap { module ->
    listOf(module.file("src/main/kotlin"), module.file("src/main/java"))
}
val coveredExecutionData = coveredProjects.map { module ->
    module.file("${module.layout.buildDirectory.get()}/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn(coverageProjects.map { "$it:testDebugUnitTest" })
    sourceDirectories.setFrom(coveredSources)
    classDirectories.setFrom(coveredClasses)
    executionData.setFrom(coveredExecutionData.filter(File::exists))
    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
    }
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("jacocoTestReport")
    sourceDirectories.setFrom(coveredSources)
    classDirectories.setFrom(coveredClasses)
    executionData.setFrom(coveredExecutionData.filter(File::exists))
    violationRules {
        rule { limit { counter = "INSTRUCTION"; value = "COVEREDRATIO"; minimum = "0.30".toBigDecimal() } }
        rule {
            element = "PACKAGE"
            includes = listOf("app.readylytics.health.workers")
            limit { counter = "LINE"; value = "COVEREDRATIO"; minimum = "0.60".toBigDecimal() }
        }
    }
}

