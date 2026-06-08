package com.onlyti.rtkrouter.desktop.serial

enum class PortAvailability {
    AVAILABLE,
    BUSY,
    NO_PERMISSION,
}

data class PortScanEntry(
    val systemPortName: String,
    val descriptiveName: String,
    val portDescription: String,
    val isNovAtelUsb: Boolean,
    /** 1-based NovAtel USB index (USB1..USB3) when [isNovAtelUsb]. */
    val novAtelUsbIndex: Int = 0,
    val availability: PortAvailability,
    val sortKey: Int,
) {
    val displayLabel: String
        get() = buildString {
            append(systemPortName)
            if (isNovAtelUsb && novAtelUsbIndex > 0) append(" · USB$novAtelUsbIndex")
            val extra = descriptiveName.takeIf { it.isNotBlank() && it != systemPortName }
            if (extra != null) append(" · ").append(extra)
        }
}
