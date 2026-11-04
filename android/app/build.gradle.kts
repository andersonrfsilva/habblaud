plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

android {
    namespace = "br.com.xlinx.habblaud"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "br.com.xlinx.habblaud"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("release") {
            val path = providers.environmentVariable("HABBLAUD_KEYSTORE").orElse(providers.gradleProperty("HABBLAUD_KEYSTORE")).orNull
            val password = providers.environmentVariable("HABBLAUD_KEYSTORE_PASSWORD").orElse(providers.gradleProperty("HABBLAUD_KEYSTORE_PASSWORD")).orNull
            val alias = providers.environmentVariable("HABBLAUD_KEY_ALIAS").orElse(providers.gradleProperty("HABBLAUD_KEY_ALIAS")).orNull
            val keyPasswordValue = providers.environmentVariable("HABBLAUD_KEY_PASSWORD").orElse(providers.gradleProperty("HABBLAUD_KEY_PASSWORD")).orNull
            if (path != null && password != null && alias != null && keyPasswordValue != null) {
                storeFile = file(path); storePassword = password; keyAlias = alias; keyPassword = keyPasswordValue
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(android.signingConfigs.getByName("release").storeFile != null) {
            "Configure HABBLAUD_KEYSTORE, HABBLAUD_KEYSTORE_PASSWORD, HABBLAUD_KEY_ALIAS e HABBLAUD_KEY_PASSWORD."
        }
    }
}
tasks.matching { it.name == "packageRelease" }.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
