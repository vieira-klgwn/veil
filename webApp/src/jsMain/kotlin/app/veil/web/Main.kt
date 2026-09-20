package app.veil.web

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun main() {
    val start = { CoroutineScope(Dispatchers.Main).launch { CameraApp().start() } }
    if (document.readyState.toString() == "loading") {
        document.addEventListener("DOMContentLoaded", { start() })
    } else {
        start()
    }
}
