package com.onlyti.rtkrouter.desktop.config

import kotlinx.serialization.Serializable

@Serializable
enum class SerialConnectionMode {
    /** USB-UART / single COM (FTDI, CH340, u-blox UART). NovAtel multi-CDC hidden. */
    RS232,
    /** NovAtel OEM7 USB CDC ports only; auto INTERFACEMODE + LOG GPGGA on START. */
    NOVATEL_USB,
}
