package com.onlyti.rtkrouter.desktop.config

import kotlinx.serialization.Serializable

enum class NtripVersion { V1, V2 }

enum class EndpointMode {
    AUTO,
    MANUAL,
    NEAREST,
}

@Serializable
data class CasterProfile(
    val name: String = "default",
    val host: String = "",
    val port: Int = 2101,
    val user: String = "",
    val pass: String = "",
    val ntripVersion: NtripVersion = NtripVersion.V2,
    val preferredMount: String = "",
    val priority: Int = 0,
    val preset: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class BaseStation(
    val mount: String,
    val name: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val ellipHeight: Double = 0.0,
    val requiresGga: Boolean = false,
)

@Serializable
data class FailoverPolicy(
    val minByteRate: Int = 10,
    val deadTimeoutSec: Int = 10,
    val sameMountRetries: Int = 3,
    val backoffMaxSec: Int = 30,
    val reprobeSec: Int = 120,
    val recoveryHysteresisSec: Int = 30,
)

@Serializable
data class RtkConfig(
    val profiles: List<CasterProfile> = listOf(CasterProfile()),
    val activeIndex: Int = 0,
    val endpointMode: EndpointMode = EndpointMode.AUTO,
    val baud: Int = 115200,
    val serialPortIndex: Int = 0,
    val hotStandbyCount: Int = 3,
    val validateRtcm3: Boolean = false,
    val autoReconnect: Boolean = true,
    val showDataUsage: Boolean = true,
    /**
     * Opt-in (default off): mirror the relayed RTCM3 byte stream to a local TCP server so the
     * standalone ROS bridge (ros/rtcm_tcp_bridge.py) can republish it as rtcm_msgs/Message on
     * /rtcm. Pure-JVM sockets — no ROS dependency in this app. See ros/README.md.
     */
    val rtcmTcpOutEnabled: Boolean = false,
    val rtcmTcpOutPort: Int = 8531,
    val fallbackStations: List<BaseStation> = emptyList(),
    val failover: FailoverPolicy = FailoverPolicy(),
) {
    val activeProfile: CasterProfile
        get() = profiles.getOrElse(activeIndex) { CasterProfile() }
}

@Serializable
data class DesktopSettings(
    val config: RtkConfig = RtkConfig(),
    /** e.g. /dev/ttyACM0 (Linux F9P) or COM3 (Windows). */
    val serialDevicePath: String = "",
    val connectionMode: SerialConnectionMode = SerialConnectionMode.RS232,
    /** When true, periodic scan does not override [serialDevicePath]. */
    val userPickedPort: Boolean = false,
)

data class SerialConnectOptions(
    val mode: SerialConnectionMode,
    val devicePath: String,
    val baud: Int,
    val novAtelUsbIndex: Int = 1,
)
