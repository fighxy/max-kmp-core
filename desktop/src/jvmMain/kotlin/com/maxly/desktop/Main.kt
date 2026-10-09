package com.maxly.desktop

import androidx.compose.material.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Minimal desktop shell. TODO: connect shared Session. */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Maxly") {
        Text("maxly-core desktop skeleton")
    }
}
