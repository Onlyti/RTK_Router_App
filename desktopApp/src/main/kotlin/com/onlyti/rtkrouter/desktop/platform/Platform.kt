package com.onlyti.rtkrouter.desktop.platform

object Platform {
    private val osName: String = System.getProperty("os.name").lowercase()

    val isLinux: Boolean get() = osName.contains("linux")
    val isWindows: Boolean get() = osName.contains("win")

    val serialPortHint: String
        get() = if (isWindows) "COM3 (USB-UART or u-blox CDC)" else "/dev/ttyACM0 or /dev/ttyUSB0"

    val serialPermissionHint: String?
        get() = if (isLinux) {
            "권한 없으면 START 시 pkexec/sudo로 /dev/tty* 접근 권한을 부여할 수 있습니다.\n" +
                "영구 해결: sudo usermod -aG dialout \$USER 후 재로그인"
        } else {
            null
        }
}
