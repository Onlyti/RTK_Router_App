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
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Headless CLI entrypoint (Linux servers, no display). Reads the same settings.json the GUI
 * writes (~/.config/rtk-router/settings.json, or --config <path>), starts the NTRIP→output
 * bridge, and prints status to stdout until Ctrl-C. Make the config in the GUI once (it is
 * saved automatically) and copy it to the server, or hand-write the JSON.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun defaultConfigPath(): Path =
    Path.of(System.getProperty("user.home"), ".config", "rtk-router", "settings.json")

private fun printHelp() {
    println(
        """
        rtk-router (CLI) — NTRIP RTCM router, headless

        Usage: rtk-router-cli [--config <path>]

          -c, --config <path>   settings JSON (default: ${defaultConfigPath()})
          -h, --help            this help

        설정 JSON 은 GUI(rtk-router)에서 만들면 위 경로에 자동 저장된다. 헤드리스 서버에는
        그 파일을 복사하거나 직접 작성한다. 모드(connectionMode): RS232 / NOVATEL_USB / ROS_RTCM.
        ROS_RTCM 모드는 ROS 가 source 된 환경에서 실행할 것 (rospy/rtcm_msgs 필요).
        """.trimIndent(),
    )
}

fun main(args: Array<String>) {
    var configPath = defaultConfigPath()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--config", "-c" -> {
                if (i + 1 >= args.size) { System.err.println("--config 인자에 경로가 필요합니다"); return }
                configPath = Path.of(args[++i])
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

    val bridge = RtkBridge()
    Runtime.getRuntime().addShutdownHook(Thread {
        println("\n중지 중...")
        bridge.stop()
    })

    println("rtk-router CLI 시작 — config: $configPath, mode: $mode")
    bridge.start(settings.config, options)

    runBlocking {
        while (true) {
            println(statusLine(RtkState.current()))
            delay(2000)
        }
    }
}

private fun statusLine(s: RtkStatus): String {
    val sb = StringBuilder()
    sb.append("[").append(s.uptimeSec).append("s] ").append(s.healthLevel)
    sb.append(" | NTRIP=").append(if (s.ntripConnected) "up" else "down")
    if (s.activeMount.isNotBlank()) sb.append(" mount=").append(s.activeMount)
    sb.append(" rate=").append(s.rtcmBytesPerSec).append("B/s")
    if (s.rosActive) {
        sb.append(" | ROS=").append(if (s.rosNodeAlive) "run→${s.rosTopic}" else "stopped")
    } else {
        sb.append(" | serial=").append(if (s.serialConnected) s.deviceName else "down")
        if (s.lastFixQuality >= 0) sb.append(" fix=").append(Nmea.fixQualityLabel(s.lastFixQuality))
    }
    if (s.lastError.reason.isNotBlank()) {
        sb.append(" | ERR[").append(s.lastError.level).append("] ").append(s.lastError.reason)
    } else if (s.healthLevel != "ok" && s.healthMessage.isNotBlank()) {
        sb.append(" | ").append(s.healthMessage)
    }
    return sb.toString()
}
