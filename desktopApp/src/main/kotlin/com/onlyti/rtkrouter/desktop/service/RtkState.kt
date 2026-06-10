package com.onlyti.rtkrouter.desktop.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ErrorInfo(
    val failureMode: String = "",
    val level: String = "",
    val reason: String = "",
)

data class RtkStatus(
    val running: Boolean = false,
    val ntripConnected: Boolean = false,
    val serialConnected: Boolean = false,
    val deviceName: String = "",
    val serialPortCount: Int = 0,
    val serialPortIndex: Int = 0,
    val activeProfileName: String = "",
    val activeMount: String = "",
    val activeMode: String = "",
    val ggaActive: Boolean = false,
    val failoverLevel: String = "L0",
    val streamCount: Int = 0,
    val healthyCount: Int = 0,
    val streamLines: List<String> = emptyList(),
    val rtcmBytesPerSec: Long = 0,
    val sessionRxBytes: Long = 0,
    val sessionTxBytes: Long = 0,
    val serialTxBytes: Long = 0,
    val serialRxBytes: Long = 0,
    val lastFixQuality: Int = -1,
    val lastError: ErrorInfo = ErrorInfo(),
    val uptimeSec: Long = 0,
    val detail: String = "stopped",
    val rxLat: Double = Double.NaN,
    val rxLon: Double = Double.NaN,
    val rxSats: Int = 0,
    val rxHdop: Double = Double.NaN,
    val rxAltM: Double = Double.NaN,
    val lastNmea: String = "",
    val trajectory: List<GeoPt> = emptyList(),
    /** ok | warn (orange) | error */
    val healthLevel: String = "ok",
    val healthMessage: String = "",
    val rtcmTcpOutEnabled: Boolean = false,
    val rtcmTcpPort: Int = 0,
    val rtcmTcpClients: Int = 0,
)

data class GeoPt(val lat: Double, val lon: Double)

object RtkState {
    private val _status = MutableStateFlow(RtkStatus())
    val status: StateFlow<RtkStatus> = _status

    fun update(s: RtkStatus) { _status.value = s }
    fun current(): RtkStatus = _status.value
    fun reset() { _status.value = RtkStatus() }
}
