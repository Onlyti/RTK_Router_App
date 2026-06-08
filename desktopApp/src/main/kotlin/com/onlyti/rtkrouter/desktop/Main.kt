package com.onlyti.rtkrouter.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.onlyti.rtkrouter.desktop.ui.DesktopApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "RTK Router",
        state = rememberWindowState(width = 920.dp, height = 900.dp),
    ) {
        DesktopApp()
    }
}
