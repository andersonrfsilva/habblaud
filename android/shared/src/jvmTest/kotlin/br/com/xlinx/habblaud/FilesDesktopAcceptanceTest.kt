package br.com.xlinx.habblaud

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import br.com.xlinx.habblaud.data.LocalStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FilesDesktopAcceptanceTest {
    @Test fun capturesPhoneAndWideThemesAndChecksLargeDownloadHash() {
        val directory = Files.createTempDirectory("zaplaud-files-desktop-").toFile()
        val previousHome = System.getProperty("user.home")
        val previousData = System.getProperty("zaplaud.dataDir")
        val previousPrefs = System.getProperty("zaplaud.prefsNode")
        val prefs = "br/com/xlinx/zaplaud-files-${directory.name}"
        var child: Process? = null
        try {
            System.setProperty("user.home", directory.absolutePath)
            System.setProperty("zaplaud.dataDir", directory.resolve("state").absolutePath)
            System.setProperty("zaplaud.prefsNode", prefs)
            assertEquals(directory.resolve("Downloads").toPath(), downloadDirectory(),
                "Execute com XDG_CONFIG_HOME=/dev/null para isolar Downloads.")
            val workspace = directory.resolve("workspace/documentos").apply { mkdirs() }
            val source = workspace.resolve("grande.bin")
            val block = ByteArray(100_000) { (it % 251).toByte() }
            source.outputStream().use { output -> repeat(500) { output.write(block) } }
            assertEquals(50_000_000L, source.length())
            val expectedHash = hash(source)
            val process = ProcessBuilder("node", "--import", "tsx", "scripts/android-smoke.ts", "--port", "0",
                "--allow", "terminal,messages,permissions", "--files", workspace.parentFile.absolutePath)
                .directory(File("../..")).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            child = process
            val port = CompletableFuture.supplyAsync {
                val reader = process.inputStream.bufferedReader()
                val ready = generateSequence { reader.readLine() }.first { it.contains("\"ready\":true") }
                val value = Regex("\"url\":\"http://127\\.0\\.0\\.1:(\\d+)\"").find(ready)!!.groupValues[1].toInt()
                Thread { reader.forEachLine { } }.apply { isDaemon = true; start() }
                value
            }.get(60, TimeUnit.SECONDS)
            val output = File("build/files-evidence").apply { mkdirs() }
            for ((label, width) in listOf("phone" to 420, "wide" to 1280)) {
                for (theme in listOf("light", "dark")) {
                    runBlocking {
                        LocalStateStore().clear()
                        LocalStateStore().setTheme(theme)
                    }
                    runDesktopComposeUiTest(width = width, height = 900) {
                        setContent {
                            CompositionLocalProvider(LocalNotificationPlatform provides NotificationPlatform(false, {}, {}, { _, result -> result(false) })) {
                                ZaplaudApp()
                            }
                        }
                        loginToFixture(port)
                        onNodeWithContentDescription("Menu").performClick()
                        onNodeWithTag("files-menu").performClick()
                        waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("files-entry:documentos").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("files-entry:documentos").performClick()
                        waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("files-download:grande.bin").fetchSemanticsNodes().isNotEmpty() }
                        waitForIdle()
                        Image.makeFromBitmap(onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()).use { image ->
                            image.encodeToData()!!.use { data ->
                                File("/tmp/zaplaud-align-kfiles-$label-$theme.png").writeBytes(data.bytes)
                            }
                        }
                        if (label == "wide" && theme == "dark") {
                            onNodeWithTag("files-download:grande.bin").performClick()
                            waitUntil(timeoutMillis = 120_000) { onAllNodesWithTag("files-completed:grande.bin").fetchSemanticsNodes().isNotEmpty() }
                            val downloaded = directory.resolve("Downloads/grande.bin")
                            assertEquals(source.length(), downloaded.length())
                            assertEquals(expectedHash, hash(downloaded))
                            output.resolve("download.sha256").writeText("$expectedHash  grande.bin\n")
                            assertTrue(downloaded.delete())
                        }
                    }
                }
            }
        } finally {
            child?.let { process ->
                process.outputStream.close()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroy()
                    if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor() }
                }
            }
            Preferences.userRoot().node(prefs).removeNode()
            Preferences.userRoot().flush()
            directory.deleteRecursively()
            for ((name, value) in listOf("user.home" to previousHome, "zaplaud.dataDir" to previousData, "zaplaud.prefsNode" to previousPrefs)) {
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
