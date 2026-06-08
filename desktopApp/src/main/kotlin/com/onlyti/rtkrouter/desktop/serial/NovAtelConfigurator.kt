package com.onlyti.rtkrouter.desktop.serial

import com.fazecast.jSerialComm.SerialPort
import com.onlyti.rtkrouter.desktop.gnss.Nmea

sealed class NovAtelConfigResult {
    data object Ok : NovAtelConfigResult()
    data class Failed(val message: String) : NovAtelConfigResult()
}

/**
 * Configure a NovAtel USB port for RTCM on START (no SAVECONFIG).
 * Same port: command mode -> INTERFACEMODE RTCM -> stream RTCM + read GGA.
 */
object NovAtelConfigurator {
    const val CONFIG_BAUD = 9600
    const val CMD_TIMEOUT_MS = 4_000L
    const val GGA_WAIT_MS = 20_000L

    fun configure(devicePath: String, usbIndex: Int): NovAtelConfigResult {
        if (usbIndex < 1) {
            return NovAtelConfigResult.Failed("invalid NovAtel USB index")
        }
        val p = SerialPort.getCommPort(devicePath)
        p.setComPortParameters(CONFIG_BAUD, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 200, 0)
        if (!p.openPort()) {
            return NovAtelConfigResult.Failed(
                "$devicePath 를 열 수 없습니다 (다른 앱이 사용 중이거나 권한 없음)",
            )
        }
        return try {
            drain(p)
            val usb = "USB$usbIndex"
            if (!sendAndWaitOk(p, "LOG GPGGA ONTIME 1")) {
                return NovAtelConfigResult.Failed(
                    "NovAtel LOG GPGGA 실패 — 설정이 안 되었거나 포트가 명령 모드가 아닙니다 ($usb)",
                )
            }
            if (!sendAndWaitOk(p, "INTERFACEMODE $usb NOVATEL RTCM ON")) {
                return NovAtelConfigResult.Failed(
                    "NovAtel INTERFACEMODE RTCM 실패 — $usb 설정 확인",
                )
            }
            when (waitForGga(p, GGA_WAIT_MS)) {
                GgaWait.Ok -> NovAtelConfigResult.Ok
                GgaWait.Timeout -> NovAtelConfigResult.Failed(
                    "GGA 미수신 (${GGA_WAIT_MS / 1000}s) — NovAtel 설정 문제이거나 GNSS가 아직 신호를 잡지 못했습니다",
                )
            }
        } finally {
            try { p.closePort() } catch (_: Throwable) {}
        }
    }

    private sealed interface GgaWait {
        data object Ok : GgaWait
        data object Timeout : GgaWait
    }

    private fun sendAndWaitOk(port: SerialPort, command: String): Boolean {
        val line = if (command.startsWith("#")) command else command.trim()
        val payload = "$line\r\n"
        port.writeBytes(payload.toByteArray(Charsets.US_ASCII), payload.length)
        val deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS
        val buf = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val chunk = readChunk(port)
            if (chunk.isNotEmpty()) {
                buf.append(chunk)
                val text = buf.toString()
                if (text.contains("<OK", ignoreCase = true) || Regex("""\bOK\b""").containsMatchIn(text)) {
                    return true
                }
                if (text.contains("ERROR", ignoreCase = true)) return false
            } else {
                Thread.sleep(20)
            }
        }
        return false
    }

    private fun waitForGga(port: SerialPort, timeoutMs: Long): GgaWait {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val chunk = readChunk(port)
            if (chunk.isNotEmpty()) {
                buf.append(chunk)
                var nl = buf.indexOf('\n')
                while (nl >= 0) {
                    val line = buf.substring(0, nl).trim()
                    buf.delete(0, nl + 1)
                    if (line.contains("GGA") && Nmea.parseGga(line) != null) return GgaWait.Ok
                    nl = buf.indexOf('\n')
                }
            } else {
                Thread.sleep(30)
            }
        }
        return GgaWait.Timeout
    }

    private fun readChunk(port: SerialPort): String {
        val buf = ByteArray(512)
        val n = port.readBytes(buf, buf.size)
        return if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else ""
    }

    private fun drain(port: SerialPort) {
        val buf = ByteArray(256)
        repeat(10) {
            if (port.readBytes(buf, buf.size) <= 0) return
        }
    }
}
