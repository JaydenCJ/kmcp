import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    `maven-publish`
}

group = "dev.kmcp"
version = "0.1.0"
description = "An MCP client/server SDK for Kotlin Multiplatform — one protocol core in commonMain, verified on the JVM, with echo server/client samples."

kotlin {
    // Library authors: every public declaration must be explicit and documented.
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
                testLogging {
                    events("passed", "failed", "skipped")
                }
            }
        }
    }

    // Apple targets are declared so the published klibs cover iOS consumers.
    // On non-macOS hosts the Kotlin Gradle Plugin disables their tasks; the
    // JVM target remains fully buildable and testable everywhere.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}

// Runs the bundled echo MCP server sample (jvmMain) on 127.0.0.1.
tasks.register<JavaExec>("runEchoServer") {
    group = "application"
    description = "Starts the echo MCP server sample on http://127.0.0.1:8931/mcp"
    dependsOn("jvmMainClasses")
    classpath = files(
        kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs,
        configurations.getByName("jvmRuntimeClasspath"),
    )
    mainClass.set("dev.kmcp.sample.EchoServerMainKt")
}
