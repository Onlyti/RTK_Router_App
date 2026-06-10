package com.onlyti.rtkrouter.desktop.ui

import com.onlyti.rtkrouter.desktop.bridge.RosRtcmPublisher
import com.onlyti.rtkrouter.desktop.bridge.RtkBridge
import com.onlyti.rtkrouter.desktop.config.CasterPreset
import com.onlyti.rtkrouter.desktop.config.CasterProfile
import com.onlyti.rtkrouter.desktop.config.DesktopSettings
import com.onlyti.rtkrouter.desktop.config.EndpointMode
import com.onlyti.rtkrouter.desktop.config.RtkConfig
import com.onlyti.rtkrouter.desktop.config.SerialConnectOptions
import com.onlyti.rtkrouter.desktop.config.SerialConnectionMode
import com.onlyti.rtkrouter.desktop.ntrip.NtripClient
import com.onlyti.rtkrouter.desktop.ntrip.StrEntry
import com.onlyti.rtkrouter.desktop.ntrip.rtcmFormatRank
import com.onlyti.rtkrouter.desktop.prefs.DesktopPrefs
import com.onlyti.rtkrouter.desktop.serial.PortAvailability
import com.onlyti.rtkrouter.desktop.serial.PortScanEntry
import com.onlyti.rtkrouter.desktop.serial.SerialPermission
import com.onlyti.rtkrouter.desktop.serial.SerialPortScanner
import com.onlyti.rtkrouter.desktop.service.RtkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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

/** ROS dependency prompt (ROS mode only): rtcm_msgs missing, or ROS env not sourced. */
data class RosDepDialogState(
    val visible: Boolean = false,
    val message: String = "",
    val canInstall: Boolean = false,
    val installLabel: String = "",
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

    private val _portEntries = MutableStateFlow<List<PortScanEntry>>(emptyList())
    val portEntries: StateFlow<List<PortScanEntry>> = _portEntries

    private val _permissionDialog = MutableStateFlow(PermissionDialogState())
    val permissionDialog: StateFlow<PermissionDialogState> = _permissionDialog

    private val _rosDepDialog = MutableStateFlow(RosDepDialogState())
    val rosDepDialog: StateFlow<RosDepDialogState> = _rosDepDialog

    private var portScanJob: Job? = null

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

    fun beginPortScanning() {
        scanPorts()
        portScanJob?.cancel()
        portScanJob = scope.launch {
            while (isActive) {
                delay(PORT_SCAN_INTERVAL_MS)
                if (!status.value.running) scanPorts()
            }
        }
    }

    fun scanPorts() {
        val mode = _settings.value.connectionMode
        val entries = SerialPortScanner.scan(mode)
        _portEntries.value = entries
        if (!_settings.value.userPickedPort) {
            SerialPortScanner.defaultFreePort(entries)?.let { pick ->
                if (pick.systemPortName != _settings.value.serialDevicePath) {
                    save(_settings.value.copy(serialDevicePath = pick.systemPortName))
                }
            }
        }
    }

    fun setConnectionMode(mode: SerialConnectionMode) {
        save(
            _settings.value.copy(
                connectionMode = mode,
                userPickedPort = false,
                serialDevicePath = "",
            ),
        )
        scanPorts()
    }

    fun setSerialDevicePath(path: String) {
        save(_settings.value.copy(serialDevicePath = path, userPickedPort = true))
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
    fun setRosTopic(v: String) = updateConfig { it.copy(rosTopic = v.trim().ifBlank { "/rtcm" }) }
    fun setRosFrameId(v: String) = updateConfig { it.copy(rosFrameId = v.trim()) }

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
        // ROS mode: no local serial — pre-flight the ROS env, then spawn the rospy node.
        if (s.connectionMode == SerialConnectionMode.ROS_RTCM) {
            scope.launch(Dispatchers.IO) {
                when (val pf = RosRtcmPublisher.preflight()) {
                    RosRtcmPublisher.Preflight.Ok ->
                        bridge.start(
                            s.config,
                            SerialConnectOptions(mode = SerialConnectionMode.ROS_RTCM, devicePath = "", baud = s.config.baud),
                        )
                    RosRtcmPublisher.Preflight.MissingRtcmMsgs ->
                        _rosDepDialog.value = RosDepDialogState(
                            visible = true,
                            canInstall = true,
                            message = "${RosRtcmPublisher.rtcmMsgsPackage()} 패키지가 없습니다.\n" +
                                "이 패키지가 있어야 /rtcm 을 publish 할 수 있습니다. 지금 설치할까요?",
                            installLabel = "pkexec 로 설치",
                        )
                    RosRtcmPublisher.Preflight.NoRos ->
                        _rosDepDialog.value = RosDepDialogState(
                            visible = true,
                            canInstall = false,
                            message = "ROS 환경(rospy/python3)을 찾을 수 없습니다.\n" +
                                "ROS 가 source 된 터미널에서 앱을 실행하세요 " +
                                "(예: source /opt/ros/noetic/setup.bash 후 실행).",
                        )
                    is RosRtcmPublisher.Preflight.Error ->
                        _rosDepDialog.value = RosDepDialogState(
                            visible = true,
                            canInstall = false,
                            message = "ROS 사전 점검 실패:\n${pf.message}",
                        )
                }
            }
            return
        }
        val path = s.serialDevicePath
        if (path.isBlank()) {
            showMessage("시리얼 포트를 선택하세요.")
            return
        }
        val entry = _portEntries.value.find { it.systemPortName == path }
        when (entry?.availability) {
            PortAvailability.BUSY -> {
                showMessage("$path 는 다른 앱이 사용 중입니다. 다른 포트를 선택하세요.")
                return
            }
            PortAvailability.NO_PERMISSION -> {
                if (SerialPermission.needsPermissionFix() && !SerialPermission.canReadWrite(path)) {
                    _permissionDialog.value = PermissionDialogState(
                        visible = true,
                        devicePath = path,
                        message = "$path 에 접근 권한이 없습니다.\npkexec 또는 sudo로 권한을 부여하세요.",
                        usePkexec = SerialPermission.hasPkexec(),
                    )
                    return
                }
            }
            else -> Unit
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
        val options = SerialConnectOptions(
            mode = s.connectionMode,
            devicePath = path,
            baud = s.config.baud,
            novAtelUsbIndex = entry?.novAtelUsbIndex?.takeIf { it > 0 } ?: 1,
        )
        bridge.start(s.config, options)
    }

    private fun showMessage(msg: String) {
        _permissionDialog.value = PermissionDialogState(visible = true, devicePath = "", message = msg)
    }

    fun stop() = bridge.stop()
    fun resetUsage() = bridge.resetUsage()

    fun dismissRosDepDialog() {
        _rosDepDialog.value = RosDepDialogState()
    }

    /** Install rtcm_msgs via pkexec; on success the user re-presses START. */
    fun installRosDep() {
        _rosDepDialog.value = _rosDepDialog.value.copy(busy = true, resultMessage = "")
        scope.launch(Dispatchers.IO) {
            val result = RosRtcmPublisher.installRtcmMsgs()
            _rosDepDialog.value = _rosDepDialog.value.copy(
                busy = false,
                canInstall = result.isFailure,
                resultMessage = result.fold(onSuccess = { it }, onFailure = { it.message ?: "설치 실패" }),
            )
        }
    }

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

    fun shutdown() {
        portScanJob?.cancel()
        bridge.shutdown()
    }

    companion object {
        private const val PORT_SCAN_INTERVAL_MS = 5_000L
    }
}
