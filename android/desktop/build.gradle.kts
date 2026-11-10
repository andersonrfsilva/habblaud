import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose)
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}
compose.desktop {
    application {
        mainClass = "br.com.xlinx.habblaud.desktop.MainKt"
        jvmArgs += listOf("-XX:+UseSerialGC", "-Xmx256m")
        // shortcut: ProGuard 7.8 falha em referências opcionais do OkHttp; reavaliar com regras -dontwarn medidas no 067
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "Zaplaud"
            packageVersion = "1.0.0"
            description = "Cliente do Habblaud"
            vendor = "xlinx"
            modules("java.instrument", "java.management", "java.prefs", "jdk.security.auth", "jdk.unsupported")
            linux {
                iconFile.set(project.file("icons/zaplaud.png"))
                shortcut = true
                menuGroup = "Network"
            }
            macOS {
                bundleID = "br.com.xlinx.zaplaud"
                project.file("icons/zaplaud.icns").takeIf { it.exists() }?.let { iconFile.set(it) }
            }
        }
    }
}
