import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("io.gitlab.arturbosch.detekt")
}

pluginManager.withPlugin("com.android.library") {
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(17)
    }
}

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    // Per-module baseline. A single shared root baseline cannot work: `detektBaseline` is a
    // per-project task, every module writes the same path, and the last writer wins — so the
    // file only ever holds one module's findings and `./gradlew detektBaseline` cannot
    // reproduce it. Config stays shared; only the baseline is split.
    baseline = layout.projectDirectory.file("detekt-baseline.xml").asFile
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(true)
        html.outputLocation.set(layout.buildDirectory.file("reports/detekt/${name}.html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/detekt/${name}.xml"))
        sarif.outputLocation.set(layout.buildDirectory.file("reports/detekt/${name}.sarif"))
    }
}

// MockK mocks final Kotlin classes through its inline agent, which self-attaches to the running
// JVM. Self-attach on JDK 9+ signals the VM and waits on a socket file; under load it intermittently
// times out as AttachNotSupportedException, and once JvmMockKGateway fails to initialise every later
// mockk call in that fork dies with NoClassDefFoundError — observed at ~2-in-3 runs in
// :core:database once phase 3 concentrated 76 mockk-using tests there. Preloading the agent removes
// the attach step entirely.
//
// Two details are load-bearing. The configuration is DETACHED so the plain JVM jar carries no
// Android variant attributes to disambiguate, and the path is resolved EAGERLY into a String so the
// configuration cache stores a value rather than a live Configuration reference. Earlier attempts
// that captured the Configuration lazily failed to serialise.
val byteBuddyAgentPath: String =
    configurations
        .detachedConfiguration(dependencies.create("net.bytebuddy:byte-buddy-agent:1.18.2"))
        .singleFile
        .absolutePath

tasks.withType<Test>().configureEach {
    jvmArgs("-javaagent:$byteBuddyAgentPath")
}
