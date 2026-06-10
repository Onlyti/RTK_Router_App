package com.onlyti.rtkrouter.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.onlyti.rtkrouter.desktop.config.CasterPresets
import com.onlyti.rtkrouter.desktop.config.DesktopSettings
import com.onlyti.rtkrouter.desktop.config.EndpointMode
import com.onlyti.rtkrouter.desktop.config.RtkConfig
import com.onlyti.rtkrouter.desktop.config.SerialConnectionMode
import com.onlyti.rtkrouter.desktop.serial.PortAvailability
import com.onlyti.rtkrouter.desktop.serial.PortScanEntry
import com.onlyti.rtkrouter.desktop.gnss.Nmea
import com.onlyti.rtkrouter.desktop.platform.Platform
import com.onlyti.rtkrouter.desktop.serial.SerialPermission
import com.onlyti.rtkrouter.desktop.service.RtkStatus

@Composable
fun DesktopApp() {
    val vm = remember { DesktopViewModel() }
    DisposableEffect(Unit) {
        vm.beginPortScanning()
        onDispose { vm.shutdown() }
    }

    val settings by vm.settings.collectAsState()
    val status by vm.status.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column {
            DesktopScreen(vm, settings, status)
            PermissionDialog(vm)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DesktopScreen(vm: DesktopViewModel, settings: DesktopSettings, status: RtkStatus) {
    val config = settings.config
    val portEntries by vm.portEntries.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("RTK Router", style = MaterialTheme.typography.headlineSmall)
        Text(
            "NTRIP RTCM → USB/serial → GNSS receiver (u-blox F9P, USB-UART adapter)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        SerialPortSection(
            vm = vm,
            settings = settings,
            portEntries = portEntries,
            running = status.running,
        )

        ProfilesSection(vm, config)

        if (settings.connectionMode == SerialConnectionMode.RS232) {
            Text("Baud (USB-UART adapter)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in listOf(4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)) {
                    FilterChip(
                        selected = config.baud == b,
                        onClick = { vm.setBaud(b) },
                        enabled = !status.running,
                        label = { Text("$b") },
                    )
                }
            }
        }

        Text("Endpoint mode", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in EndpointMode.entries) {
                FilterChip(
                    selected = config.endpointMode == m,
                    onClick = { vm.setEndpointMode(m) },
                    enabled = !status.running,
                    label = { Text(m.name) },
                )
            }
        }

        Text("Standby bases (fixed only)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in 1..3) {
                FilterChip(
                    selected = config.hotStandbyCount == c,
                    onClick = { vm.setHotStandbyCount(c) },
                    enabled = !status.running,
                    label = { Text("$c") },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::start, enabled = !status.running) { Text("START") }
            Button(onClick = vm::stop, enabled = status.running) { Text("STOP") }
        }

        StatusCard(status, showDataUsage = config.showDataUsage, onResetUsage = vm::resetUsage)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SerialPortSection(
    vm: DesktopViewModel,
    settings: DesktopSettings,
    portEntries: List<PortScanEntry>,
    running: Boolean,
) {
    var modeExpanded by remember { mutableStateOf(false) }
    var portExpanded by remember { mutableStateOf(false) }
    val selectedPath = settings.serialDevicePath

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::scanPorts, enabled = !running) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh ports")
                }
            }

            ExposedDropdownMenuBox(
                expanded = modeExpanded,
                onExpandedChange = { if (!running) modeExpanded = it },
            ) {
                OutlinedTextField(
                    value = when (settings.connectionMode) {
                        SerialConnectionMode.RS232 -> "RS232 (USB-UART / adapter)"
                        SerialConnectionMode.NOVATEL_USB -> "NovAtel USB (multi-COM)"
                        SerialConnectionMode.ROS_RTCM -> "ROS /rtcm (u-blox)"
                    },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    label = { Text("Interface") },
                    enabled = !running,
                )
                ExposedDropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("RS232 (USB-UART / adapter)") },
                        onClick = {
                            vm.setConnectionMode(SerialConnectionMode.RS232)
                            modeExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("NovAtel USB (auto RTCM config)") },
                        onClick = {
                            vm.setConnectionMode(SerialConnectionMode.NOVATEL_USB)
                            modeExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("ROS /rtcm (u-blox)") },
                        onClick = {
                            vm.setConnectionMode(SerialConnectionMode.ROS_RTCM)
                            modeExpanded = false
                        },
                    )
                }
            }

            if (settings.connectionMode == SerialConnectionMode.NOVATEL_USB) {
                Text(
                    "START 시 LOG GPGGA + INTERFACEMODE RTCM ON (SAVECONFIG 없음). " +
                        "기본: 사용 가능한 COM 번호가 가장 높은 포트.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (settings.connectionMode == SerialConnectionMode.ROS_RTCM) {
                RosSettings(vm, settings.config, running)
            } else {
                ExposedDropdownMenuBox(
                    expanded = portExpanded,
                    onExpandedChange = { if (!running) portExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedPath.ifBlank { "(select port)" },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = portExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text(if (settings.connectionMode == SerialConnectionMode.RS232) Platform.serialPortHint else "NovAtel COM port") },
                        enabled = !running,
                    )
                    ExposedDropdownMenu(expanded = portExpanded, onDismissRequest = { portExpanded = false }) {
                        if (portEntries.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("no matching ports — check mode & USB cable") },
                                onClick = { portExpanded = false },
                            )
                        } else {
                            for (p in portEntries) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            PortLed(p.availability)
                                            Text(p.displayLabel, modifier = Modifier.padding(start = 8.dp))
                                        }
                                    },
                                    onClick = {
                                        vm.setSerialDevicePath(p.systemPortName)
                                        portExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                if (portEntries.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (p in portEntries) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PortLed(p.availability)
                                Text(
                                    "${p.displayLabel} — ${portAvailabilityLabel(p.availability)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }

                Platform.serialPermissionHint?.let { hint ->
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun RosSettings(vm: DesktopViewModel, config: RtkConfig, running: Boolean) {
    OutlinedTextField(
        value = config.rosTopic,
        onValueChange = { vm.setRosTopic(it) },
        label = { Text("ROS topic") },
        singleLine = true,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = config.rosFrameId,
        onValueChange = { vm.setRosFrameId(it) },
        label = { Text("frame_id (비워도 됨)") },
        singleLine = true,
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "START 시 앱이 rospy 노드를 띄워 RTCM3 를 ${config.rosTopic} 로 publish 합니다. " +
            "ROS master(ROS_MASTER_URI)·rospy·rtcm_msgs 가 보이도록 ROS source 된 터미널에서 앱을 실행하세요. " +
            "ublox_gps 가 이 토픽을 subscribe → M8P RTK fix.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun PortLed(availability: PortAvailability) {
    val color = when (availability) {
        PortAvailability.AVAILABLE -> Color(0xFF2E7D32)
        PortAvailability.BUSY -> Color(0xFFC62828)
        PortAvailability.NO_PERMISSION -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

private fun portAvailabilityLabel(a: PortAvailability): String = when (a) {
    PortAvailability.AVAILABLE -> "available"
    PortAvailability.BUSY -> "in use"
    PortAvailability.NO_PERMISSION -> "no permission"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfilesSection(vm: DesktopViewModel, config: RtkConfig) {
    Text("Casters (multi-network hot-standby)", style = MaterialTheme.typography.titleMedium)

    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "+ add preset caster",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            label = { Text("presets") },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (p in CasterPresets.ALL) {
                DropdownMenuItem(
                    text = { Text("[${p.country}] ${p.name}  ·  ${p.host}:${p.port}") },
                    onClick = { vm.addPreset(p); expanded = false },
                )
            }
            DropdownMenuItem(text = { Text("+ empty caster") }, onClick = { vm.addProfile(); expanded = false })
        }
    }

    config.profiles.forEachIndexed { i, p ->
        val isActive = i == config.activeIndex
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = isActive,
                        onClick = { vm.setActiveIndex(if (isActive) -1 else i) },
                        label = { Text(if (isActive) "▼" else "▶") },
                    )
                    Text(
                        "  ${p.name}  ·  ${p.host.ifBlank { "(no host)" }}:${p.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = p.enabled, onCheckedChange = { vm.setEnabled(i, it) })
                    if (config.profiles.size > 1) {
                        TextButton(onClick = { vm.removeProfile(i) }, contentPadding = PaddingValues(4.dp)) {
                            Text("✕")
                        }
                    }
                }
                if (isActive) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        OutlinedTextField(
                            value = p.host,
                            onValueChange = { vm.setHost(i, it) },
                            label = { Text("host") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = p.port.toString(),
                                onValueChange = { vm.setPort(i, it) },
                                label = { Text("port") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = p.preferredMount,
                                onValueChange = { vm.setMount(i, it) },
                                label = { Text("mount (blank=AUTO)") },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = p.user,
                                onValueChange = { vm.setUser(i, it) },
                                label = { Text("user") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = p.pass,
                                onValueChange = { vm.setPass(i, it) },
                                label = { Text("pass") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        ScanSection(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanSection(vm: DesktopViewModel) {
    val scan by vm.scan.collectAsState()
    OutlinedButton(onClick = vm::scanEndpoints) { Text("SCAN endpoints") }
    when (val s = scan) {
        is ScanState.Idle -> {}
        is ScanState.Scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            Text("scanning sourcetable...")
        }
        is ScanState.Error -> Text(
            "scan failed: ${s.msg}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is ScanState.Done -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("${s.entries.size} mountpoints (click to select)", style = MaterialTheme.typography.labelMedium)
                Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    for (e in s.entries) {
                        val tag = if (e.requiresGga) "VRS" else "FIX"
                        Text(
                            "${e.mount}   ·   ${e.format}   ·   $tag",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.pickMount(e.mount) }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(s: RtkStatus, showDataUsage: Boolean, onResetUsage: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            if (s.healthLevel == "warn" && s.healthMessage.isNotBlank()) {
                Text(
                    "⚠ ${s.healthMessage}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE65100),
                )
            }
            line("NTRIP", if (s.ntripConnected) "connected" else "down")
            if (s.rosActive) {
                line("ROS node", if (s.rosNodeAlive) "running → ${s.rosTopic}" else "stopped")
                if (s.rosNodeMessage.isNotBlank()) {
                    Text(s.rosNodeMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                line("Serial", if (s.serialConnected) s.deviceName else "down")
            }
            line("Provider/Mount", "${s.activeProfileName} / ${s.activeMount.ifBlank { "-" }}")
            line("Mode / GGA", "${s.activeMode.ifBlank { "-" }} / ${if (s.ggaActive) "on" else "off"}")
            line("Failover", s.failoverLevel)
            if (s.streamCount > 0) {
                line("Bases", "${s.healthyCount}/${s.streamCount} healthy")
                for (l in s.streamLines) {
                    Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            val fix = if (s.lastFixQuality >= 0) Nmea.fixQualityLabel(s.lastFixQuality) else "-"
            line("Fix", fix)
            if (s.lastFixQuality >= 0) {
                line("Rx pos", if (s.rxLat.isNaN()) "-" else String.format("%.7f, %.7f", s.rxLat, s.rxLon))
                line("Rx sats / HDOP", "${s.rxSats} / ${if (s.rxHdop.isNaN()) "-" else String.format("%.1f", s.rxHdop)}")
                line("Rx alt", if (s.rxAltM.isNaN()) "-" else String.format("%.1f m", s.rxAltM))
            }
            if (showDataUsage) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Data usage", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onResetUsage, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("reset", style = MaterialTheme.typography.bodySmall)
                    }
                }
                line("RTCM rate", "${s.rtcmBytesPerSec} B/s")
                line("Rx (caster)", human(s.sessionRxBytes))
                line("Tx GGA (caster)", human(s.sessionTxBytes))
                line("Serial Tx/Rx", "${human(s.serialTxBytes)} / ${human(s.serialRxBytes)}")
            }
            line("Uptime", "${s.uptimeSec}s")
            if (s.lastError.reason.isNotBlank()) {
                line("Error", "[${s.lastError.failureMode}/${s.lastError.level}] ${s.lastError.reason}")
            }
            if (s.detail.isNotBlank()) line("Detail", s.detail)
            if (s.lastNmea.isNotBlank()) {
                Text(s.lastNmea, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun PermissionDialog(vm: DesktopViewModel) {
    val dlg by vm.permissionDialog.collectAsState()
    if (!dlg.visible) return

    AlertDialog(
        onDismissRequest = vm::dismissPermissionDialog,
        title = { Text("Serial port permissions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dlg.message)
                if (dlg.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("권한 요청 중...")
                    }
                }
                if (dlg.resultMessage.isNotBlank()) {
                    Text(dlg.resultMessage, color = MaterialTheme.colorScheme.primary)
                }
                if (!dlg.usePkexec && dlg.devicePath.isNotBlank()) {
                    OutlinedTextField(
                        value = dlg.sudoPassword,
                        onValueChange = vm::setSudoPassword,
                        label = { Text("sudo password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (SerialPermission.needsPermissionFix() && dlg.devicePath.isNotBlank()) {
                if (dlg.usePkexec) {
                    TextButton(onClick = vm::fixPermissionsWithPkexec, enabled = !dlg.busy) {
                        Text("pkexec로 권한 부여")
                    }
                } else {
                    TextButton(onClick = vm::fixPermissionsWithSudo, enabled = !dlg.busy && dlg.sudoPassword.isNotEmpty()) {
                        Text("sudo로 권한 부여")
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (SerialPermission.needsPermissionFix() && dlg.devicePath.isNotBlank()) {
                    TextButton(onClick = vm::addUserToDialout, enabled = !dlg.busy) {
                        Text("dialout 영구 추가")
                    }
                }
                TextButton(onClick = vm::dismissPermissionDialog) { Text("닫기") }
            }
        },
    )
}

@Composable
private fun line(k: String, v: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun human(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.1f kB", bytes / 1_000.0)
    else -> "$bytes B"
}
