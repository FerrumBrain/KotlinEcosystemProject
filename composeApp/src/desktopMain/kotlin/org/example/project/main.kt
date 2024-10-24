package org.example.project

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.*

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KotlinEcosystemProject",
    ) {
        desktopApp()
    }
}

@Composable
@Preview
fun desktopApp() {
    App()
}
