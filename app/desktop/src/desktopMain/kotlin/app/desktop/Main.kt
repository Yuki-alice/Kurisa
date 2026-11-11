package app.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.shared.App

/** 桌面入口（产品显示名只落配置层，此处标题即一例）。 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Kurisa") {
        App()
    }
}
