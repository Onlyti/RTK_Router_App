package com.onlyti.rtkrouter.desktop.serial

import com.fazecast.jSerialComm.SerialPort
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Desktop serial bridge via jSerialComm (Linux /dev/tty*, Windows COM*).
 * Writes RTCM to the receiver, reads NMEA in a background thread.
 */
class SerialLink(
    private val devicePath: String,
    private val baud: Int,
    private val onConnected: (deviceName: String) -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
    private val onData: (ByteArray) -> Unit,
) {
    private var port: SerialPort? = null
    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    val rxBytes = AtomicLong(0)
    val txBytes = AtomicLong(0)

    fun connect() {
        if (devicePath.isBlank()) {
            onDisconnected("no serial device selected")
            return
        }
        if (SerialPermission.needsPermissionFix() && !SerialPermission.canReadWrite(devicePath)) {
            onDisconnected("permission denied on $devicePath — use 'Fix permissions'")
            return
        }
        try {
            val p = SerialPort.getCommPort(devicePath)
            p.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
            p.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 2000, 0)
            if (!p.openPort()) {
                val err = if (SerialPermission.needsPermissionFix() && !SerialPermission.canReadWrite(devicePath)) {
                    "permission denied on $devicePath"
                } else {
                    "failed to open $devicePath (in use or driver missing)"
                }
                onDisconnected(err)
                return
            }
            port = p
            running.set(true)
            readerThread = Thread({ readLoop(p) }, "serial-rx-$devicePath").also { it.isDaemon = true; it.start() }
            onConnected(devicePath)
        } catch (t: Throwable) {
            onDisconnected("open failed: ${t.message}")
        }
    }

    private fun readLoop(p: SerialPort) {
        val buf = ByteArray(1024)
        while (running.get()) {
            try {
                val n = p.readBytes(buf, buf.size)
                if (n > 0) {
                    rxBytes.addAndGet(n.toLong())
                    onData(buf.copyOf(n))
                } else if (n < 0) {
                    break
                } else {
                    Thread.sleep(10)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                onDisconnected("serial read error: ${e.message}")
                break
            }
        }
    }

    fun write(data: ByteArray, len: Int) {
        val p = port ?: return
        try {
            val payload = if (len == data.size) data else data.copyOf(len)
            val n = p.writeBytes(payload, payload.size)
            if (n > 0) txBytes.addAndGet(n.toLong())
        } catch (_: Throwable) {
            // transient; watchdog handles persistent failures
        }
    }

    fun close() {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        try { port?.closePort() } catch (_: Throwable) {}
        port = null
    }
}
