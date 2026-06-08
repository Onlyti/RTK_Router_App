package com.onlyti.rtkrouter.desktop.serial

import com.fazecast.jSerialComm.SerialPort
import com.onlyti.rtkrouter.desktop.platform.Platform

data class SerialPortInfo(
    val systemPortName: String,
    val descriptiveName: String,
    val portDescription: String,
)

object SerialPortHelper {
    fun listPorts(): List<SerialPortInfo> =
        SerialPort.getCommPorts()
            .map { p ->
                SerialPortInfo(
                    systemPortName = p.systemPortPath,
                    descriptiveName = p.descriptivePortName ?: p.systemPortPath,
                    portDescription = p.portDescription ?: "",
                )
            }
            .sortedBy { it.systemPortName }

    /** Likely GNSS receivers: CDC-ACM (F9P) or common USB-UART chips. */
    fun guessGnssPorts(): List<SerialPortInfo> =
        listPorts().filter { info ->
            val d = "${info.descriptiveName} ${info.portDescription}".lowercase()
            val name = info.systemPortName.lowercase()
            val osMatch = when {
                Platform.isWindows -> name.startsWith("com")
                else -> name.startsWith("/dev/ttyacm") || name.startsWith("/dev/ttyusb")
            }
            osMatch ||
                "ublox" in d || "u-blox" in d || "novatel" in d ||
                "ftdi" in d || "cp210" in d || "ch340" in d || "cdc" in d
        }
}
