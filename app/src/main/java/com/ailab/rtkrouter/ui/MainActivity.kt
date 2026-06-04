package com.ailab.rtkrouter.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ailab.rtkrouter.config.EndpointMode
import com.ailab.rtkrouter.config.RtkConfig
import com.ailab.rtkrouter.service.RtkStatus

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

    private fun requiredPermissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return p.toTypedArray()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RtkScreen(vm: RtkViewModel, onStart: () -> Unit) {
    val config: RtkConfig by vm.config.collectAsState()
    val status: RtkStatus by vm.status.collectAsState()
    val profile = config.activeProfile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("RTK Router", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = profile.host, onValueChange = vm::setHost,
            label = { Text("Caster host") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = profile.port.toString(), onValueChange = vm::setPort,
                label = { Text("Port") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = profile.preferredMount, onValueChange = vm::setMount,
                label = { Text("Mount (blank=AUTO)") }, singleLine = true,
                modifier = Modifier.weight(2f),
            )
        }

        ScanSection(vm)
        OutlinedTextField(
            value = profile.user, onValueChange = vm::setUser,
            label = { Text("User") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = profile.pass, onValueChange = vm::setPass,
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Baud", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in listOf(57600, 115200, 230400, 460800)) {
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

        // VRS mounts require GGA upload; AUTO sets this automatically, MANUAL needs it forced.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Send GGA (VRS)", style = MaterialTheme.typography.labelLarge)
            Switch(checked = config.sendGga, onCheckedChange = vm::setSendGga)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart, enabled = !status.running) { Text("START") }
            Button(onClick = vm::stop, enabled = status.running) { Text("STOP") }
        }

        StatusCard(status, showDataUsage = config.showDataUsage)
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
private fun StatusCard(s: RtkStatus, showDataUsage: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            line("NTRIP", if (s.ntripConnected) "connected" else "down")
            line("Serial", if (s.serialConnected) "${s.deviceName}" else "down")
            line("Provider/Mount", "${s.activeProfileName} / ${s.activeMount.ifBlank { "-" }}")
            line("Mode / GGA", "${s.activeMode.ifBlank { "-" }} / ${if (s.ggaActive) "on" else "off"}")
            line("Failover", s.failoverLevel)
            val fix = if (s.lastFixQuality >= 0)
                com.ailab.rtkrouter.gnss.Nmea.fixQualityLabel(s.lastFixQuality) else "-"
            line("Fix", fix)

            // Receiver (u-blox) parsed NMEA.
            if (s.lastFixQuality >= 0) {
                line("Rx pos", if (s.rxLat.isNaN()) "-" else String.format("%.7f, %.7f", s.rxLat, s.rxLon))
                line("Rx sats / HDOP", "${s.rxSats} / ${if (s.rxHdop.isNaN()) "-" else String.format("%.1f", s.rxHdop)}")
                line("Rx alt", if (s.rxAltM.isNaN()) "-" else String.format("%.1f m", s.rxAltM))
            }
            if (showDataUsage) {
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
