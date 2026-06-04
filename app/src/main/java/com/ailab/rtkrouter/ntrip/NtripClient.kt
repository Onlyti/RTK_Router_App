package com.ailab.rtkrouter.ntrip

import android.util.Base64
import com.ailab.rtkrouter.config.CasterProfile
import com.ailab.rtkrouter.config.NtripVersion
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** One sourcetable STR record (subset of NTRIP fields we care about). */
data class StrEntry(
    val mount: String,
    val identifier: String,
    val format: String,
    val lat: Double,
    val lon: Double,
    /** STR nmea flag: 1 => client must send GGA (VRS), 0 => fixed station. */
    val requiresGga: Boolean,
)

/**
 * Minimal NTRIP v1/v2 client over a raw socket. Handles the HTTP-like handshake,
 * sourcetable fetch, RTCM passthrough, and periodic GGA upload.
 *
 * Built fresh for rtk-router (data flows caster -> phone -> serial, the reverse of
 * phone-sensor-stream's transports) but mirrors their thread/lifecycle discipline.
 */
class NtripClient(
    private val profile: CasterProfile,
    private val mount: String,
    private val onRtcm: (ByteArray, Int) -> Unit,
    private val onState: (connected: Boolean, detail: String) -> Unit,
    /** Returns the latest GGA sentence to upload, or null to skip. */
    private val ggaProvider: () -> String?,
    private val sendGga: Boolean,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var ggaThread: Thread? = null
    @Volatile private var out: OutputStream? = null

    fun start() {
        if (running.getAndSet(true)) return
        worker = Thread({ run() }, "ntrip-$mount").also { it.start() }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        ggaThread?.interrupt()
        worker = null
        ggaThread = null
    }

    private fun run() {
        var sock: Socket? = null
        try {
            onState(false, "connecting ${profile.host}:${profile.port}/$mount")
            sock = Socket().apply { connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS) }
            sock.soTimeout = READ_TIMEOUT_MS
            val os = sock.getOutputStream()
            val ins = sock.getInputStream()
            out = os

            os.write(requestHeader(mount).toByteArray(Charsets.US_ASCII))
            os.flush()

            if (!readHandshakeOk(ins)) {
                onState(false, "handshake rejected")
                return
            }
            onState(true, "streaming /$mount")

            if (sendGga) startGgaUploader(os)

            val buf = ByteArray(4096)
            while (running.get()) {
                val n = try {
                    ins.read(buf)
                } catch (e: java.net.SocketTimeoutException) {
                    0 // no data this window; loop so the byte watchdog upstream can act
                }
                if (n < 0) {
                    onState(false, "stream closed by caster")
                    break
                }
                if (n > 0) onRtcm(buf, n)
            }
        } catch (t: Throwable) {
            onState(false, "error: ${t.message}")
        } finally {
            try { sock?.close() } catch (_: Throwable) {}
            out = null
        }
    }

    private fun startGgaUploader(os: OutputStream) {
        ggaThread = Thread({
            while (running.get()) {
                try {
                    val gga = ggaProvider()
                    if (gga != null) {
                        synchronized(os) {
                            os.write(gga.toByteArray(Charsets.US_ASCII))
                            os.flush()
                        }
                    }
                    Thread.sleep(GGA_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    break
                }
            }
        }, "ntrip-gga-$mount").also { it.start() }
    }

    private fun requestHeader(target: String): String {
        val auth = Base64.encodeToString(
            "${profile.user}:${profile.pass}".toByteArray(Charsets.US_ASCII),
            Base64.NO_WRAP,
        )
        val sb = StringBuilder()
        sb.append("GET /$target HTTP/1.1\r\n")
        sb.append("Host: ${profile.host}:${profile.port}\r\n")
        if (profile.ntripVersion == NtripVersion.V2) sb.append("Ntrip-Version: Ntrip/2.0\r\n")
        sb.append("User-Agent: NTRIP rtk-router/0.1\r\n")
        sb.append("Authorization: Basic $auth\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

    /** Read header lines until blank; accept on 200 / ICY 200 OK. */
    private fun readHandshakeOk(ins: InputStream): Boolean {
        val first = readLine(ins) ?: return false
        val ok = first.contains("200") || first.contains("ICY 200 OK", ignoreCase = true)
        // drain remaining header lines (v2) until blank line
        if (first.startsWith("HTTP", ignoreCase = true)) {
            while (true) {
                val l = readLine(ins) ?: break
                if (l.isEmpty()) break
            }
        }
        return ok
    }

    /** Read a single CRLF/LF-terminated line from a raw stream (no buffering ahead). */
    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 5000
        private const val GGA_INTERVAL_MS = 1000L

        /** Fetch and parse the caster sourcetable (GET /). Blocking; call off the main thread. */
        fun fetchSourcetable(profile: CasterProfile): Result<List<StrEntry>> = runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
                val auth = Base64.encodeToString(
                    "${profile.user}:${profile.pass}".toByteArray(Charsets.US_ASCII),
                    Base64.NO_WRAP,
                )
                val req = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: ${profile.host}:${profile.port}\r\n")
                    if (profile.ntripVersion == NtripVersion.V2) append("Ntrip-Version: Ntrip/2.0\r\n")
                    append("User-Agent: NTRIP rtk-router/0.1\r\n")
                    append("Authorization: Basic $auth\r\n")
                    append("Connection: close\r\n\r\n")
                }
                sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                sock.getOutputStream().flush()

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                val entries = ArrayList<StrEntry>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!
                    if (l.startsWith("ENDSOURCETABLE")) break
                    if (!l.startsWith("STR;")) continue
                    val f = l.split(';')
                    if (f.size < 12) continue
                    entries.add(
                        StrEntry(
                            mount = f[1],
                            identifier = f.getOrElse(2) { "" },
                            format = f.getOrElse(3) { "" },
                            lat = f.getOrElse(9) { "" }.toDoubleOrNull() ?: Double.NaN,
                            lon = f.getOrElse(10) { "" }.toDoubleOrNull() ?: Double.NaN,
                            requiresGga = f.getOrElse(11) { "0" }.trim() == "1",
                        )
                    )
                }
                entries
            }
        }
    }
}
