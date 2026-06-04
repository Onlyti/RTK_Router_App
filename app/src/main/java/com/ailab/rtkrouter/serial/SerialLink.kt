package com.ailab.rtkrouter.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.atomic.AtomicLong

/**
 * USB-host serial bridge to the receiver via usb-serial-for-android.
 * Writes RTCM (caster -> receiver), reads back NMEA (receiver -> phone) for fix status.
 *
 * USB permission is requested on demand through a PendingIntent + BroadcastReceiver,
 * mirroring the permission flow in phone-sensor-stream's AoaTransport.
 */
class SerialLink(
    private val context: Context,
    private val baud: Int,
    private val onConnected: (deviceName: String) -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
    /** Bytes read from the receiver (typically NMEA). */
    private val onData: (ByteArray) -> Unit,
) {
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    val rxBytes = AtomicLong(0)
    val txBytes = AtomicLong(0)

    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            context.unregisterReceiver(this)
            if (granted) openFirstDriver() else onDisconnected("USB permission denied")
        }
    }

    /** Find the first serial driver; request permission if needed, else open immediately. */
    fun connect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            onDisconnected("no USB serial device found")
            return
        }
        val device = drivers[0].device
        if (usbManager.hasPermission(device)) {
            openFirstDriver()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
            )
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(permissionReceiver, filter)
            }
            usbManager.requestPermission(device, pi)
        }
    }

    private fun openFirstDriver() {
        try {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
                ?: run { onDisconnected("device detached"); return }
            val connection = usbManager.openDevice(driver.device)
                ?: run { onDisconnected("openDevice failed"); return }
            val p = driver.ports[0]
            p.open(connection)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p

            val io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    rxBytes.addAndGet(data.size.toLong())
                    onData(data)
                }

                override fun onRunError(e: Exception) {
                    onDisconnected("serial read error: ${e.message}")
                }
            })
            ioManager = io
            io.start()

            val name = driver.device.productName ?: driver.javaClass.simpleName
            Log.d(TAG, "serial open: driver=${driver.javaClass.simpleName} device=$name baud=$baud")
            onConnected(name)
        } catch (t: Throwable) {
            onDisconnected("open failed: ${t.message}")
        }
    }

    /** Inject correction bytes to the receiver. Safe to call from any thread. */
    fun write(data: ByteArray, len: Int) {
        val p = port ?: return
        try {
            val payload = if (len == data.size) data else data.copyOf(len)
            p.write(payload, WRITE_TIMEOUT_MS)
            txBytes.addAndGet(len.toLong())
        } catch (_: Throwable) {
            // transient; the byte watchdog / reconnect handles persistent failures
        }
    }

    fun close() {
        try { ioManager?.stop() } catch (_: Throwable) {}
        try { port?.close() } catch (_: Throwable) {}
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
        ioManager = null
        port = null
    }

    companion object {
        private const val TAG = "rtk"
        private const val ACTION_USB_PERMISSION = "com.ailab.rtkrouter.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 2000
    }
}
