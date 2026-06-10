package com.onlyti.rtkrouter.desktop.serial

import com.fazecast.jSerialComm.SerialPort
import com.onlyti.rtkrouter.desktop.config.SerialConnectionMode

object SerialPortScanner {
    const val NOVATEL_VID = 0x09D7
    private const val PROBE_BAUD = 9600

    fun scan(mode: SerialConnectionMode): List<PortScanEntry> {
        if (mode == SerialConnectionMode.ROS_RTCM) return emptyList()  // no local serial in ROS mode
        val raw = SerialPort.getCommPorts().map { p ->
            Triple(p, isNovAtelPort(p), portSortKey(p.systemPortPath))
        }
        val novAtelOrdered = raw.filter { it.second }.sortedBy { it.third }

        return raw
            .filter { (_, isNov, _) ->
                when (mode) {
                    SerialConnectionMode.RS232 -> !isNov
                    SerialConnectionMode.NOVATEL_USB -> isNov
                    SerialConnectionMode.ROS_RTCM -> false
                }
            }
            .sortedBy { it.third }
            .map { (port, isNov, sortKey) ->
                val usbIdx = if (isNov) {
                    novAtelOrdered.indexOfFirst { it.first.systemPortPath == port.systemPortPath } + 1
                } else {
                    0
                }
                PortScanEntry(
                    systemPortName = port.systemPortPath,
                    descriptiveName = port.descriptivePortName ?: port.systemPortPath,
                    portDescription = port.portDescription ?: "",
                    isNovAtelUsb = isNov,
                    novAtelUsbIndex = usbIdx.coerceAtLeast(if (isNov) 1 else 0),
                    availability = probeAvailability(port.systemPortPath),
                    sortKey = sortKey,
                )
            }
    }

    fun defaultFreePort(entries: List<PortScanEntry>): PortScanEntry? =
        entries
            .filter { it.availability == PortAvailability.AVAILABLE }
            .maxByOrNull { it.sortKey }

    fun isNovAtelPort(port: SerialPort): Boolean {
        if (port.vendorID == NOVATEL_VID) return true
        val d = "${port.descriptivePortName} ${port.portDescription}".lowercase()
        return "novatel" in d
    }

    fun portSortKey(systemPortName: String): Int {
        Regex("COM(\\d+)", RegexOption.IGNORE_CASE).find(systemPortName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("ttyACM(\\d+)").find(systemPortName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("ttyUSB(\\d+)").find(systemPortName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 0
    }

    fun probeAvailability(devicePath: String): PortAvailability {
        if (devicePath.isBlank()) return PortAvailability.BUSY
        if (SerialPermission.needsPermissionFix() && !SerialPermission.canReadWrite(devicePath)) {
            return PortAvailability.NO_PERMISSION
        }
        val p = SerialPort.getCommPort(devicePath)
        p.setComPortParameters(PROBE_BAUD, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 50, 0)
        return try {
            if (p.openPort()) {
                p.closePort()
                PortAvailability.AVAILABLE
            } else {
                PortAvailability.BUSY
            }
        } catch (_: Throwable) {
            PortAvailability.BUSY
        }
    }
}
