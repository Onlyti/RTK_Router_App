package com.onlyti.rtkrouter.desktop.ui

import com.onlyti.rtkrouter.desktop.bridge.RtkBridge
import com.onlyti.rtkrouter.desktop.config.CasterPreset
import com.onlyti.rtkrouter.desktop.config.CasterProfile
import com.onlyti.rtkrouter.desktop.config.DesktopSettings
import com.onlyti.rtkrouter.desktop.config.EndpointMode
import com.onlyti.rtkrouter.desktop.config.RtkConfig
import com.onlyti.rtkrouter.desktop.ntrip.NtripClient
import com.onlyti.rtkrouter.desktop.ntrip.StrEntry
import com.onlyti.rtkrouter.desktop.ntrip.rtcmFormatRank
import com.onlyti.rtkrouter.desktop.prefs.DesktopPrefs
import com.onlyti.rtkrouter.desktop.serial.SerialPermission
import com.onlyti.rtkrouter.desktop.serial.SerialPortHelper
import com.onlyti.rtkrouter.desktop.serial.SerialPortInfo
import com.onlyti.rtkrouter.desktop.service.RtkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Done(val entries: List<StrEntry>) : ScanState
    data class Error(val msg: String) : ScanState
}

data class PermissionDialogState(
    val visible: Boolean = false,
    val devicePath: String = "",
    val message: String = "",
    val usePkexec: Boolean = true,
    val sudoPassword: String = "",
    val busy: Boolean = false,
    val resultMessage: String = "",
)

class DesktopViewModel {
    private val prefs = DesktopPrefs()
    private val bridge = RtkBridge()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settings = MutableStateFlow(prefs.load())
    val settings: StateFlow<DesktopSettings> = _settings

    val status = RtkState.status

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan

    private val _ports = MutableStateFlow(SerialPortHelper.listPorts())
    val ports: StateFlow<List<SerialPortInfo>> = _ports

    private val _permissionDialog = MutableStateFlow(PermissionDialogState())
    val permissionDialog: StateFlow<PermissionDialogState> = _permissionDialog

    private fun save(settings: DesktopSettings) {
        _settings.value = settings
        prefs.save(settings)
    }

    private fun updateConfig(transform: (RtkConfig) -> RtkConfig) {
        save(_settings.value.copy(config = transform(_settings.value.config)))
    }

    private fun updateProfileAt(i: Int, transform: (CasterProfile) -> CasterProfile) = updateConfig { cfg ->
        val list = cfg.profiles.toMutableList()
        if (i in list.indices) list[i] = transform(list[i])
        cfg.copy(profiles = list)
    }

    fun refreshPorts() {
        _ports.value = SerialPortHelper.listPorts()
        val guess = SerialPortHelper.guessGnssPorts()
        if (_settings.value.serialDevicePath.isBlank() && guess.isNotEmpty()) {
            setSerialDevicePath(guess.first().systemPortName)
        }
    }

    fun setSerialDevicePath(path: String) {
        save(_settings.value.copy(serialDevicePath = path))
    }

    fun setHost(i: Int, v: String) = updateProfileAt(i) { it.copy(host = v.trim()) }
    fun setPort(i: Int, v: String) = updateProfileAt(i) { it.copy(port = v.trim().toIntOrNull() ?: it.port) }
    fun setUser(i: Int, v: String) = updateProfileAt(i) { it.copy(user = v) }
    fun setPass(i: Int, v: String) = updateProfileAt(i) { it.copy(pass = v) }
    fun setMount(i: Int, v: String) = updateProfileAt(i) { it.copy(preferredMount = v.trim()) }
    fun setEnabled(i: Int, v: Boolean) = updateProfileAt(i) { it.copy(enabled = v) }
    fun setActiveIndex(i: Int) = updateConfig { it.copy(activeIndex = i.coerceIn(-1, it.profiles.size - 1)) }

    fun addProfile(host: String = "", port: Int = 2101, name: String = "caster") = updateConfig { cfg ->
        val list = cfg.profiles.toMutableList()
        list.add(CasterProfile(name = name, host = host, port = port, priority = list.size))
        cfg.copy(profiles = list, activeIndex = list.size - 1)
    }

    fun addPreset(preset: CasterPreset) = updateConfig { cfg ->
        val list = cfg.profiles.toMutableList()
        list.add(
            CasterProfile(
                name = preset.name,
                host = preset.host,
                port = preset.port,
                preferredMount = preset.defaultMount,
                priority = list.size,
            ),
        )
        cfg.copy(profiles = list, activeIndex = list.size - 1)
    }

    fun removeProfile(i: Int) = updateConfig { cfg ->
        if (cfg.profiles.size <= 1) return@updateConfig cfg
        val list = cfg.profiles.toMutableList().also { it.removeAt(i) }
        cfg.copy(profiles = list, activeIndex = cfg.activeIndex.coerceIn(0, list.size - 1))
    }

    fun setBaud(v: Int) = updateConfig { it.copy(baud = v) }
    fun setHotStandbyCount(v: Int) = updateConfig { it.copy(hotStandbyCount = v) }
    fun setEndpointMode(m: EndpointMode) = updateConfig { it.copy(endpointMode = m) }

    fun scanEndpoints() {
        val profile = _settings.value.config.activeProfile
        if (profile.host.isBlank()) {
            _scan.value = ScanState.Error("caster host empty")
            return
        }
        _scan.value = ScanState.Scanning
        scope.launch(Dispatchers.IO) {
            _scan.value = NtripClient.fetchSourcetable(profile).fold(
                onSuccess = { list ->
                    val sorted = list.sortedWith(
                        compareByDescending<StrEntry> { rtcmFormatRank(it.format) }.thenBy { it.mount },
                    )
                    ScanState.Done(sorted)
                },
                onFailure = { ScanState.Error(it.message ?: "scan failed") },
            )
        }
    }

    fun pickMount(mount: String) {
        setMount(_settings.value.config.activeIndex, mount)
        setEndpointMode(EndpointMode.MANUAL)
        _scan.value = ScanState.Idle
    }

    fun start() {
        val s = _settings.value
        val path = s.serialDevicePath
        if (path.isBlank()) {
            _permissionDialog.value = PermissionDialogState(
                visible = true,
                devicePath = "",
                message = "시리얼 포트를 선택하세요.",
            )
            return
        }
        if (SerialPermission.needsPermissionFix() && !SerialPermission.canReadWrite(path)) {
            _permissionDialog.value = PermissionDialogState(
                visible = true,
                devicePath = path,
                message = "$path 에 접근 권한이 없습니다.\npkexec 또는 sudo로 권한을 부여하세요.",
                usePkexec = SerialPermission.hasPkexec(),
            )
            return
        }
        bridge.start(s.config, path)
    }

    fun stop() = bridge.stop()
    fun resetUsage() = bridge.resetUsage()

    fun dismissPermissionDialog() {
        _permissionDialog.value = _permissionDialog.value.copy(visible = false, sudoPassword = "", resultMessage = "")
    }

    fun setSudoPassword(v: String) {
        _permissionDialog.value = _permissionDialog.value.copy(sudoPassword = v)
    }

    fun fixPermissionsWithPkexec() {
        val path = _permissionDialog.value.devicePath
        if (path.isBlank()) return
        _permissionDialog.value = _permissionDialog.value.copy(busy = true, resultMessage = "")
        scope.launch(Dispatchers.IO) {
            val result = SerialPermission.fixWithPkexec(path)
            _permissionDialog.value = _permissionDialog.value.copy(
                busy = false,
                resultMessage = result.fold(
                    onSuccess = { "권한 부여 완료. START를 다시 누르세요." },
                    onFailure = { "pkexec 실패: ${it.message}" },
                ),
            )
        }
    }

    fun fixPermissionsWithSudo() {
        val path = _permissionDialog.value.devicePath
        if (path.isBlank()) return
        val pwd = _permissionDialog.value.sudoPassword.toCharArray()
        _permissionDialog.value = _permissionDialog.value.copy(busy = true, resultMessage = "", sudoPassword = "")
        scope.launch(Dispatchers.IO) {
            val result = SerialPermission.fixWithSudo(path, pwd)
            _permissionDialog.value = _permissionDialog.value.copy(
                busy = false,
                resultMessage = result.fold(
                    onSuccess = { "권한 부여 완료. START를 다시 누르세요." },
                    onFailure = { "sudo 실패: ${it.message}" },
                ),
            )
        }
    }

    fun addUserToDialout() {
        _permissionDialog.value = _permissionDialog.value.copy(busy = true, resultMessage = "")
        scope.launch(Dispatchers.IO) {
            val result = SerialPermission.addUserToDialoutPkexec()
            _permissionDialog.value = _permissionDialog.value.copy(
                busy = false,
                resultMessage = result.fold(
                    onSuccess = { it },
                    onFailure = { "dialout 추가 실패: ${it.message}" },
                ),
            )
        }
    }

    fun shutdown() = bridge.shutdown()
}
