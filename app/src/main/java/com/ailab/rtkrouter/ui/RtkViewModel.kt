package com.ailab.rtkrouter.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.ailab.rtkrouter.config.CasterProfile
import com.ailab.rtkrouter.config.EndpointMode
import com.ailab.rtkrouter.config.RtkConfig
import com.ailab.rtkrouter.service.RtkService
import com.ailab.rtkrouter.service.RtkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

class RtkViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val json = Json { encodeDefaults = true }

    private val _config = MutableStateFlow(prefs.load())
    val config: StateFlow<RtkConfig> = _config

    val status = RtkState.status

    private fun update(transform: (RtkConfig) -> RtkConfig) {
        val next = transform(_config.value)
        _config.value = next
        prefs.save(next)
    }

    private fun updateProfile(transform: (CasterProfile) -> CasterProfile) = update { cfg ->
        val list = cfg.profiles.toMutableList()
        if (list.isEmpty()) list.add(CasterProfile())
        list[cfg.activeIndex] = transform(list[cfg.activeIndex])
        cfg.copy(profiles = list)
    }

    fun setHost(v: String) = updateProfile { it.copy(host = v.trim()) }
    fun setPort(v: String) = updateProfile { it.copy(port = v.trim().toIntOrNull() ?: it.port) }
    fun setUser(v: String) = updateProfile { it.copy(user = v) }
    fun setPass(v: String) = updateProfile { it.copy(pass = v) }
    fun setMount(v: String) = updateProfile { it.copy(preferredMount = v.trim()) }
    fun setBaud(v: Int) = update { it.copy(baud = v) }
    fun setEndpointMode(m: EndpointMode) = update { it.copy(endpointMode = m) }
    fun setSendGga(v: Boolean) = update { it.copy(sendGga = v) }

    fun start() {
        val app = getApplication<Application>()
        val intent = Intent(app, RtkService::class.java).apply {
            action = RtkService.ACTION_START
            putExtra(RtkService.EXTRA_CONFIG, json.encodeToString(RtkConfig.serializer(), _config.value))
        }
        app.startForegroundService(intent)
    }

    fun stop() {
        val app = getApplication<Application>()
        app.startService(Intent(app, RtkService::class.java).apply { action = RtkService.ACTION_STOP })
    }
}
