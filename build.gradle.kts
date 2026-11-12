import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.android.application") version "8.11.1" apply false
}

// The core modules are plain Kotlin with no dependencies. Each has a `selfTest` task that runs its
// test main (see testkit), wired into `check`. scripts/check.sh does the same with kotlinc alone.
val core = setOf("testkit", "formula", "spec", "engine", "composer", "ai", "data")
subprojects {
    if (name in core) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
