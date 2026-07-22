package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Daemon thread that logs app health every 5 minutes.
 *
 * Detects:
 * - EDT starvation (post a Runnable, check if it executes within timeout)
 * - MainStore state (isLoading, input text length, visible dialogs)
 * - Thread count and memory usage
 * - Hotkey provider health (with auto-recovery)
 *
 * @property logger           Logger to write heartbeat lines to.
 * @property mainStore        Main app store for state inspection (nullable for early calls).
 * @property hotkeyHealthCheck  Returns true if hotkey system is alive; nullable for early calls.
 * @property hotkeyRecovery   Attempts to re-initialize hotkey system; nullable for early calls.
 * @property edtTimeout       Max time to wait for EDT response, in milliseconds.
 * @property intervalMs       Heartbeat interval in milliseconds.
 */
class HeartbeatMonitor(
    private val logger: Logger,
    private val mainStore: () -> MainStore?,
    private val hotkeyHealthCheck: (() -> Boolean)? = null,
    private val hotkeyRecovery: (() -> Boolean)? = null,
    private val edtTimeout: Long = 10_000L,
    private val intervalMs: Long = 5 * 60 * 1000L // 5 minutes
) {
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            logger.info("HeartbeatMonitor started (interval=${intervalMs}ms, edtTimeout=${edtTimeout}ms)")
            while (running) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                emitHeartbeat()
            }
            logger.info("HeartbeatMonitor stopped")
        }, "HeartbeatMonitor").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun emitHeartbeat() {
        val sb = StringBuilder()
        sb.appendLine("=== HEARTBEAT ===")
        sb.appendLine("  time=${java.time.LocalDateTime.now()}")
        sb.appendLine("  threadCount=${Thread.activeCount()}")

        // Memory
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMB = runtime.maxMemory() / (1024 * 1024)
        sb.appendLine("  memory=${usedMB}MB/${maxMB}MB")

        // EDT check
        val edtOk = checkEdt()
        sb.appendLine("  edt=$edtOk")
        if (!edtOk) {
            sb.appendLine("  edt-state=${getEdtThreadState()}")
            sb.appendLine("  edt-stack=${getEdtStackTrace()}")
        }

        // Hotkey health
        val healthCheck = hotkeyHealthCheck
        if (healthCheck != null) {
            val hotkeysAlive = healthCheck()
            sb.appendLine("  hotkeys=$hotkeysAlive")
            if (!hotkeysAlive) {
                val recovery = hotkeyRecovery
                if (recovery != null) {
                    val recovered = recovery()
                    sb.appendLine("  hotkey-recovery=$recovered")
                }
            }
        }

        // MainStore state
        val store = mainStore()
        if (store != null) {
            val state = store.state.value
            sb.appendLine("  isLoading=${state.isLoading}")
            sb.appendLine("  inputLength=${state.inputText.length}")
            sb.appendLine("  quickTranslateVisible=${state.isQuickTranslateDialogVisible}")
            sb.appendLine("  quickTranslatePinned=${state.isQuickTranslateDialogPinned}")
            sb.appendLine("  quickDictVisible=${state.isQuickDictionaryVisible}")
            sb.appendLine("  availableServices=${state.availableServices.size}")
        } else {
            sb.appendLine("  store=null (not initialized)")
        }

        sb.appendLine("=== END HEARTBEAT ===")

        logger.info(sb.toString().trimEnd())
    }

    /**
     * Posts a Runnable to the EDT and waits for it to execute.
     * Returns true if EDT responded within [edtTimeout], false if it's stuck.
     */
    private fun checkEdt(): Boolean {
        if (!SwingUtilities.isEventDispatchThread()) {
            val latch = CountDownLatch(1)
            SwingUtilities.invokeLater { latch.countDown() }
            return latch.await(edtTimeout, TimeUnit.MILLISECONDS)
        }
        // If we're somehow on the EDT itself, just return true
        return true
    }

    private fun getEdtThreadState(): String {
        val edtThread = findEdtThread() ?: return "not-found"
        return edtThread.state.name
    }

    private fun getEdtStackTrace(): String {
        val edtThread = findEdtThread() ?: return "not-found"
        val stackTrace = edtThread.stackTrace.take(15)
        return stackTrace.joinToString(" <- ") { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
    }

    private fun findEdtThread(): Thread? {
        return Thread.getAllStackTraces().keys.find { thread ->
            thread.name.contains("AWT-EventQueue") && thread.isAlive
        }
    }
}
