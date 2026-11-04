plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose)
}

kotlin {
    android {
        namespace = "br.com.xlinx.habblaud.shared"
        compileSdk { version = release(37) { minorApiLevel = 0 } }
        minSdk = 26
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        androidResources { enable = true }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ZaplaudShared"
            isStatic = true
        }
        if (target.name == "iosSimulatorArm64") {
            providers.gradleProperty("zaplaud.nativeTestEntitlements").orNull?.let { path ->
                target.binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
                    // Simulator entitlements belong in Mach-O sections, not the host macOS code signature.
                    linkerOpts("-sectcreate", "__TEXT", "__entitlements", path,
                        "-sectcreate", "__TEXT", "__ents_der", "$path.der")
                    linkTaskProvider.configure { inputs.files(path, "$path.der") }
                }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.markdown.m3)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation(libs.serialization.json)
            api(libs.ktor.client.core)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
        }
        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.datastore)
            implementation(libs.coroutines.android)
            implementation(libs.okhttp)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        commonTest.dependencies { implementation(kotlin("test")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0") }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.mockwebserver)
            implementation("io.ktor:ktor-client-mock:3.6.1")
            implementation("org.json:json:20250517")
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
            implementation(libs.mockwebserver)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "br.com.xlinx.habblaud.resources"
}
