package br.com.xlinx.habblaud.desktop

import br.com.xlinx.habblaud.data.AgentEvent
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.alertSubtitle
import br.com.xlinx.habblaud.data.alertText
import br.com.xlinx.habblaud.data.watchAlerts
import br.com.xlinx.habblaud.pendingSession
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.getString

class DesktopAlerts(private val focused: () -> Boolean, private val bringToFront: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val unavailableReported = AtomicBoolean()

    fun setEnabled(on: Boolean) {
        val previous = job
        previous?.cancel()
        job = if (on) scope.launch {
            previous?.join()
            var multiple = false
            coroutineScope {
                watchAlerts(LocalStateStore(), onServers = { multiple = it > 1 }, notify = { event ->
                    launch { show(event, multiple) }
                })
            }
        } else null
    }

    private suspend fun show(event: AgentEvent, multiple: Boolean) {
        if (withContext(Dispatchers.Main) { focused() }) return
        val title = event.session.characterName ?: event.session.title
        val text = getString(alertText(event)) + (alertSubtitle(event, multiple)?.let { " · $it" } ?: "")
        val mac = System.getProperty("os.name").lowercase().startsWith("mac")
        val arguments = if (mac) listOf("osascript", "-e", "on run argv", "-e",
            "display notification (item 2 of argv) with title (item 1 of argv)", "-e", "end run", title, text)
        else listOf("notify-send", "--app-name=Zaplaud", "--action=open=Abrir", "--wait", title, text)
        try {
            val process = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val action = try {
                runInterruptible { process.waitFor() }
                process.inputStream.bufferedReader().use { it.readText().trim() }
            } finally {
                process.destroy()
            }
            if (!mac && action == "open") withContext(Dispatchers.Main) {
                pendingSession.value = event.session.key
                bringToFront()
            }
        } catch (error: IOException) {
            if (unavailableReported.compareAndSet(false, true)) {
                System.err.println("Zaplaud: ${arguments.first()} indisponível: ${error.message}")
            }
        }
    }

    fun close() { scope.cancel() }
}
