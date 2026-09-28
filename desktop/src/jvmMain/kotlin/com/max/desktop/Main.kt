package com.max.desktop

import androidx.compose.material.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Minimal desktop shell. TODO: connect shared Session. */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "max-kmp-core") {
        Text("max-kmp-core desktop skeleton")
    }
}
