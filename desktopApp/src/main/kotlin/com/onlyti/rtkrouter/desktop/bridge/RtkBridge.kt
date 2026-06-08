package com.onlyti.rtkrouter.desktop.bridge

import com.onlyti.rtkrouter.desktop.config.CasterProfile
import com.onlyti.rtkrouter.desktop.config.EndpointMode
import com.onlyti.rtkrouter.desktop.config.RtkConfig
import com.onlyti.rtkrouter.desktop.gnss.Nmea
import com.onlyti.rtkrouter.desktop.ntrip.NtripClient
import com.onlyti.rtkrouter.desktop.ntrip.StrEntry
import com.onlyti.rtkrouter.desktop.ntrip.rtcmFormatRank
import com.onlyti.rtkrouter.desktop.serial.SerialLink
import com.onlyti.rtkrouter.desktop.service.ErrorInfo
import com.onlyti.rtkrouter.desktop.service.GeoPt
import com.onlyti.rtkrouter.desktop.service.RtkState
import com.onlyti.rtkrouter.desktop.service.RtkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Desktop bridge: NTRIP caster -> serial -> GNSS receiver (Android RtkService port). */
class RtkBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusJob: Job? = null

    private var config: RtkConfig = RtkConfig()
    private var serialDevicePath: String = ""
    private var serial: SerialLink? = null

    private class BaseStream(
        val id: Int,
        val profile: CasterProfile,
        val mount: String,
        val distanceKm: Double,
        requiresGga: Boolean,
    ) {
        @Volatile var client: NtripClient? = null
        @Volatile var connected = false
        @Volatile var connectedAtMs = 0L
        @Volatile var lastDataMs = 0L
        @Volatile var nextRetryMs = 0L
        @Volatile var retryCount = 0
        @Volatile var ggaActive = requiresGga
        val rxBytes = AtomicLong(0)
    }

    @Volatile private var streams: List<BaseStream> = emptyList()
    @Volatile private var activeStreamId = -1

    private val sessionRx = AtomicLong(0)
    private val sessionTx = AtomicLong(0)
    @Volatile private var lastRtcmAtMs = 0L
    @Volatile private var startedAtMs = 0L
    @Volatile private var lastFixQuality = -1
    @Volatile private var rxFix: Nmea.GgaFix? = null
    @Volatile private var lastNmea = ""
    private val rateWindow = ArrayDeque<Pair<Long, Long>>()
    private val track = ArrayDeque<Pair<Long, GeoPt>>()

    @Volatile private var resolvedMount = ""
    @Volatile private var resolvedMode = ""
    @Volatile private var ggaActive = false
    @Volatile private var ntripConnected = false
    @Volatile private var healthyCount = 0
    @Volatile private var streamLines: List<String> = emptyList()
    @Volatile private var failoverLevel = "L0"
    @Volatile private var activeProfileName = ""
    @Volatile private var serialConnected = false
    @Volatile private var serialDeviceName = ""
    @Volatile private var detail = "starting"
    @Volatile private var error = ErrorInfo()

    fun start(cfg: RtkConfig, devicePath: String) {
        stop()
        config = cfg
        serialDevicePath = devicePath
        startedAtMs = System.currentTimeMillis()
        detail = "starting"

        serial = SerialLink(
            devicePath = devicePath,
            baud = config.baud,
            onConnected = { name ->
                serialConnected = true
                serialDeviceName = name
                detail = "serial: $name @ ${config.baud}"
            },
            onDisconnected = { reason ->
                serialConnected = false
                error = ErrorInfo("Serial", "L0", reason)
                detail = reason
            },
            onData = { bytes -> onSerialData(bytes) },
        ).also { it.connect() }

        scope.launch(Dispatchers.IO) { resolveAndConnectNtrip() }
        statusJob = scope.launch { statusLoop() }
    }

    fun stop() {
        statusJob?.cancel()
        statusJob = null
        streams.forEach { it.client?.stop() }
        streams = emptyList()
        serial?.close()
        serial = null
        RtkState.reset()
    }

    fun resetUsage() {
        sessionRx.set(0)
        sessionTx.set(0)
        serial?.txBytes?.set(0)
        serial?.rxBytes?.set(0)
        rateWindow.clear()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun resolveAndConnectNtrip() {
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
            var entries: List<StrEntry> = emptyList()
            if (config.endpointMode != EndpointMode.MANUAL) {
                NtripClient.fetchSourcetable(profile)
                    .onSuccess { entries = it }
                    .onFailure {
                        if (profile.preferredMount.isBlank()) return@onFailure
                    }
            }
            val (targets, mode) = chooseTargets(profile, profile.preferredMount, entries)
            if (mode.isNotEmpty()) lastMode = mode
            all.addAll(targets)
        }

        if (all.isEmpty()) {
            error = ErrorInfo("Config", "L0", "no mount resolved on any caster")
            return
        }
        val capped = all.take(MAX_STREAMS)
        resolvedMode = if (profiles.size > 1) "MULTI/$lastMode" else lastMode
        resolvedMount = capped.first().mount

        val list = capped.mapIndexed { i, t -> BaseStream(i, t.profile, t.mount, t.distanceKm, t.requiresGga) }
        list.forEach { startStream(it) }
        streams = list
        activeStreamId = 0
    }

    private class StreamTarget(val profile: CasterProfile, val mount: String, val requiresGga: Boolean, val distanceKm: Double)

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
        val n = config.hotStandbyCount.coerceIn(1, 5)
        val picked = entries.filter { rtcmFormatRank(it.format) >= 0 }
            .sortedByDescending { rtcmFormatRank(it.format) }
            .take(n)
            .map { StreamTarget(profile, it.mount, it.requiresGga, Double.NaN) }
        return picked to "NEAREST"
    }

    private fun startStream(s: BaseStream) {
        s.client?.stop()
        s.client = NtripClient(
            profile = s.profile,
            mount = s.mount,
            onRtcm = { buf, n -> onStreamRtcm(s, buf, n) },
            onState = { connected, d ->
                s.connected = connected
                if (connected) { s.retryCount = 0; s.connectedAtMs = System.currentTimeMillis() }
                if (s.id == activeStreamId) detail = d
            },
            ggaProvider = { if (s.ggaActive) ggaForUpload() else null },
        ).also { it.start() }
    }

    private fun ggaForUpload(): String? {
        val raw = lastNmea
        if (lastFixQuality >= 1 && raw.startsWith("\$")) {
            val gga = if (raw.endsWith("\r\n")) raw else "$raw\r\n"
            sessionTx.addAndGet(gga.length.toLong())
            return gga
        }
        return null
    }

    private fun onStreamRtcm(s: BaseStream, buf: ByteArray, n: Int) {
        s.rxBytes.addAndGet(n.toLong())
        s.lastDataMs = System.currentTimeMillis()
        sessionRx.addAndGet(n.toLong())
        if (s.id == activeStreamId) {
            lastRtcmAtMs = s.lastDataMs
            serial?.write(buf, n)
        }
    }

    private val nmeaBuf = StringBuilder()

    private fun onSerialData(bytes: ByteArray) {
        synchronized(nmeaBuf) {
            nmeaBuf.append(String(bytes, Charsets.US_ASCII))
            if (nmeaBuf.length > NMEA_BUF_CAP) {
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
                    startStream(s)
                }
            }
        }

        val active = streams.find { it.id == activeStreamId }
        if (active == null || !healthy(active)) {
            val next = streams.firstOrNull { healthy(it) }
            if (next != null && next.id != activeStreamId) {
                activeStreamId = next.id
                resolvedMount = next.mount
            }
        } else {
            resolvedMount = active.mount
        }

        for (s in streams) {
            if (!s.ggaActive && s.connected && s.connectedAtMs > 0 &&
                s.rxBytes.get() == 0L && now - s.connectedAtMs > GGA_PROBE_MS
            ) {
                s.ggaActive = true
            }
        }

        val act = streams.find { it.id == activeStreamId }
        ntripConnected = streams.any { it.connected }
        healthyCount = streams.count { healthy(it) }
        ggaActive = act?.ggaActive ?: false
        activeProfileName = act?.profile?.name ?: ""
        failoverLevel = when {
            act == null || activeStreamId <= 0 -> "L0"
            streams.firstOrNull()?.profile?.name != act.profile.name -> "L2"
            else -> "L1"
        }
        streamLines = streams.map { s ->
            val star = if (s.id == activeStreamId) "▶ " else "   "
            val dist = if (s.distanceKm.isNaN()) "" else String.format(Locale.US, "  %.1f km", s.distanceKm)
            val st = if (healthy(s)) "ok" else if (s.connected) "stale" else "down"
            "$star${s.profile.name}/${s.mount}$dist  ·  $st"
        }
        if (healthyCount == 0 && now - startedAtMs > 5000) {
            error = ErrorInfo("NoRtcmData", failoverLevel, "no healthy base stream")
        }
    }

    private fun windowedRate(): Long {
        val w = rateWindow
        if (w.size < 2) return 0
        val (t0, b0) = w.first()
        val (t1, b1) = w.last()
        val dt = t1 - t0
        return if (dt > 0) (b1 - b0) * 1000 / dt else 0
    }

    private suspend fun statusLoop() {
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val rx = sessionRx.get()
            rateWindow.addLast(now to rx)
            while (rateWindow.size > 1 && now - rateWindow.first().first > RATE_WINDOW_MS) {
                rateWindow.removeFirst()
            }
            val rate = windowedRate()

            val rf = rxFix
            if (rf != null && !rf.lat.isNaN() && !rf.lon.isNaN() && rf.quality >= 1) {
                track.addLast(now to GeoPt(rf.lat, rf.lon))
                while (track.isNotEmpty() && now - track.first().first > TRACK_WINDOW_MS) track.removeFirst()
            }

            superviseStreams(now)

            RtkState.update(
                RtkStatus(
                    running = true,
                    ntripConnected = ntripConnected,
                    serialConnected = serialConnected,
                    deviceName = serialDeviceName,
                    serialPortCount = 1,
                    serialPortIndex = config.serialPortIndex,
                    activeProfileName = activeProfileName,
                    activeMount = resolvedMount,
                    activeMode = resolvedMode,
                    ggaActive = ggaActive,
                    failoverLevel = failoverLevel,
                    streamCount = streams.size,
                    healthyCount = healthyCount,
                    streamLines = streamLines,
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
                    trajectory = track.map { it.second },
                ),
            )
            delay(500)
        }
    }

    companion object {
        private const val RATE_WINDOW_MS = 4000L
        private const val NMEA_BUF_CAP = 4096
        private const val GGA_PROBE_MS = 6000L
        private const val MAX_STREAMS = 6
        private const val TRACK_WINDOW_MS = 60_000L
    }
}
