package com.onlyti.rtkrouter.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.onlyti.rtkrouter.config.CasterPresets
import com.onlyti.rtkrouter.config.EndpointMode
import com.onlyti.rtkrouter.config.RtkConfig
import com.onlyti.rtkrouter.service.GeoPt
import com.onlyti.rtkrouter.service.RtkStatus
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : ComponentActivity() {
    private val vm: RtkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { }
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                RtkScreen(vm, onStart = {
                    permLauncher.launch(requiredPermissions())
                    vm.start()
                })
            }
        }
    }

    // Only notification permission (Android 13+); no location — GGA comes from the receiver.
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RtkScreen(vm: RtkViewModel, onStart: () -> Unit) {
    val config: RtkConfig by vm.config.collectAsState()
    val status: RtkStatus by vm.status.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("RTK Router", style = MaterialTheme.typography.headlineSmall)

        ProfilesSection(vm, config)

        // Baud matters only for a UART-via-USB-serial adapter (native USB CDC ignores it).
        // NovAtel COM default 9600; u-blox F9P UART1 default 38400.
        Text("Baud (UART adapter only)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in listOf(4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)) {
                FilterChip(selected = config.baud == b, onClick = { vm.setBaud(b) }, label = { Text("$b") })
            }
        }

        Text("Endpoint mode", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in EndpointMode.entries) {
                FilterChip(
                    selected = config.endpointMode == m,
                    onClick = { vm.setEndpointMode(m) },
                    label = { Text(m.name) },
                )
            }
        }

        // Hot-standby: N nearest fixed bases kept warm (fixed-base only; VRS uses 1).
        Text("Standby bases (fixed only)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in 1..3) {
                FilterChip(
                    selected = config.hotStandbyCount == c,
                    onClick = { vm.setHotStandbyCount(c) },
                    label = { Text("$c") },
                )
            }
        }

        // GGA upload is auto-enabled when a VRS mount is detected (no manual toggle).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart, enabled = !status.running) { Text("START") }
            Button(onClick = vm::stop, enabled = status.running) { Text("STOP") }
        }

        StatusCard(status, showDataUsage = config.showDataUsage, onResetUsage = vm::resetUsage)

        Text("Trajectory (last ~1 min)", style = MaterialTheme.typography.labelLarge)
        TrajectoryMap(status.trajectory)

        SupportButton()
    }
}

// Genuine donation (no goods in return) -> external link is Play-policy OK. Replace with your ID.
private const val DONATE_URL = "https://ko-fi.com/onlyti"

@Composable
private fun SupportButton() {
    val ctx = LocalContext.current
    OutlinedButton(
        onClick = {
            runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(DONATE_URL)))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("☕  Support on Ko-fi") }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfilesSection(vm: RtkViewModel, config: RtkConfig) {
    Text("Casters (multi-network hot-standby)", style = MaterialTheme.typography.titleMedium)

    // Preset quick-add combobox (per country).
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "+ add preset caster", onValueChange = {}, readOnly = true,
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

    // Compact: collapsed one-liner per caster; the selected one expands for editing.
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
                        TextButton(onClick = { vm.removeProfile(i) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("✕") }
                    }
                }
                if (isActive) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        OutlinedTextField(
                            value = p.host, onValueChange = { vm.setHost(i, it) },
                            label = { Text("host") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = p.port.toString(), onValueChange = { vm.setPort(i, it) },
                                label = { Text("port") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = p.preferredMount, onValueChange = { vm.setMount(i, it) },
                                label = { Text("mount (blank=AUTO)") }, singleLine = true, modifier = Modifier.weight(2f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = p.user, onValueChange = { vm.setUser(i, it) },
                                label = { Text("user") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = p.pass, onValueChange = { vm.setPass(i, it) },
                                label = { Text("pass") }, singleLine = true,
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
private fun ScanSection(vm: RtkViewModel) {
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
                Text("${s.entries.size} mountpoints (tap to select)", style = MaterialTheme.typography.labelMedium)
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
private fun TrajectoryMap(points: List<GeoPt>) {
    val ctx = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().userAgentValue = ctx.packageName
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(18.0)
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }
    AndroidView(
        factory = { mapView },
        // clipToBounds so the native MapView never overdraws into the status card above.
        modifier = Modifier.fillMaxWidth().height(240.dp).clipToBounds(),
        update = { map ->
            map.overlays.clear()
            if (points.isNotEmpty()) {
                val geo = points.map { GeoPoint(it.lat, it.lon) }
                map.overlays.add(Polyline().apply { setPoints(geo); outlinePaint.strokeWidth = 8f })
                map.overlays.add(Marker(map).apply { position = geo.last() })
                map.controller.setCenter(geo.last())
            }
            map.invalidate()
        },
    )
}

@Composable
private fun StatusCard(s: RtkStatus, showDataUsage: Boolean, onResetUsage: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            line("NTRIP", if (s.ntripConnected) "connected" else "down")
            line("Serial", if (s.serialConnected) s.deviceName else "down")
            line("Provider/Mount", "${s.activeProfileName} / ${s.activeMount.ifBlank { "-" }}")
            line("Mode / GGA", "${s.activeMode.ifBlank { "-" }} / ${if (s.ggaActive) "on" else "off"}")
            line("Failover", s.failoverLevel)
            if (s.streamCount > 0) {
                line("Bases", "${s.healthyCount}/${s.streamCount} healthy")
                for (l in s.streamLines) {     // one line per base: ▶active, distance km, ok/stale/down
                    Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            val fix = if (s.lastFixQuality >= 0)
                com.onlyti.rtkrouter.gnss.Nmea.fixQualityLabel(s.lastFixQuality) else "-"
            line("Fix", fix)

            // Receiver (u-blox) parsed NMEA.
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
                Text(
                    s.lastNmea,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
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
