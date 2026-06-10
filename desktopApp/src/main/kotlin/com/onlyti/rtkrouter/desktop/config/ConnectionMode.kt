package com.onlyti.rtkrouter.desktop.config

import kotlinx.serialization.Serializable

@Serializable
enum class SerialConnectionMode {
    /** USB-UART / single COM (FTDI, CH340, u-blox UART). NovAtel multi-CDC hidden. */
    RS232,
    /** NovAtel OEM7 USB CDC ports only; auto INTERFACEMODE + LOG GPGGA on START. */
    NOVATEL_USB,

    /**
     * No local serial. On START, spawn the bundled rospy node and pipe RTCM3 to it; the node
     * publishes rtcm_msgs/Message on the topic for a ROS driver (e.g. ublox_gps on u-blox).
     */
    ROS_RTCM,
}
