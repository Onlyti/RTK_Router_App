import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    kotlin("plugin.serialization")
}

group = "com.onlyti.rtkrouter"
version = "1.0.9"

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
            packageVersion = "1.0.9"
            description = "NTRIP RTCM router for GNSS receivers"
            vendor = "onlyti"

            windows {
                menu = true
                menuGroup = "RTK Router"
                shortcut = true
                // Stable MSI product id for in-place upgrades.
                upgradeUuid = "c4e8f2a1-9b3d-4f6e-a812-0d5e7b9c3f21"
            }

            linux {
                // Compose emits "Maintainer: <vendor> <debMaintainer>", so debMaintainer must be
                // the EMAIL ONLY. An unset/badly-formatted value yields a malformed Maintainer
                // (e.g. nested "<>" or missing email) that dpkg rejects on install
                // ("'Maintainer' field, ... found newline"). Email-only -> "onlyti <pauljiwon96@gmail.com>".
                debMaintainer = "pauljiwon96@gmail.com"
                menuGroup = "RTK Router"
                appCategory = "Science"
                shortcut = true
            }
        }
    }
}

// shortcutPrompt DSL is not in Compose 1.6.11; pass jpackage flag directly so the
// MSI installer shows "Create start menu / desktop shortcuts" checkboxes (JDK 17+).
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Msi) {
        freeArgs.add("--win-shortcut-prompt")
    }
    // Deb: jpackage installs only under /opt and adds no PATH entry, so after the
    // package is built we repack it (via dpkg-deb, which jpackage itself uses to
    // build the .deb, so it is always present here) to swap in postinst/postrm
    // that create a /usr/local/bin symlink — making `rtk-router` work on PATH
    // after install. Overriding jpackage's resource-dir doesn't take because
    // Compose passes its own resource-dir, so we patch the finished artifact.
    // Canonical scripts live in jpackage/linux/.
    // Ship a headless CLI launcher (Linux) alongside the GUI: /opt/rtk-router/bin/rtk-router-cli
    // runs com.onlyti.rtkrouter.desktop.CliMainKt (reads the GUI's settings.json, no display).
    if (targetFormat == TargetFormat.Deb) {
        freeArgs.add("--add-launcher")
        freeArgs.add("rtk-router-cli=" + project.file("jpackage/cli-launcher.properties").absolutePath)
    }
    if (targetFormat == TargetFormat.Deb) {
        doLast {
            val debDir = project.layout.buildDirectory.dir("compose/binaries/main-release/deb").get().asFile
            val deb = debDir.listFiles { f -> f.name.endsWith(".deb") }?.firstOrNull()
                ?: throw GradleException("packageReleaseDeb: .deb not found in $debDir")
            val work = File(project.layout.buildDirectory.get().asFile, "deb-repack")
            work.deleteRecursively()
            project.exec { commandLine("dpkg-deb", "-R", deb.absolutePath, work.absolutePath) }
            val ctrl = File(work, "DEBIAN")
            for (name in listOf("postinst", "postrm")) {
                val dst = File(ctrl, name)
                project.file("jpackage/linux/$name").copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
            }
            project.exec { commandLine("dpkg-deb", "--build", work.absolutePath, deb.absolutePath) }
            work.deleteRecursively()
        }
    }
}
