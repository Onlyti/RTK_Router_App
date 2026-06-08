import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    kotlin("plugin.serialization")
}

group = "com.onlyti.rtkrouter"
version = "1.0.2"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Linux /dev/tty* and Windows COM* serial access.
    implementation("com.fazecast:jSerialComm:2.10.4")
}

compose.desktop {
    application {
        mainClass = "com.onlyti.rtkrouter.desktop.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "rtk-router"
            packageVersion = "1.0.2"
            description = "NTRIP RTCM router for GNSS receivers"
            vendor = "onlyti"

            windows {
                menu = true
                menuGroup = "RTK Router"
                shortcut = true
                shortcutPrompt = true
            }
        }
    }
}
