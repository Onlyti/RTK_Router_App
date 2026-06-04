package com.onlyti.rtkrouter.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Why and where a failure occurred (DESIGN.md 3.3 failure modes + ladder levels). */
data class ErrorInfo(
    val failureMode: String = "",   // NoInternet | CasterDown | AuthFailed | NoRtcmData | NoFix
    val level: String = "",          // L0..L3
    val reason: String = "",
)

/** Live snapshot of the bridge, observed by the UI (DESIGN.md 4 RtkStatus). */
data class RtkStatus(
    val running: Boolean = false,
    // connection: where / which option
    val ntripConnected: Boolean = false,
    val serialConnected: Boolean = false,
    val deviceName: String = "",
    val serialPortCount: Int = 0,   // total CDC ports on the device (NovAtel = multiple)
    val serialPortIndex: Int = 0,   // which port is open
    val activeProfileName: String = "",
    val activeMount: String = "",
    val activeMode: String = "",     // VRS | NEAREST | MANUAL
    val ggaActive: Boolean = false,
    val failoverLevel: String = "L0",
    val streamCount: Int = 0,        // hot-standby streams open
    val healthyCount: Int = 0,       // of which currently healthy
    val streamsInfo: String = "",    // per-stream summary for UI
    // data usage
    val rtcmBytesPerSec: Long = 0,
    val sessionRxBytes: Long = 0,    // RTCM received from caster
    val sessionTxBytes: Long = 0,    // GGA uploaded to caster
    val serialTxBytes: Long = 0,     // bytes written to receiver
    val serialRxBytes: Long = 0,     // bytes read back from receiver
    // fix + errors
    val lastFixQuality: Int = -1,
    val lastError: ErrorInfo = ErrorInfo(),
    val uptimeSec: Long = 0,
    val detail: String = "stopped",
    // receiver (u-blox) parsed output
    val rxLat: Double = Double.NaN,
    val rxLon: Double = Double.NaN,
    val rxSats: Int = 0,
    val rxHdop: Double = Double.NaN,
    val rxAltM: Double = Double.NaN,
    val lastNmea: String = "",
)

/** Process-global bridge status. Written by [RtkService], read by the UI. */
object RtkState {
    private val _status = MutableStateFlow(RtkStatus())
    val status: StateFlow<RtkStatus> = _status

    fun update(s: RtkStatus) { _status.value = s }
    fun current(): RtkStatus = _status.value
    fun reset() { _status.value = RtkStatus() }
}
