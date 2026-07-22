package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.tulskiy.keymaster.common.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages global and local hotkey registration.
 *
 * ### Scopes
 * - [HotkeyScope.GLOBAL] — registered with jKeymaster, fires system-wide
 *   even when QTranslate is not focused.
 * - [HotkeyScope.LOCAL] — the caller (MainAppFrame) registers these via
 *   Swing InputMap/ActionMap; this listener only provides the binding list
 *   via [getLocalBindings]. Local bindings never intercept keys from other apps.
 *
 * ### Per-action scope (Dinar's request)
 * Users can choose per action whether it should be global or local. This prevents
 * shortcuts like Ctrl+L from being stolen from browsers when set to LOCAL.
 *
 * ### Actions
 * - [HotkeyAction.SHOW_MAIN_WINDOW] — special: uses double-Ctrl via JNativeHook,
 *   not a regular KeyStroke. Always GLOBAL. Respects [isEnabled] on the binding.
 * - [HotkeyAction.REPLACE_WITH_TRANSLATION] — copies selected text, translates,
 *   pastes result back via [onReplaceWithTranslation].
 * - [HotkeyAction.CYCLE_TARGET_LANGUAGE] — default LOCAL, cycles the target language.
 */
class MainGlobalKeyListener(
    private val scope: CoroutineScope,
    /** Receives the captured selection, or `null` when nothing was selected. */
    private val onShowApp: (String?) -> Unit,
    private val onShowQuickTranslate: (String) -> Unit,
    private val onListenToText: (String) -> Unit,
    private val onOpenSnippingTool: () -> Unit,
    private val onReplaceWithTranslation: (String) -> Unit,
    private val onCycleTargetLanguage: () -> Unit,
    private val onShowDictionary: (String) -> Unit = {},
    private val onTranslate: () -> Unit = {},
    private val logger: com.github.ahatem.qtranslate.api.core.Logger? = null
) {

    private var provider: Provider? = null
    private var nativeHookRegistered = false
    private val sequenceListener = CustomSequenceListener()
    private val clipboardLock = AtomicBoolean(false)
    private val hotkeysEnabled = AtomicBoolean(true)
    // Raised while [simulateCopy] is injecting Ctrl+C at OS level. The native hook
    // cannot tell our own synthetic keys from the user's, so it must ignore events
    // while this is up — otherwise the app feeds its own Ctrl presses back into the
    // double-Ctrl detector and re-triggers itself.
    private val syntheticInput = AtomicBoolean(false)
    // AtomicBoolean.compareAndSet prevents double-initialization if initialize()
    // is called concurrently (e.g. from two rapid lifecycle events).
    private val initialized = AtomicBoolean(false)

    @Volatile private var bindings: List<HotkeyBinding> = HotkeyBinding.DEFAULTS

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            initJKeyMaster()
            initJNativeHook()
        } catch (e: Exception) {
            initialized.set(false)   // allow retry if initialization itself failed
            System.err.println("Hotkey initialization failed: ${e.message}")
            e.printStackTrace()
        }
    }

    fun updateBindings(newBindings: List<HotkeyBinding>) {
        bindings = newBindings
        if (!initialized.get()) return
        try {
            provider?.reset()
            registerGlobalHotkeys()
        } catch (e: Exception) {
            System.err.println("Failed to update hotkey bindings: ${e.message}")
        }
    }

    fun setHotkeysEnabled(enabled: Boolean) {
        if (!initialized.get()) return
        if (hotkeysEnabled.getAndSet(enabled) == enabled) return
        if (enabled) enableHotkeys() else disableHotkeys()
    }

    fun areHotkeysEnabled(): Boolean = hotkeysEnabled.get()

    /**
     * Checks if the native hook is alive and the provider is functional.
     * Used by HeartbeatMonitor to detect hook death.
     */
    fun isNativeHookAlive(): Boolean {
        if (!nativeHookRegistered) return false
        return try {
            GlobalScreen.isNativeHookRegistered()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if the keymaster Provider is alive.
     */
    fun isProviderAlive(): Boolean {
        return provider != null
    }

    /**
     * Attempts to re-register the native hook if it has died.
     * Called by HeartbeatMonitor when it detects hook failure.
     */
    fun tryReinitialize(): Boolean {
        if (!initialized.get()) return false
        if (isNativeHookAlive() && isProviderAlive()) return true

        System.err.println("[Hotkeys] Hotkey system appears dead, attempting re-initialization...")
        try {
            // Clean up dead components
            try {
                if (nativeHookRegistered) {
                    GlobalScreen.removeNativeKeyListener(sequenceListener)
                    GlobalScreen.unregisterNativeHook()
                    nativeHookRegistered = false
                }
            } catch (_: Exception) { }

            // Re-initialize everything
            initJKeyMaster()
            initJNativeHook()
            println("[Hotkeys] Hotkey system re-initialized successfully")
            return true
        } catch (e: Exception) {
            System.err.println("[Hotkeys] Re-initialization failed: ${e.message}")
            return false
        }
    }

    /**
     * Returns bindings with [HotkeyScope.LOCAL] scope that are enabled and have a key.
     * The caller (MainAppFrame) registers these via Swing InputMap.
     */
    fun getLocalBindings(): List<HotkeyBinding> =
        bindings.filter { it.scope == HotkeyScope.LOCAL && it.isEnabled && it.hasBinding }

    fun shutdown() {
        if (!initialized.get()) return
        try {
            provider?.reset()
            provider?.stop()
            provider = null
            if (nativeHookRegistered) {
                GlobalScreen.removeNativeKeyListener(sequenceListener)
                GlobalScreen.unregisterNativeHook()
                nativeHookRegistered = false
            }
        } catch (e: Exception) {
            System.err.println("Hotkey manager shutdown error: ${e.message}")
        } finally {
            initialized.set(false)
        }
    }

    private fun initJKeyMaster() {
        provider = Provider.getCurrentProvider(false)
            ?: throw Exception("Hotkey provider unavailable")
        registerGlobalHotkeys()
    }

    /**
     * Registers only [HotkeyScope.GLOBAL] bindings with jKeymaster.
     * LOCAL bindings are handled by MainAppFrame via Swing InputMap.
     *
     * Each binding is wrapped in its own try-catch so a single failure
     * (e.g. OS refuses to grant a reserved key combination) does not
     * silently abort registration of the remaining bindings.
     */
    private fun registerGlobalHotkeys() {
        val p = provider ?: return

        bindings
            .filter { it.scope == HotkeyScope.GLOBAL && it.isEnabled && it.hasBinding }
            .forEach { binding ->
                val keyStroke = binding.toKeyStroke() ?: return@forEach
                val action = binding.action
                runCatching {
                    p.register(keyStroke) {
                        if (!hotkeysEnabled.get()) return@register
                        dispatchAction(action)
                    }
                    println("[Hotkeys] Registered GLOBAL ${action.name}: $keyStroke")
                }.onFailure { ex ->
                    System.err.println(
                        "[Hotkeys] Failed to register GLOBAL ${action.name} ($keyStroke): ${ex.message}"
                    )
                }
            }
    }

    /**
     * Dispatches an action from either a global hotkey or a local InputMap trigger.
     * Called from both [registerGlobalHotkeys] and MainAppFrame's local key handler.
     */
    fun dispatchAction(action: HotkeyAction) {
        when (action) {
            HotkeyAction.SHOW_QUICK_TRANSLATE ->
                scope.launch { handleSelectedText(onShowQuickTranslate) }
            HotkeyAction.LISTEN_TO_TEXT ->
                scope.launch { handleSelectedText(onListenToText) }
            HotkeyAction.OPEN_OCR ->
                onOpenSnippingTool()
            HotkeyAction.SHOW_MAIN_WINDOW ->
                scope.launch { showMainWindow() }
            HotkeyAction.REPLACE_WITH_TRANSLATION ->
                scope.launch { handleSelectedText(onReplaceWithTranslation) }
            HotkeyAction.CYCLE_TARGET_LANGUAGE ->
                onCycleTargetLanguage()
            HotkeyAction.SHOW_DICTIONARY ->
                scope.launch { handleSelectedText(onShowDictionary) }
            HotkeyAction.TRANSLATE ->
                onTranslate()
            // Focus actions are LOCAL-scope only — handled by MainContentView's InputMap.
            // Nothing to do here; the branch is required for exhaustive when.
            HotkeyAction.FOCUS_INPUT,
            HotkeyAction.FOCUS_OUTPUT,
            HotkeyAction.FOCUS_EXTRA_OUTPUT -> Unit
        }
    }

    private fun enableHotkeys() {
        try { provider?.reset(); registerGlobalHotkeys() }
        catch (e: Exception) { System.err.println("Enable hotkeys failed: ${e.message}") }
    }

    private fun disableHotkeys() {
        try { provider?.reset() }
        catch (e: Exception) { System.err.println("Disable hotkeys failed: ${e.message}") }
    }

    private fun initJNativeHook() {
        try {
            if (!nativeHookRegistered) {
                GlobalScreen.registerNativeHook()
                nativeHookRegistered = true
            }
            GlobalScreen.addNativeKeyListener(sequenceListener)
        } catch (ex: NativeHookException) {
            throw Exception("Native hook registration failed", ex)
        }
    }

    /**
     * Handles the double-Ctrl sequence for [HotkeyAction.SHOW_MAIN_WINDOW].
     *
     * This cannot be expressed as a single KeyStroke so it uses JNativeHook's
     * raw key events. It only fires if:
     * 1. Global hotkeys are enabled
     * 2. The SHOW_MAIN_WINDOW binding exists AND isEnabled = true
     *    (Hoyeun's request — user can disable it from the keyboard panel)
     */
    private inner class CustomSequenceListener : NativeKeyListener {
        private var lastCtrlTime = 0L
        private var lastTriggerTime = 0L
        private val threshold = 400

        // --- Verbose logging state ---
        private var verboseLogging = false
        private var verboseUntil = 0L
        private var firstCtrlPressTime = 0L
        private val keysBetweenCtrl = mutableListOf<String>()
        private var lastEventTime = 0L
        private var ctrlPressCount = 0
        private var ctrlReleaseCount = 0
        private var filterSkips = mutableListOf<String>()

        private fun keyName(code: Int): String = when (code) {
            NativeKeyEvent.VC_CONTROL -> "VK_CONTROL"
            NativeKeyEvent.VC_SHIFT -> "VK_SHIFT"
            NativeKeyEvent.VC_ALT -> "VK_ALT"
            NativeKeyEvent.VC_META -> "VK_META"
            NativeKeyEvent.VC_ESCAPE -> "VK_ESCAPE"
            NativeKeyEvent.VC_ENTER -> "VK_ENTER"
            NativeKeyEvent.VC_SPACE -> "VK_SPACE"
            NativeKeyEvent.VC_TAB -> "VK_TAB"
            NativeKeyEvent.VC_BACKSPACE -> "VK_BACKSPACE"
            NativeKeyEvent.VC_DELETE -> "VK_DELETE"
            NativeKeyEvent.VC_LEFT -> "VK_LEFT"
            NativeKeyEvent.VC_RIGHT -> "VK_RIGHT"
            NativeKeyEvent.VC_UP -> "VK_UP"
            NativeKeyEvent.VC_DOWN -> "VK_DOWN"
            NativeKeyEvent.VC_A -> "VK_A"
            NativeKeyEvent.VC_B -> "VK_B"
            NativeKeyEvent.VC_C -> "VK_C"
            NativeKeyEvent.VC_D -> "VK_D"
            NativeKeyEvent.VC_E -> "VK_E"
            NativeKeyEvent.VC_F -> "VK_F"
            NativeKeyEvent.VC_G -> "VK_G"
            NativeKeyEvent.VC_H -> "VK_H"
            NativeKeyEvent.VC_I -> "VK_I"
            NativeKeyEvent.VC_J -> "VK_J"
            NativeKeyEvent.VC_K -> "VK_K"
            NativeKeyEvent.VC_L -> "VK_L"
            NativeKeyEvent.VC_M -> "VK_M"
            NativeKeyEvent.VC_N -> "VK_N"
            NativeKeyEvent.VC_O -> "VK_O"
            NativeKeyEvent.VC_P -> "VK_P"
            NativeKeyEvent.VC_Q -> "VK_Q"
            NativeKeyEvent.VC_R -> "VK_R"
            NativeKeyEvent.VC_S -> "VK_S"
            NativeKeyEvent.VC_T -> "VK_T"
            NativeKeyEvent.VC_U -> "VK_U"
            NativeKeyEvent.VC_V -> "VK_V"
            NativeKeyEvent.VC_W -> "VK_W"
            NativeKeyEvent.VC_X -> "VK_X"
            NativeKeyEvent.VC_Y -> "VK_Y"
            NativeKeyEvent.VC_Z -> "VK_Z"
            NativeKeyEvent.VC_0 -> "VK_0"
            NativeKeyEvent.VC_1 -> "VK_1"
            NativeKeyEvent.VC_2 -> "VK_2"
            NativeKeyEvent.VC_3 -> "VK_3"
            NativeKeyEvent.VC_4 -> "VK_4"
            NativeKeyEvent.VC_5 -> "VK_5"
            NativeKeyEvent.VC_6 -> "VK_6"
            NativeKeyEvent.VC_7 -> "VK_7"
            NativeKeyEvent.VC_8 -> "VK_8"
            NativeKeyEvent.VC_9 -> "VK_9"
            NativeKeyEvent.VC_F1 -> "VK_F1"
            NativeKeyEvent.VC_F2 -> "VK_F2"
            NativeKeyEvent.VC_F3 -> "VK_F3"
            NativeKeyEvent.VC_F4 -> "VK_F4"
            NativeKeyEvent.VC_F5 -> "VK_F5"
            NativeKeyEvent.VC_F6 -> "VK_F6"
            NativeKeyEvent.VC_F7 -> "VK_F7"
            NativeKeyEvent.VC_F8 -> "VK_F8"
            NativeKeyEvent.VC_F9 -> "VK_F9"
            NativeKeyEvent.VC_F10 -> "VK_F10"
            NativeKeyEvent.VC_F11 -> "VK_F11"
            NativeKeyEvent.VC_F12 -> "VK_F12"
            else -> "VK_0x${code.toString(16).uppercase()}"
        }

        private fun log(msg: String) {
            logger?.debug(msg) ?: println(msg)
        }

        private fun startVerboseMode() {
            verboseLogging = true
            verboseUntil = System.currentTimeMillis() + 1000
            firstCtrlPressTime = System.currentTimeMillis()
            keysBetweenCtrl.clear()
            filterSkips.clear()
            ctrlPressCount = 0
            ctrlReleaseCount = 0
            lastEventTime = firstCtrlPressTime
            log("[DoubleCtrl] === Ctrl pressed — verbose logging ON for 1s ===")
        }

        private fun stopVerboseMode() {
            verboseLogging = false
            log("[DoubleCtrl] === Verbose logging OFF (1s expired) ===")
        }

        private fun logKeyEvent(type: String, code: Int) {
            if (!verboseLogging) return
            val now = System.currentTimeMillis()
            if (now > verboseUntil) {
                stopVerboseMode()
                return
            }
            val gap = now - lastEventTime
            val elapsed = now - firstCtrlPressTime
            val tag = if (code != NativeKeyEvent.VC_CONTROL) " [BETWEEN]" else ""
            log("[KeyLog] t+${elapsed}ms $type ${keyName(code)}(0x${code.toString(16)}) gap=${gap}ms$tag")
            lastEventTime = now
        }

        private fun endSequence(cause: String, verdict: String) {
            val elapsed = System.currentTimeMillis() - firstCtrlPressTime
            val summary = buildString {
                append("[DoubleCtrl] === Sequence end: ")
                append("t=${elapsed}ms | ")
                append("ctrlPressed=$ctrlPressCount ctrlReleased=$ctrlReleaseCount | ")
                append("keysBetween=${keysBetweenCtrl.size} (${keysBetweenCtrl.joinToString()}) | ")
                if (filterSkips.isNotEmpty()) append("filterSkips=[${filterSkips.joinToString("; ")}] | ")
                append("cause=$cause → $verdict ===")
            }
            log(summary)
            verboseLogging = false
            keysBetweenCtrl.clear()
        }

        private fun logNonCtrlRelease(code: Int) {
            if (!verboseLogging) return
            val now = System.currentTimeMillis()
            if (now > verboseUntil) return
            val elapsed = now - firstCtrlPressTime
            log("[KeyLog] t+${elapsed}ms released ${keyName(code)}(0x${code.toString(16)}) [BETWEEN-RELEASE]")
        }

        override fun nativeKeyPressed(e: NativeKeyEvent) {
            // Our own Robot-injected Ctrl+C — not a user keystroke.
            if (syntheticInput.get()) return

            // Start verbose logging on first Ctrl press
            if (e.keyCode == NativeKeyEvent.VC_CONTROL && !verboseLogging) {
                startVerboseMode()
            }

            logKeyEvent("pressed ", e.keyCode)

            if (verboseLogging && e.keyCode == NativeKeyEvent.VC_CONTROL) {
                ctrlPressCount++
                log("[Filter] Ctrl press #$ctrlPressCount")
            }

            if (verboseLogging && e.keyCode != NativeKeyEvent.VC_CONTROL) {
                keysBetweenCtrl.add(keyName(e.keyCode))
            }
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // Our own Robot-injected Ctrl+C — not a user keystroke.
            if (syntheticInput.get()) return

            logKeyEvent("released", e.keyCode)

            if (verboseLogging && e.keyCode != NativeKeyEvent.VC_CONTROL) {
                logNonCtrlRelease(e.keyCode)
            }

            if (verboseLogging && e.keyCode == NativeKeyEvent.VC_CONTROL) {
                ctrlReleaseCount++
                log("[Filter] Ctrl release #$ctrlReleaseCount")
            }

            if (!hotkeysEnabled.get()) {
                if (verboseLogging) filterSkips.add("hotkeys disabled")
                return
            }
            if (e.keyCode != NativeKeyEvent.VC_CONTROL) return

            // Only fire if the binding exists, is enabled, AND the user
            // has not opted out of the double-Ctrl mechanism specifically.
            val binding = bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            if (binding == null || !binding.isEnabled || !binding.isDoubleCtrlEnabled) {
                if (verboseLogging) filterSkips.add("binding disabled")
                return
            }

            val cooldown = binding.doubleCtrlCooldownMs
            val debounce = binding.doubleCtrlDebounceMs
            val now = System.currentTimeMillis()

            // If within cooldown — log and skip
            if (now - lastTriggerTime < cooldown) {
                endSequence("within cooldown (${now - lastTriggerTime}ms < ${cooldown}ms)", "SKIP")
                return
            }
            // If within debounce — log and skip
            if (now - lastCtrlTime < debounce) {
                endSequence("within debounce (${now - lastCtrlTime}ms < ${debounce}ms)", "SKIP")
                return
            }
            // If other keys were pressed between Ctrl releases — log and skip
            if (keysBetweenCtrl.isNotEmpty()) {
                endSequence("other key(s) between: ${keysBetweenCtrl.joinToString()}", "SKIP")
                lastCtrlTime = now
                return
            }
            // If within threshold — trigger
            if (now - lastCtrlTime < threshold) {
                lastTriggerTime = now
                endSequence("gap=${now - lastCtrlTime}ms within threshold", "TRIGGER")
                scope.launch { showMainWindow() }
            } else {
                endSequence("gap=${now - lastCtrlTime}ms beyond threshold", "SKIP")
            }
            lastCtrlTime = now
        }
    }

    /**
     * Outcome of trying to copy whatever the user currently has selected.
     *
     * The three cases must stay distinct. An unchanged clipboard after a simulated
     * Ctrl+C means *"nothing was selected"* — it does **not** mean the clipboard
     * content is the selection. Collapsing those two is what allowed a stale
     * clipboard to overwrite the input a moment after a real selection had already
     * been translated.
     */
    private sealed interface Capture {
        /** A fresh selection was copied. */
        data class Text(val value: String) : Capture
        /** Ctrl+C produced nothing new — the user had no selection. */
        data object None : Capture
        /** A capture is already in flight; this invocation is an echo. */
        data object Busy : Capture
    }

    /**
     * Copies the current selection via a simulated Ctrl+C, then restores the
     * clipboard to whatever the user had in it.
     *
     * Success is decided by comparing the clipboard before and after: if it did
     * not change, the focused app had nothing to give us.
     */
    private suspend fun captureSelection(): Capture {
        if (!clipboardLock.compareAndSet(false, true)) return Capture.Busy
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val original  = runCatching { clipboard.getContents(null) }.getOrNull()
            val before    = readClipboardText(clipboard)

            var copied: String? = null
            for (attempt in 1..COPY_ATTEMPTS) {
                simulateCopy()
                delay(CLIPBOARD_SETTLE_MS)
                syntheticInput.set(false)

                val after = readClipboardText(clipboard)
                if (!after.isNullOrEmpty() && after != before) {
                    copied = after
                    break
                }
            }

            original?.let { runCatching { clipboard.setContents(it, null) } }

            return copied?.let { Capture.Text(it) }
                ?: Capture.None.also { logger?.debug("captureSelection: no selection captured") }
        } finally {
            syntheticInput.set(false)
            clipboardLock.set(false)
        }
    }

    /**
     * Opens the main window. When there is no selection the window still opens,
     * but the input is left untouched — filling it from the clipboard here is
     * what produced the "translated text replaced by stale clipboard" bug.
     */
    private suspend fun showMainWindow() {
        when (val capture = captureSelection()) {
            is Capture.Text -> onShowApp(capture.value)
            Capture.None    -> onShowApp(null)
            Capture.Busy    -> Unit
        }
    }

    /**
     * Captures the selection for actions that are pointless without text
     * (Quick Translate, dictionary, TTS, inline replace).
     *
     * These keep the clipboard fallback: they have no existing input for a stale
     * value to destroy, and acting on the clipboard when nothing is selected is
     * long-standing behaviour.
     */
    private suspend fun handleSelectedText(callback: (String) -> Unit) {
        val text = when (val capture = captureSelection()) {
            is Capture.Text -> capture.value
            Capture.None    -> readClipboardText(Toolkit.getDefaultToolkit().systemClipboard).orEmpty()
            Capture.Busy    -> return
        }
        if (text.isNotEmpty()) callback(text)
    }

    private fun readClipboardText(clipboard: Clipboard): String? = runCatching {
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
            clipboard.getData(DataFlavor.stringFlavor).toString().trim()
        else null
    }.getOrNull()

    /**
     * Injects Ctrl+C at OS level. Raises [syntheticInput] first so the native hook
     * discards the keys we are about to press; the caller lowers it once the
     * clipboard has settled.
     */
    private fun simulateCopy() {
        syntheticInput.set(true)
        runCatching {
            val robot = Robot()
            robot.autoDelay = 20
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_CONTROL)
        }.onFailure { logger?.error("Copy simulation failed", it) }
    }

    private companion object {
        /** How many times to retry the copy before deciding nothing is selected. */
        const val COPY_ATTEMPTS = 2

        /** Time given to the focused app to put the selection on the clipboard. */
        const val CLIPBOARD_SETTLE_MS = 50L
    }
}