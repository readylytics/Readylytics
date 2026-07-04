import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

pluginManager.withPlugin("com.android.library") {
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(17)
        compilerOptions {
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }
}
