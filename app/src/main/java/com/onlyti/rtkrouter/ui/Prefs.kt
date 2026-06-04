package com.onlyti.rtkrouter.ui

import android.content.Context
import com.onlyti.rtkrouter.config.RtkConfig
import kotlinx.serialization.json.Json

/** Persists [RtkConfig] as a JSON blob in SharedPreferences (credentials stay on-device). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("rtk_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): RtkConfig {
        val s = sp.getString(KEY_CONFIG, null) ?: return RtkConfig()
        return try {
            json.decodeFromString(RtkConfig.serializer(), s)
        } catch (_: Throwable) {
            RtkConfig()
        }
    }

    fun save(config: RtkConfig) {
        sp.edit().putString(KEY_CONFIG, json.encodeToString(RtkConfig.serializer(), config)).apply()
    }

    companion object {
        private const val KEY_CONFIG = "config"
    }
}
