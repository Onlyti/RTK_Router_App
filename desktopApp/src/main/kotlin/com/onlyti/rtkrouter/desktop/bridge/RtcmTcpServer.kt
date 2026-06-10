package com.onlyti.rtkrouter.desktop.bridge

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in TCP server that mirrors the relayed RTCM3 byte stream to connected clients.
 *
 * Pure JDK sockets — NO ROS or other external dependency. A separate, standalone ROS bridge
 * node (ros/rtcm_tcp_bridge.py) connects here, frames the stream by RTCM3 boundary
 * (preamble 0xD3 + 10-bit length + CRC24Q), and republishes it as rtcm_msgs/Message on /rtcm.
 * Keeping ROS entirely out of this process means the desktop app builds and runs unchanged on
 * machines without ROS; the feature is off by default and only this thin pipe is added.
 *
 * The payload is a raw passthrough of exactly the bytes written to the serial receiver, i.e. an
 * unframed RTCM3 octet stream (NTRIP read chunks, not frame-aligned). Frame reconstruction is
 * the consumer's job — done robustly (with CRC) in the ROS bridge.
 *
 * Binds the wildcard address so the bridge may run either on the same host (localhost) or on the
 * mapping PC over the LAN. The RTCM correction stream is not secret, but keep it on a trusted
 * network; see ros/README.md.
 */
class RtcmTcpServer(private val port: Int) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var running = false
    private val clients = CopyOnWriteArrayList<Socket>()
    val txBytes = AtomicLong(0)

    val clientCount: Int get() = clients.size
    val listening: Boolean get() = running

    /** Bind and start accepting. Returns null on success, or an error message on failure. */
    fun start(): String? {
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            serverSocket = ss
            running = true
            acceptThread = Thread({ acceptLoop(ss) }, "rtcm-tcp-accept-$port").also {
                it.isDaemon = true
                it.start()
            }
            null
        } catch (t: Throwable) {
            running = false
            "RTCM TCP bind :$port 실패 — ${t.message}"
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val sock = ss.accept()
                sock.tcpNoDelay = true
                clients.add(sock)
            } catch (_: Throwable) {
                if (!running) break
            }
        }
    }

    /** Broadcast [len] bytes of [data] to every connected client; drop clients that error. */
    fun broadcast(data: ByteArray, len: Int) {
        if (len <= 0 || clients.isEmpty()) return
        for (c in clients) {
            try {
                val out = c.getOutputStream()
                out.write(data, 0, len)
                out.flush()
            } catch (_: Throwable) {
                clients.remove(c)
                try { c.close() } catch (_: Throwable) {}
            }
        }
        txBytes.addAndGet(len.toLong())
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        for (c in clients) { try { c.close() } catch (_: Throwable) {} }
        clients.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }
}
