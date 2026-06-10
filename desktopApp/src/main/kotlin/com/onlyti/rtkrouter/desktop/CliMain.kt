package com.onlyti.rtkrouter.desktop

import com.onlyti.rtkrouter.desktop.bridge.RosRtcmPublisher
import com.onlyti.rtkrouter.desktop.bridge.RtkBridge
import com.onlyti.rtkrouter.desktop.config.DesktopSettings
import com.onlyti.rtkrouter.desktop.config.SerialConnectOptions
import com.onlyti.rtkrouter.desktop.config.SerialConnectionMode
import com.onlyti.rtkrouter.desktop.gnss.Nmea
import com.onlyti.rtkrouter.desktop.service.RtkState
import com.onlyti.rtkrouter.desktop.service.RtkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Headless CLI entrypoint (Linux servers, no display). Reads the same settings.json the GUI
 * writes (~/.config/rtk-router/settings.json, or --config <path>), starts the NTRIP→output
 * bridge, and renders a live status dashboard until Ctrl-C. On a TTY it draws an auto-refreshing
 * panel (the same state the GUI shows: base candidates + health, chosen endpoint, data rate, GPS
 * fix); when piped/redirected (or with --plain) it logs one concise line per update.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private const val ESC = "\u001b"
private const val GREEN = "32"
private const val YELLOW = "33"
private const val RED = "31"
private const val DIM = "2"

private fun defaultConfigPath(): Path =
    Path.of(System.getProperty("user.home"), ".config", "rtk-router", "settings.json")

private fun printHelp() {
    println(
        """
        rtk-router (CLI) — NTRIP RTCM router, headless

        Usage: rtk-router-cli [--config <path>] [--plain] [--interval <sec>]

          -c, --config <path>   settings JSON (default: ${defaultConfigPath()})
              --plain           one line per update (no dashboard); for logs/pipes
              --interval <sec>  refresh seconds (default 1 dashboard / 2 plain)
          -h, --help            this help

        설정 JSON 은 GUI(rtk-router)에서 만들면 위 경로에 자동 저장된다. 헤드리스 서버에는
        그 파일을 복사하거나 직접 작성한다. connectionMode: RS232 / NOVATEL_USB / ROS_RTCM.
        ROS_RTCM 모드는 ROS 가 source 된 환경에서 실행할 것 (rospy/rtcm_msgs 필요).
        """.trimIndent(),
    )
}

fun main(args: Array<String>) {
    var configPath = defaultConfigPath()
    var forcePlain = false
    var interval = -1.0
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--config", "-c" -> {
                if (i + 1 >= args.size) { System.err.println("--config 인자에 경로가 필요합니다"); return }
                configPath = Path.of(args[++i])
            }
            "--plain" -> forcePlain = true
            "--interval" -> {
                if (i + 1 >= args.size) { System.err.println("--interval 인자에 초가 필요합니다"); return }
                interval = args[++i].toDoubleOrNull() ?: -1.0
            }
            "--help", "-h" -> { printHelp(); return }
            else -> { System.err.println("알 수 없는 인자: ${args[i]}"); printHelp(); return }
        }
        i++
    }

    if (!configPath.exists()) {
        System.err.println("설정 파일이 없습니다: $configPath")
        System.err.println("GUI(rtk-router)에서 설정 후 같은 경로에 저장하거나, 수동으로 JSON 을 만드세요. (--help)")
        return
    }
    val settings: DesktopSettings = try {
        json.decodeFromString(DesktopSettings.serializer(), configPath.readText())
    } catch (t: Throwable) {
        System.err.println("설정 파싱 실패: ${t.message}")
        return
    }

    val mode = settings.connectionMode
    if (mode == SerialConnectionMode.ROS_RTCM) {
        when (val pf = RosRtcmPublisher.preflight()) {
            RosRtcmPublisher.Preflight.Ok -> {}
            RosRtcmPublisher.Preflight.MissingRtcmMsgs -> {
                System.err.println("${RosRtcmPublisher.rtcmMsgsPackage()} 패키지가 없습니다.")
                System.err.println("설치: sudo apt install ${RosRtcmPublisher.rtcmMsgsPackage()}")
                return
            }
            RosRtcmPublisher.Preflight.NoRos -> {
                System.err.println("ROS 환경(rospy/python3)을 찾을 수 없습니다. ROS source 후 실행하세요.")
                return
            }
            is RosRtcmPublisher.Preflight.Error -> {
                System.err.println("ROS 사전 점검 실패: ${pf.message}")
                return
            }
        }
    } else if (settings.serialDevicePath.isBlank()) {
        System.err.println("시리얼 포트(serialDevicePath)가 비어 있습니다. 설정을 확인하세요.")
        return
    }

    val options = SerialConnectOptions(
        mode = mode,
        devicePath = if (mode == SerialConnectionMode.ROS_RTCM) "" else settings.serialDevicePath,
        baud = settings.config.baud,
    )

    val dashboard = !forcePlain && System.console() != null
    val periodMs = ((if (interval > 0) interval else if (dashboard) 1.0 else 2.0) * 1000).toLong()
    val headerLine = "config: $configPath    mode: $mode"

    val bridge = RtkBridge()
    Runtime.getRuntime().addShutdownHook(Thread {
        if (dashboard) print("$ESC[?25h$ESC[0m\n")  // restore cursor
        println("중지 중...")
        bridge.stop()
    })

    if (dashboard) print("$ESC[2J$ESC[?25l")  // clear + hide cursor
    else println("rtk-router CLI 시작 — $headerLine")
    bridge.start(settings.config, options)

    runBlocking {
        while (true) {
            val s = RtkState.current()
            if (dashboard) {
                print(renderFrame(s, headerLine))
                System.out.flush()
            } else {
                println(statusLine(s))
            }
            delay(periodMs)
        }
    }
}

// ---- dashboard (TTY) ----

private fun col(s: String, code: String): String = "$ESC[${code}m$s$ESC[0m"

private fun renderFrame(s: RtkStatus, headerLine: String): String {
    val L = ArrayList<String>()
    val rule = "─".repeat(66)
    val hcol = when (s.healthLevel) { "ok" -> GREEN; "warn" -> YELLOW; else -> RED }

    L.add(rule)
    L.add(" rtk-router CLI    up ${s.uptimeSec}s    health: ${col(s.healthLevel.uppercase(Locale.US), hcol)}")
    L.add(col(" $headerLine", DIM))
    L.add(rule)

    // NTRIP / endpoint
    L.add(
        " NTRIP    " + col(if (s.ntripConnected) "connected" else "down", if (s.ntripConnected) GREEN else RED) +
            "    selected: " + s.activeProfileName.ifBlank { "-" } + " / " + s.activeMount.ifBlank { "-" },
    )
    L.add(" endpoint ${s.activeMode.ifBlank { "-" }}    GGA ${if (s.ggaActive) "on" else "off"}    failover ${s.failoverLevel}")

    // base candidates + health
    L.add(" Bases (${s.healthyCount}/${s.streamCount} healthy)")
    if (s.streamLines.isEmpty()) {
        L.add(col("   (후보 없음 — 스캔/연결 대기)", DIM))
    } else {
        for (ln in s.streamLines) L.add("   " + colorBase(ln))
    }

    // data throughput
    L.add(
        " Data     RTCM ${s.rtcmBytesPerSec} B/s    Rx(caster) ${human(s.sessionRxBytes)}    " +
            "TxGGA ${human(s.sessionTxBytes)}",
    )

    // output target
    if (s.rosActive) {
        L.add(" Output   ROS node: " + col(if (s.rosNodeAlive) "running → ${s.rosTopic}" else "stopped", if (s.rosNodeAlive) GREEN else RED))
        if (s.rosNodeMessage.isNotBlank()) L.add(col("          ${s.rosNodeMessage.take(64)}", DIM))
    } else {
        L.add(
            " Output   serial: " + (if (s.serialConnected) col(s.deviceName, GREEN) else col("down", RED)) +
                "    Tx ${human(s.serialTxBytes)} / Rx ${human(s.serialRxBytes)}",
        )
    }

    // GPS / fix
    val fixLabel = if (s.lastFixQuality >= 0) Nmea.fixQualityLabel(s.lastFixQuality) else "-"
    val gps = StringBuilder(" GPS      ")
    gps.append(
        when (s.lastFixQuality) {
            4 -> col(fixLabel, GREEN)
            5 -> col(fixLabel, YELLOW)
            else -> fixLabel
        },
    )
    if (s.lastFixQuality >= 1 && !s.rxLat.isNaN() && !s.rxLon.isNaN()) {
        gps.append("  ").append(String.format(Locale.US, "%.7f, %.7f", s.rxLat, s.rxLon))
        gps.append("  sats ${s.rxSats}")
        if (!s.rxHdop.isNaN()) gps.append("  HDOP ").append(String.format(Locale.US, "%.1f", s.rxHdop))
        if (!s.rxAltM.isNaN()) gps.append("  alt ").append(String.format(Locale.US, "%.1fm", s.rxAltM))
    }
    L.add(gps.toString())
    if (s.lastNmea.isNotBlank()) L.add(col("          ${s.lastNmea.take(64)}", DIM))

    // detail / error
    if (s.detail.isNotBlank()) L.add(col(" detail   ${s.detail.take(64)}", DIM))
    if (s.lastError.reason.isNotBlank()) {
        L.add(col(" ! error  [${s.lastError.failureMode}/${s.lastError.level}] ${s.lastError.reason}", RED))
    } else if (s.healthLevel != "ok" && s.healthMessage.isNotBlank()) {
        L.add(col(" ! ${s.healthMessage}", YELLOW))
    }
    L.add(rule)

    val frame = StringBuilder("$ESC[H")
    for (ln in L) frame.append(ln).append("$ESC[K\n")
    frame.append("$ESC[J")  // clear anything below (shorter frame than previous)
    return frame.toString()
}

/** Colorize the trailing ok/stale/down health token of a base line. */
private fun colorBase(ln: String): String = when {
    ln.endsWith("ok") -> ln.dropLast(2) + col("ok", GREEN)
    ln.endsWith("stale") -> ln.dropLast(5) + col("stale", YELLOW)
    ln.endsWith("down") -> ln.dropLast(4) + col("down", RED)
    else -> ln
}

// ---- plain (pipe/log) ----

private fun statusLine(s: RtkStatus): String {
    val sb = StringBuilder()
    sb.append("[").append(s.uptimeSec).append("s] ").append(s.healthLevel)
    sb.append(" | NTRIP=").append(if (s.ntripConnected) "up" else "down")
    if (s.activeMount.isNotBlank()) sb.append(" mount=").append(s.activeMount)
    sb.append(" bases=").append(s.healthyCount).append("/").append(s.streamCount)
    sb.append(" rate=").append(s.rtcmBytesPerSec).append("B/s rx=").append(human(s.sessionRxBytes))
    if (s.rosActive) {
        sb.append(" | ROS=").append(if (s.rosNodeAlive) "run→${s.rosTopic}" else "stopped")
    } else {
        sb.append(" | serial=").append(if (s.serialConnected) s.deviceName else "down")
    }
    if (s.lastFixQuality >= 0) sb.append(" fix=").append(Nmea.fixQualityLabel(s.lastFixQuality))
    if (s.lastError.reason.isNotBlank()) {
        sb.append(" | ERR[").append(s.lastError.level).append("] ").append(s.lastError.reason)
    } else if (s.healthLevel != "ok" && s.healthMessage.isNotBlank()) {
        sb.append(" | ").append(s.healthMessage)
    }
    return sb.toString()
}

private fun human(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1fMB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.1fkB", bytes / 1_000.0)
    else -> "${bytes}B"
}
