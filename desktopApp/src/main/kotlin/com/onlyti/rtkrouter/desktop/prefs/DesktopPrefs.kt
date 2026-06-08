package com.onlyti.rtkrouter.desktop.prefs

import com.onlyti.rtkrouter.desktop.config.DesktopSettings
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DesktopPrefs {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val path: Path = Path.of(
        System.getProperty("user.home"),
        ".config",
        "rtk-router",
        "settings.json",
    )

    fun load(): DesktopSettings {
        if (!path.exists()) return DesktopSettings()
        return try {
            json.decodeFromString(DesktopSettings.serializer(), path.readText())
        } catch (_: Throwable) {
            DesktopSettings()
        }
    }

    fun save(settings: DesktopSettings) {
        Files.createDirectories(path.parent)
        path.writeText(json.encodeToString(DesktopSettings.serializer(), settings))
    }
}
