package com.onlyti.rtkrouter.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.onlyti.rtkrouter.RtkApp
import com.onlyti.rtkrouter.config.CasterProfile
import com.onlyti.rtkrouter.config.EndpointMode
import com.onlyti.rtkrouter.config.RtkConfig
import com.onlyti.rtkrouter.gnss.LocationHub
import com.onlyti.rtkrouter.gnss.Nmea
import com.onlyti.rtkrouter.ntrip.NtripClient
import com.onlyti.rtkrouter.ntrip.StrEntry
import com.onlyti.rtkrouter.ntrip.rtcmFormatRank
import com.onlyti.rtkrouter.serial.SerialLink
import com.onlyti.rtkrouter.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Foreground bridge: NTRIP caster -> USB serial -> receiver, with GGA upload back.
 * Mirrors phone-sensor-stream's StreamService lifecycle/status-loop discipline.
 */
class RtkService : LifecycleService() {

    private val json = Json { ignoreUnknownKeys = true }
    private var config: RtkConfig = RtkConfig()

    private var serial: SerialLink? = null

    /** One hot-standby base stream (possibly on a different caster). Only the active
     *  stream is forwarded to serial. Spans multiple networks for whole-network failover. */
    private class BaseStream(
        val id: Int,
        val profile: CasterProfile,
        val mount: String,
        val distanceKm: Double,   // from phone GPS to this fixed base; NaN for VRS/unknown
        requiresGga: Boolean,
    ) {
        @Volatile var client: NtripClient? = null
        @Volatile var connected = false
        @Volatile var connectedAtMs = 0L
        @Volatile var lastDataMs = 0L
        @Volatile var nextRetryMs = 0L
        @Volatile var retryCount = 0
        @Volatile var ggaActive = requiresGga   // VRS from sourcetable; MANUAL auto-detects
        val rxBytes = AtomicLong(0)
    }

    // Built once on resolve (IO thread), then read by the status loop (Main). Immutable
    // list behind a volatile ref avoids concurrent-modification; per-stream state is volatile.
    @Volatile private var streams: List<BaseStream> = emptyList()
    @Volatile private var activeStreamId = -1

    private val sessionRx = AtomicLong(0)   // RTCM bytes from caster (all streams)
    private val sessionTx = AtomicLong(0)   // GGA bytes to caster
    @Volatile private var lastRtcmAtMs = 0L
    @Volatile private var startedAtMs = 0L
    @Volatile private var lastFixQuality = -1
    @Volatile private var rxFix: Nmea.GgaFix? = null
    @Volatile private var lastNmea = ""
    // moving window of (timeMs, cumulativeRxBytes) for a smoothed RTCM rate.
    private val rateWindow = ArrayDeque<Pair<Long, Long>>()

    @Volatile private var resolvedMount = ""
    @Volatile private var resolvedMode = ""
    @Volatile private var ggaActive = false
    @Volatile private var ntripConnected = false
    @Volatile private var healthyCount = 0
    @Volatile private var streamsInfo = ""
    @Volatile private var failoverLevel = "L0"
    @Volatile private var activeProfileName = ""
    @Volatile private var serialConnected = false
    @Volatile private var serialDeviceName = ""
    @Volatile private var serialPortCount = 0
    @Volatile private var detail = "starting"
    @Volatile private var error = ErrorInfo()

    private lateinit var location: LocationHub

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val cfgJson = intent.getStringExtra(EXTRA_CONFIG)
                config = cfgJson?.let { runCatching { json.decodeFromString(RtkConfig.serializer(), it) }.getOrNull() } ?: RtkConfig()
                startBridge()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startBridge() {
        startedAtMs = System.currentTimeMillis()
        startForegroundNotification()

        location = LocationHub(this).also { it.start() }

        serial = SerialLink(
            context = this,
            baud = config.baud,
            portIndex = config.serialPortIndex,
            onConnected = { name, portCount ->
                serialConnected = true; serialDeviceName = name; serialPortCount = portCount
                detail = "serial: $name (port ${config.serialPortIndex}/$portCount)"
            },
            onDisconnected = { reason ->
                serialConnected = false
                error = ErrorInfo("Serial", "L0", reason)
                detail = reason
            },
            onData = { bytes -> onSerialData(bytes) },
        ).also { it.connect() }

        lifecycleScope.launch(Dispatchers.IO) { resolveAndConnectNtrip() }
        lifecycleScope.launch { statusLoop() }
    }

    private suspend fun resolveAndConnectNtrip() {
        // Multi-network: resolve targets across ALL enabled profiles, by priority. Streams
        // from different casters run in parallel (hot-standby) so a whole-network outage
        // fails over to an already-warm stream on another network.
        val profiles = config.profiles
            .filter { it.enabled && it.host.isNotBlank() }
            .sortedBy { it.priority }
        if (profiles.isEmpty()) {
            error = ErrorInfo("Config", "L0", "no enabled caster with host")
            return
        }

        val all = ArrayList<StreamTarget>()
        var lastMode = ""
        for (profile in profiles) {
            Log.d(TAG, "resolve ${profile.name} ${profile.host}:${profile.port} mode=${config.endpointMode}")
            var entries: List<StrEntry> = emptyList()
            if (config.endpointMode != EndpointMode.MANUAL) {
                NtripClient.fetchSourcetable(profile)
                    .onSuccess { entries = it; Log.d(TAG, "${profile.name}: ${it.size} mounts, vrs=${it.count { e -> e.requiresGga }}") }
                    .onFailure {
                        Log.w(TAG, "${profile.name} sourcetable failed: ${it.message}")
                        if (profile.preferredMount.isBlank()) return@onFailure
                    }
            }
            val (targets, mode) = chooseTargets(profile, profile.preferredMount, entries)
            if (mode.isNotEmpty()) lastMode = mode
            all.addAll(targets)
        }

        if (all.isEmpty()) {
            error = ErrorInfo("Config", "L0", "no mount resolved on any caster")
            Log.w(TAG, "no mount resolved across ${profiles.size} profile(s)")
            return
        }
        val capped = all.take(MAX_STREAMS)
        resolvedMode = if (profiles.size > 1) "MULTI/$lastMode" else lastMode
        resolvedMount = capped.first().mount
        Log.d(TAG, "resolved ${capped.size} stream(s): ${capped.joinToString { "${it.profile.name}/${it.mount}" }}")

        val list = capped.mapIndexed { i, t -> BaseStream(i, t.profile, t.mount, t.distanceKm, t.requiresGga) }
        list.forEach { startStream(it) }
        streams = list
        activeStreamId = 0
    }

    private class StreamTarget(val profile: CasterProfile, val mount: String, val requiresGga: Boolean, val distanceKm: Double)

    /** Per-profile: VRS -> single; fixed -> top-N nearest (distance attached). */
    private fun chooseTargets(profile: CasterProfile, preferred: String, entries: List<StrEntry>): Pair<List<StreamTarget>, String> {
        if (config.endpointMode == EndpointMode.MANUAL || preferred.isNotBlank()) {
            val e = entries.firstOrNull { it.mount == preferred }
            return listOf(StreamTarget(profile, preferred, e?.requiresGga ?: false, Double.NaN)) to "MANUAL"
        }
        if (entries.isEmpty()) return emptyList<StreamTarget>() to ""

        if (config.endpointMode == EndpointMode.AUTO) {
            val vrsList = entries.filter { it.requiresGga }
            val vrs = vrsList.filter { rtcmFormatRank(it.format) >= 0 }
                .maxByOrNull { rtcmFormatRank(it.format) }
                ?: vrsList.firstOrNull()
            if (vrs != null) return listOf(StreamTarget(profile, vrs.mount, true, Double.NaN)) to "VRS"
        }
        val loc = location.lastLocation()
        val fixed = entries.filter {
            !it.requiresGga && rtcmFormatRank(it.format) >= 0 && !it.lat.isNaN() && !it.lon.isNaN()
        }
        val n = config.hotStandbyCount.coerceIn(1, 5)
        val picked = if (loc != null && fixed.isNotEmpty()) {
            fixed.map { it to haversineKm(loc.latitude, loc.longitude, it.lat, it.lon) }
                .sortedBy { it.second }.take(n)
                .map { StreamTarget(profile, it.first.mount, it.first.requiresGga, it.second) }
        } else {
            entries.take(n).map { StreamTarget(profile, it.mount, it.requiresGga, Double.NaN) }
        }
        return picked to "NEAREST"
    }

    private fun startStream(s: BaseStream) {
        s.client?.stop()
        s.client = NtripClient(
            profile = s.profile,                          // each stream on its own caster
            mount = s.mount,
            onRtcm = { buf, n -> onStreamRtcm(s, buf, n) },
            onState = { connected, d ->
                s.connected = connected
                if (connected) { s.retryCount = 0; s.connectedAtMs = System.currentTimeMillis() }
                if (s.id == activeStreamId) detail = d
            },
            ggaProvider = { if (s.ggaActive) buildGga() else null },
        ).also { it.start() }
    }

    private fun onStreamRtcm(s: BaseStream, buf: ByteArray, n: Int) {
        s.rxBytes.addAndGet(n.toLong())
        s.lastDataMs = System.currentTimeMillis()
        sessionRx.addAndGet(n.toLong())          // total cellular usage (all streams)
        if (s.id == activeStreamId) {            // forward only the active stream
            lastRtcmAtMs = s.lastDataMs
            serial?.write(buf, n)
        }
    }

    private fun buildGga(): String? {
        val loc = location.lastLocation() ?: return null
        val secOfDay = ((System.currentTimeMillis() / 1000L) % 86400L).toDouble()
        val gga = Nmea.buildGga(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 0.0, secOfDay)
        sessionTx.addAndGet(gga.length.toLong())
        return gga
    }

    private val nmeaBuf = StringBuilder()

    private fun onSerialData(bytes: ByteArray) {
        // Receiver streams NMEA in arbitrary USB chunk boundaries; a GGA can straddle
        // two callbacks. Accumulate and only parse complete '\n'-terminated lines.
        // Binary-tolerant (NovAtel may interleave OEM7 binary); non-GGA lines ignored.
        synchronized(nmeaBuf) {
            nmeaBuf.append(String(bytes, Charsets.US_ASCII))
            if (nmeaBuf.length > NMEA_BUF_CAP) {            // bound against binary spew / no newline
                nmeaBuf.delete(0, nmeaBuf.length - NMEA_BUF_CAP)
            }
            var nl = nmeaBuf.indexOf("\n")
            while (nl >= 0) {
                val line = nmeaBuf.substring(0, nl)
                nmeaBuf.delete(0, nl + 1)
                Nmea.parseGga(line)?.let { gga ->
                    lastFixQuality = gga.quality
                    rxFix = gga
                    lastNmea = line.trim()
                }
                nl = nmeaBuf.indexOf("\n")
            }
        }
    }

    /**
     * Hot-standby supervisor: keep all streams warm (auto-reconnect with backoff),
     * forward only the active stream, switch to the nearest healthy one if active dies
     * (make-before-break — the backup is already flowing, so no network gap).
     */
    private fun superviseStreams(now: Long) {
        if (streams.isEmpty()) return
        val deadMs = config.failover.deadTimeoutSec * 1000L
        fun healthy(s: BaseStream) = s.connected && s.lastDataMs > 0 && now - s.lastDataMs < deadMs

        if (config.autoReconnect) {
            for (s in streams) {
                if (!s.connected && now >= s.nextRetryMs) {
                    val backoff = minOf(1L shl s.retryCount.coerceIn(0, 5), config.failover.backoffMaxSec.toLong()) * 1000L
                    s.nextRetryMs = now + backoff
                    s.retryCount++
                    Log.d(TAG, "stream ${s.id} ${s.mount} reconnect (retry ${s.retryCount})")
                    startStream(s)
                }
            }
        }

        val active = streams.find { it.id == activeStreamId }
        if (active == null || !healthy(active)) {
            val next = streams.firstOrNull { healthy(it) }   // streams in nearest order
            if (next != null && next.id != activeStreamId) {
                Log.d(TAG, "active switch $activeStreamId -> ${next.id} (${next.mount})")
                activeStreamId = next.id
                resolvedMount = next.mount
            }
        } else {
            resolvedMount = active.mount
        }

        // VRS auto-detect, per stream: a mount that connects but stays silent is almost
        // certainly a VRS waiting for GGA. If we have a position, enable GGA — no toggle.
        if (location.lastLocation() != null) {
            for (s in streams) {
                if (!s.ggaActive && s.connected && s.connectedAtMs > 0 &&
                    s.rxBytes.get() == 0L && now - s.connectedAtMs > GGA_PROBE_MS
                ) {
                    s.ggaActive = true
                    Log.d(TAG, "VRS auto-detected on ${s.profile.name}/${s.mount}: enabling GGA")
                }
            }
        }

        val act = streams.find { it.id == activeStreamId }
        ntripConnected = streams.any { it.connected }
        healthyCount = streams.count { healthy(it) }
        ggaActive = act?.ggaActive ?: false
        activeProfileName = act?.profile?.name ?: ""
        // L0 = primary, L1 = backup base same net, L2 = on a different (backup) network.
        failoverLevel = when {
            act == null || activeStreamId <= 0 -> "L0"
            streams.firstOrNull()?.profile?.name != act.profile.name -> "L2"
            else -> "L1"
        }
        streamsInfo = streams.joinToString(" | ") { s ->
            val star = if (s.id == activeStreamId) "*" else ""
            val dist = if (s.distanceKm.isNaN()) "" else String.format(Locale.US, " %.1fkm", s.distanceKm)
            val st = if (healthy(s)) "ok" else if (s.connected) "stale" else "down"
            "$star${s.profile.name}/${s.mount}$dist $st"
        }
        if (healthyCount == 0 && now - startedAtMs > 5000) {
            error = ErrorInfo("NoRtcmData", failoverLevel, "no healthy base stream")
        }
    }

    /** Smoothed RTCM rate (B/s) over the sample window. */
    private fun windowedRate(): Long {
        val w = rateWindow
        if (w.size < 2) return 0
        val (t0, b0) = w.first()
        val (t1, b1) = w.last()
        val dt = t1 - t0
        return if (dt > 0) (b1 - b0) * 1000 / dt else 0
    }

    private suspend fun statusLoop() {
        while (lifecycleScope.isActive) {
            val now = System.currentTimeMillis()
            val rx = sessionRx.get()
            // maintain a ~4s moving window for a smooth rate (VRS arrives in ~1Hz bursts).
            rateWindow.addLast(now to rx)
            while (rateWindow.size > 1 && now - rateWindow.first().first > RATE_WINDOW_MS) {
                rateWindow.removeFirst()
            }
            val rate = windowedRate()

            superviseStreams(now)

            RtkState.update(
                RtkStatus(
                    running = true,
                    ntripConnected = ntripConnected,
                    serialConnected = serialConnected,
                    deviceName = serialDeviceName,
                    serialPortCount = serialPortCount,
                    serialPortIndex = config.serialPortIndex,
                    activeProfileName = activeProfileName,
                    activeMount = resolvedMount,
                    activeMode = resolvedMode,
                    ggaActive = ggaActive,
                    failoverLevel = failoverLevel,
                    streamCount = streams.size,
                    healthyCount = healthyCount,
                    streamsInfo = streamsInfo,
                    rtcmBytesPerSec = if (rate < 0) 0 else rate,
                    sessionRxBytes = rx,
                    sessionTxBytes = sessionTx.get(),
                    serialTxBytes = serial?.txBytes?.get() ?: 0,
                    serialRxBytes = serial?.rxBytes?.get() ?: 0,
                    lastFixQuality = lastFixQuality,
                    lastError = error,
                    uptimeSec = (now - startedAtMs) / 1000,
                    detail = detail,
                    rxLat = rxFix?.lat ?: Double.NaN,
                    rxLon = rxFix?.lon ?: Double.NaN,
                    rxSats = rxFix?.satellites ?: 0,
                    rxHdop = rxFix?.hdop ?: Double.NaN,
                    rxAltM = rxFix?.altMeters ?: Double.NaN,
                    lastNmea = lastNmea,
                )
            )
            kotlinx.coroutines.delay(500)
        }
    }

    private fun startForegroundNotification() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, RtkApp.NOTIF_CHANNEL_ID)
            .setContentTitle("RTK Router")
            .setContentText("Routing NTRIP corrections to receiver")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    override fun onDestroy() {
        streams.forEach { it.client?.stop() }
        streams = emptyList()
        serial?.close()
        if (this::location.isInitialized) location.stop()
        RtkState.reset()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.onlyti.rtkrouter.START"
        const val ACTION_STOP = "com.onlyti.rtkrouter.STOP"
        const val EXTRA_CONFIG = "config"
        private const val TAG = "rtk"
        private const val NOTIF_ID = 42
        private const val RATE_WINDOW_MS = 4000L
        private const val NMEA_BUF_CAP = 4096
        private const val GGA_PROBE_MS = 6000L   // silent-stream window before assuming VRS
        private const val MAX_STREAMS = 6        // cap across all networks

        private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
