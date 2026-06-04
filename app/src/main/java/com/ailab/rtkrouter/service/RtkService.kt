package com.ailab.rtkrouter.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ailab.rtkrouter.RtkApp
import com.ailab.rtkrouter.config.EndpointMode
import com.ailab.rtkrouter.config.RtkConfig
import com.ailab.rtkrouter.gnss.LocationHub
import com.ailab.rtkrouter.gnss.Nmea
import com.ailab.rtkrouter.ntrip.NtripClient
import com.ailab.rtkrouter.ntrip.StrEntry
import com.ailab.rtkrouter.ntrip.rtcmFormatRank
import com.ailab.rtkrouter.serial.SerialLink
import com.ailab.rtkrouter.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
    private var ntrip: NtripClient? = null

    private val sessionRx = AtomicLong(0)   // RTCM bytes from caster
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
        val profile = config.activeProfile
        Log.d(TAG, "resolve: host=${profile.host}:${profile.port} mode=${config.endpointMode} mount='${profile.preferredMount}'")
        if (profile.host.isBlank()) {
            error = ErrorInfo("Config", "L0", "caster host empty")
            return
        }

        var entries: List<StrEntry> = emptyList()
        if (config.endpointMode != EndpointMode.MANUAL) {
            val res = NtripClient.fetchSourcetable(profile)
            res.onSuccess {
                entries = it
                Log.d(TAG, "sourcetable: ${it.size} entries, vrs=${it.count { e -> e.requiresGga }}")
            }
            res.onFailure {
                Log.w(TAG, "sourcetable fetch failed: ${it.message}", it)
                error = ErrorInfo("CasterDown", "L0", "sourcetable: ${it.message}")
                // No explicit mount to fall back on -> stop and surface the real reason.
                if (profile.preferredMount.isBlank()) return
            }
        }

        val (mount, requiresGga, mode) = chooseMount(profile.preferredMount, entries)
        if (mount.isBlank()) {
            val why = if (entries.isEmpty()) "sourcetable empty/failed" else "${entries.size} mounts, none selectable"
            error = ErrorInfo("Config", "L0", "no mount: $why")
            Log.w(TAG, "no mount resolved ($why)")
            return
        }
        resolvedMount = mount
        resolvedMode = mode
        ggaActive = requiresGga || config.sendGga
        Log.d(TAG, "resolved mount=$mount mode=$mode gga=$ggaActive")

        startNtripStream(mount)
    }

    /** Returns (mount, requiresGga, modeLabel). */
    private fun chooseMount(preferred: String, entries: List<StrEntry>): Triple<String, Boolean, String> {
        // MANUAL or explicit preferred mount wins.
        if (config.endpointMode == EndpointMode.MANUAL || preferred.isNotBlank()) {
            val e = entries.firstOrNull { it.mount == preferred }
            return Triple(preferred, e?.requiresGga ?: config.sendGga, "MANUAL")
        }
        if (entries.isEmpty()) return Triple("", false, "")

        if (config.endpointMode == EndpointMode.AUTO) {
            // Prefer the highest-ranked RTCM3 VRS (3.2 > generic > 3.1); F9P/OEM7 cannot
            // use CMR/CMR+, and some casters' RTCM 3.1 VRS serves no data.
            val vrsList = entries.filter { it.requiresGga }
            val vrs = vrsList.filter { rtcmFormatRank(it.format) >= 0 }
                .maxByOrNull { rtcmFormatRank(it.format) }
                ?: vrsList.firstOrNull()
            if (vrs != null) return Triple(vrs.mount, true, "VRS")
        }
        // NEAREST (or AUTO with no VRS): pick closest fixed station by phone GPS.
        val loc = location.lastLocation()
        val fixed = entries.filter { !it.requiresGga && !it.lat.isNaN() && !it.lon.isNaN() }
        val pick = if (loc != null && fixed.isNotEmpty()) {
            fixed.minByOrNull { haversineKm(loc.latitude, loc.longitude, it.lat, it.lon) }
        } else {
            entries.firstOrNull()
        }
        return Triple(pick?.mount ?: "", pick?.requiresGga ?: false, "NEAREST")
    }

    private fun startNtripStream(mount: String) {
        ntrip?.stop()
        ntrip = NtripClient(
            profile = config.activeProfile,
            mount = mount,
            onRtcm = { buf, n ->
                sessionRx.addAndGet(n.toLong())
                lastRtcmAtMs = System.currentTimeMillis()
                serial?.write(buf, n)
            },
            onState = { connected, d -> ntripConnected = connected; detail = d },
            ggaProvider = { buildGga() },
            sendGga = ggaActive,
        ).also { it.start() }
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

            // byte watchdog (DESIGN.md 3.3 failure #4): connected but no RTCM.
            if (ntripConnected && lastRtcmAtMs > 0 &&
                now - lastRtcmAtMs > config.failover.deadTimeoutSec * 1000L
            ) {
                error = ErrorInfo("NoRtcmData", "L0", "connected but RTCM stalled ${config.failover.deadTimeoutSec}s")
                if (config.autoReconnect) {
                    detail = "watchdog: reconnecting $resolvedMount"
                    lastRtcmAtMs = now
                    startNtripStream(resolvedMount)
                }
            }

            RtkState.update(
                RtkStatus(
                    running = true,
                    ntripConnected = ntripConnected,
                    serialConnected = serialConnected,
                    deviceName = serialDeviceName,
                    serialPortCount = serialPortCount,
                    serialPortIndex = config.serialPortIndex,
                    activeProfileName = config.activeProfile.name,
                    activeMount = resolvedMount,
                    activeMode = resolvedMode,
                    ggaActive = ggaActive,
                    failoverLevel = "L0",
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
        ntrip?.stop()
        serial?.close()
        if (this::location.isInitialized) location.stop()
        RtkState.reset()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ailab.rtkrouter.START"
        const val ACTION_STOP = "com.ailab.rtkrouter.STOP"
        const val EXTRA_CONFIG = "config"
        private const val TAG = "rtk"
        private const val NOTIF_ID = 42
        private const val RATE_WINDOW_MS = 4000L
        private const val NMEA_BUF_CAP = 4096

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
