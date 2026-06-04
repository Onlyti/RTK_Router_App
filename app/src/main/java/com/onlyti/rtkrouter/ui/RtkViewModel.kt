package com.onlyti.rtkrouter.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onlyti.rtkrouter.config.CasterProfile
import com.onlyti.rtkrouter.config.EndpointMode
import com.onlyti.rtkrouter.config.RtkConfig
import com.onlyti.rtkrouter.ntrip.NtripClient
import com.onlyti.rtkrouter.ntrip.StrEntry
import com.onlyti.rtkrouter.ntrip.rtcmFormatRank
import com.onlyti.rtkrouter.service.RtkService
import com.onlyti.rtkrouter.service.RtkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    fun setSerialPortIndex(v: Int) = update { it.copy(serialPortIndex = v) }
    fun setHotStandbyCount(v: Int) = update { it.copy(hotStandbyCount = v) }
    fun setEndpointMode(m: EndpointMode) = update { it.copy(endpointMode = m) }

    // --- Sourcetable scan for manual mountpoint selection ---
    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan

    fun scanEndpoints() {
        val profile = _config.value.activeProfile
        if (profile.host.isBlank()) {
            _scan.value = ScanState.Error("caster host empty")
            return
        }
        _scan.value = ScanState.Scanning
        viewModelScope.launch(Dispatchers.IO) {
            _scan.value = NtripClient.fetchSourcetable(profile).fold(
                onSuccess = { list ->
                    // Best RTCM3 first (3.2 > generic > 3.1 > others), then by mount name.
                    val sorted = list.sortedWith(
                        compareByDescending<StrEntry> { rtcmFormatRank(it.format) }.thenBy { it.mount },
                    )
                    ScanState.Done(sorted)
                },
                onFailure = { ScanState.Error(it.message ?: "scan failed") },
            )
        }
    }

    fun clearScan() { _scan.value = ScanState.Idle }

    /** Pick a mountpoint from the scan: fill the field and switch to MANUAL. */
    fun pickMount(mount: String) {
        setMount(mount)
        setEndpointMode(EndpointMode.MANUAL)
        _scan.value = ScanState.Idle
    }

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

/** UI state for the sourcetable scan. */
sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Done(val entries: List<StrEntry>) : ScanState
    data class Error(val msg: String) : ScanState
}
