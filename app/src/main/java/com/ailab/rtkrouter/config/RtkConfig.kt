package com.ailab.rtkrouter.config

import kotlinx.serialization.Serializable

enum class NtripVersion { V1, V2 }

/** How the active mountpoint is chosen at connect time (see DESIGN.md 3.2). */
enum class EndpointMode {
    /** Fetch sourcetable, prefer a VRS (nmea=1) mount; else nearest fixed station. */
    AUTO,

    /** Use [CasterProfile.preferredMount] verbatim. */
    MANUAL,

    /** Fixed-station only: pick the nearest mount by phone GPS. */
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
    /** Null/blank => AUTO chooses from sourcetable. */
    val preferredMount: String = "",
    /** Lower wins for failover ordering (DESIGN.md 3.3 L2). */
    val priority: Int = 0,
    /** host/port preset; user only supplies credentials (DESIGN.md 3.4). */
    val preset: Boolean = false,
)

/** A mountpoint resolved from the sourcetable or an embedded fallback table. */
@Serializable
data class BaseStation(
    val mount: String,
    val name: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val ellipHeight: Double = 0.0,
    /** = sourcetable STR nmea flag. true => VRS (GGA driven), false => fixed. */
    val requiresGga: Boolean = false,
)

@Serializable
data class FailoverPolicy(
    val minByteRate: Int = 10,          // RTCM B/s below which the stream is "dead"
    val deadTimeoutSec: Int = 10,        // sustained-below duration before declaring dead
    val sameMountRetries: Int = 3,       // L0 attempts before escalating
    val backoffMaxSec: Int = 30,         // exponential backoff cap
    val reprobeSec: Int = 120,           // re-probe a higher-priority provider
    val recoveryHysteresisSec: Int = 30, // stability window before promoting back
)

@Serializable
data class RtkConfig(
    val profiles: List<CasterProfile> = listOf(CasterProfile()),
    val activeIndex: Int = 0,
    val endpointMode: EndpointMode = EndpointMode.AUTO,
    val baud: Int = 115200,
    /** USB serial port index. NovAtel OEM7 exposes multiple CDC ports; the
     *  RTCM3-input port is often not 0. u-blox F9P uses 0. */
    val serialPortIndex: Int = 0,
    /** Auto-set from the resolved mount's requiresGga; user can force on. */
    val sendGga: Boolean = false,
    val validateRtcm3: Boolean = false,
    val autoReconnect: Boolean = true,
    val showDataUsage: Boolean = true,
    /** Embedded fixed-station coordinates used when the sourcetable lacks them. */
    val fallbackStations: List<BaseStation> = emptyList(),
    val failover: FailoverPolicy = FailoverPolicy(),
) {
    val activeProfile: CasterProfile
        get() = profiles.getOrElse(activeIndex) { CasterProfile() }
}
