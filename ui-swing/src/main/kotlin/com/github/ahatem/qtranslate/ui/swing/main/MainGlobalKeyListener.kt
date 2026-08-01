package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.hotkey.DoubleCtrlDetector
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
    // Pure state machine that decides double-Ctrl. Accessed only from the hook's
    // dispatch thread (all four entry points are called from CustomSequenceListener).
    private val doubleCtrl = DoubleCtrlDetector()
    private val sequenceListener = CustomSequenceListener()
    private val clipboardLock = AtomicBoolean(false)
    private val hotkeysEnabled = AtomicBoolean(true)
    // Deadline until which hook events are treated as our own Robot-injected input.
    //
    // A plain boolean lowered right after the injection was not enough: JNativeHook
    // delivers on its own dispatch thread, so a synthetic Ctrl **release** could
    // land after the flag went down and be read as a real, press-less tap — two of
    // those in a row opened the window with nobody touching the keyboard. A
    // deadline keeps late arrivals covered without having to guess when the queue
    // has drained.
    @Volatile private var syntheticInputUntil = 0L

    private fun isSyntheticInput(): Boolean = System.currentTimeMillis() <= syntheticInputUntil
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
            logger?.error("Hotkey initialization failed", e)
        }
    }

    fun updateBindings(newBindings: List<HotkeyBinding>) {
        bindings = newBindings
        if (!initialized.get()) return
        try {
            provider?.reset()
            registerGlobalHotkeys()
        } catch (e: Exception) {
            logger?.error("Failed to update hotkey bindings", e)
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

        logger?.warn("[Hotkeys] Hotkey system appears dead, attempting re-initialization...")
        try {
            // Clean up dead components
            try {
                if (nativeHookRegistered) {
                    GlobalScreen.removeNativeKeyListener(sequenceListener)
                    GlobalScreen.unregisterNativeHook()
                    nativeHookRegistered = false
                }
            } catch (_: Exception) { }

            // A dead hook may have missed key-up events; start from a clean slate
            // so no phantom "held" key blocks the gesture after recovery.
            doubleCtrl.reset()

            // Re-initialize everything
            initJKeyMaster()
            initJNativeHook()
            logger?.info("[Hotkeys] Hotkey system re-initialized successfully")
            return true
        } catch (e: Exception) {
            logger?.error("[Hotkeys] Re-initialization failed", e)
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
            logger?.error("Hotkey manager shutdown error", e)
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
                    logger?.debug("[Hotkeys] Registered GLOBAL ${action.name}: $keyStroke")
                }.onFailure { ex ->
                    logger?.warn("[Hotkeys] Failed to register GLOBAL ${action.name} ($keyStroke): ${ex.message}")
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
        catch (e: Exception) { logger?.error("Enable hotkeys failed", e) }
    }

    private fun disableHotkeys() {
        try { provider?.reset() }
        catch (e: Exception) { logger?.error("Disable hotkeys failed", e) }
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

        // --- Key tracing (diagnostic only, gated by [keyTracing]) ---
        // A 1-second window opens on a Ctrl or Shift press and records every key in
        // it, prefixed by a snapshot of what was ALREADY held when it opened. That
        // snapshot is what reveals "Shift was down before Ctrl" — the shape of a
        // false trigger — and a key stuck in it flags a leak after long uptime.
        // None of this affects the decision, which lives entirely in [doubleCtrl].
        // Caveat: while the flag is on, ordinary typing keeps re-opening the window
        // via Shift, so the trace WILL contain typed text. Diagnostic use only.
        private var traceOpenUntil = 0L
        private var traceWindowStart = 0L
        private var lastTraceTime = 0L
        private val tracedHeld = HashSet<Int>()   // physically-held keys, for the snapshot

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

        private fun isTraceOpener(code: Int): Boolean =
            code == NativeKeyEvent.VC_CONTROL || code == NativeKeyEvent.VC_SHIFT

        /**
         * Opens — or extends — the 1s trace window on a Ctrl/Shift press, logging a
         * snapshot of the keys already held at that moment. Call before adding the
         * current key to [tracedHeld] so the snapshot reflects the prior state.
         */
        private fun openTraceWindow(code: Int, now: Long) {
            if (!keyTracing || !isTraceOpener(code)) return
            val alreadyOpen = now <= traceOpenUntil
            traceOpenUntil = now + TRACE_WINDOW_MS
            if (!alreadyOpen) {
                traceWindowStart = now
                lastTraceTime = now
                val held = tracedHeld.joinToString { keyName(it) }.ifEmpty { "none" }
                logger?.debug("[KeyLog] === window opened by ${keyName(code)}; already-held=[$held] ===")
            }
        }

        /** Logs one key event if the trace window is currently open. */
        private fun traceEvent(type: String, code: Int, now: Long) {
            if (!keyTracing || now > traceOpenUntil) return
            val elapsed = now - traceWindowStart
            val gap = now - lastTraceTime
            lastTraceTime = now
            logger?.debug("[KeyLog] t+${elapsed}ms $type ${keyName(code)}(0x${code.toString(16)}) gap=${gap}ms")
        }

        /**
         * Logs a verdict. TRIGGERED and UNPAIRED_RELEASE are always recorded — they
         * are rare, they carry no key identities, and together they are enough to
         * explain a window that opened by itself: whether a trigger happened at all,
         * and whether press-less releases are being rejected. The routine verdicts
         * (CONTAMINATED on every Ctrl+C, WAITING on every lone Ctrl) would swamp the
         * log, so they stay behind [keyTracing].
         *
         * The gap and held-key count are numbers, not keystrokes: a machine-regular
         * gap or a non-zero hold count is what distinguishes a synthetic trigger
         * from a deliberate one.
         */
        private fun logVerdict(result: DoubleCtrlDetector.Result, acted: Boolean) {
            val always = result == DoubleCtrlDetector.Result.TRIGGERED ||
                result == DoubleCtrlDetector.Result.UNPAIRED_RELEASE
            if (!keyTracing && !always) return

            val detail = when (result) {
                DoubleCtrlDetector.Result.TRIGGERED ->
                    " gap=${doubleCtrl.lastGapMs}ms held=${doubleCtrl.heldKeyCount}" +
                        if (acted) "" else " (suppressed)"
                DoubleCtrlDetector.Result.UNPAIRED_RELEASE ->
                    " (release with no press — ignored)"
                else -> ""
            }
            logger?.debug("[DoubleCtrl] $result$detail")
        }

        override fun nativeKeyPressed(e: NativeKeyEvent) {
            // Our own Robot-injected Ctrl+C — not a user keystroke.
            if (isSyntheticInput()) return
            val now = System.currentTimeMillis()

            if (e.keyCode == NativeKeyEvent.VC_CONTROL) doubleCtrl.onCtrlPressed(now)
            else doubleCtrl.onOtherKeyDown(e.keyCode, now)

            openTraceWindow(e.keyCode, now)        // snapshot reflects state BEFORE this key
            traceEvent("pressed ", e.keyCode, now)
            if (keyTracing) tracedHeld.add(e.keyCode)
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            // Our own Robot-injected Ctrl+C — not a user keystroke.
            if (isSyntheticInput()) return
            val now = System.currentTimeMillis()

            traceEvent("released", e.keyCode, now)
            if (keyTracing) tracedHeld.remove(e.keyCode)

            if (e.keyCode != NativeKeyEvent.VC_CONTROL) {
                doubleCtrl.onOtherKeyUp(e.keyCode)
                return
            }

            // Keep the detector fed with the current sensitivity, then let it decide.
            // The binding may disable the gesture; we still run the machine so its
            // state stays consistent, and only gate the resulting action.
            val binding = bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            if (binding != null) {
                doubleCtrl.configure(binding.doubleCtrlDebounceMs, binding.doubleCtrlCooldownMs)
            }

            val result = doubleCtrl.onCtrlReleased(now)
            if (result != DoubleCtrlDetector.Result.TRIGGERED) {
                logVerdict(result, acted = false)
                return
            }

            val enabled = hotkeysEnabled.get() &&
                binding != null && binding.isEnabled && binding.isDoubleCtrlEnabled
            logVerdict(result, acted = enabled)
            if (enabled) scope.launch { showMainWindow() }
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
            // Let the user's physical modifier release reach the foreground app
            // before injecting ours. The gesture ends ON a Ctrl release and we act
            // within a millisecond, so without this the still-in-flight physical
            // Ctrl-up cancels our synthetic Ctrl-down: the target then receives a
            // bare "C", which types a character over the user's selection instead
            // of copying it. In a non-Latin layout that character is whatever the
            // C key produces there.
            delay(MODIFIER_SETTLE_MS)

            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val original  = runCatching { clipboard.getContents(null) }.getOrNull()
            val before    = readClipboardText(clipboard)

            var copied: String? = null
            for (attempt in 1..COPY_ATTEMPTS) {
                simulateCopy()
                delay(CLIPBOARD_SETTLE_MS)

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
            // The guard is a deadline, so it lapses on its own once the injected
            // events have had time to arrive — clearing it here would re-open the
            // very race it exists to close.
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
     * Injects Ctrl+C at OS level, arming [syntheticInputUntil] around the injection
     * so the native hook discards both the keys we press and any that reach its
     * dispatch thread late.
     */
    private fun simulateCopy() {
        syntheticInputUntil = System.currentTimeMillis() + SYNTHETIC_GUARD_MS
        runCatching {
            val robot = Robot()
            robot.autoDelay = ROBOT_KEY_DELAY_MS
            robot.keyPress(KeyEvent.VK_CONTROL)
            // Give the target app time to register Ctrl as down before C arrives;
            // if it does not, C lands as a literal character on the selection.
            robot.delay(ROBOT_MODIFIER_HOLD_MS)
            robot.keyPress(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_CONTROL)
        }.onFailure { logger?.error("Copy simulation failed", it) }
        // Re-arm from the end of the injection: the last release still has to travel
        // through the hook's dispatch queue before it reaches us.
        syntheticInputUntil = System.currentTimeMillis() + SYNTHETIC_GUARD_MS
    }

    private companion object {
        /**
         * System property enabling per-keystroke tracing: `-Dqtranslate.keytrace=true`.
         *
         * Off by default on purpose. When on, every key pressed within a second of a
         * Ctrl press is written to the log by name, which is exactly what a keylogger
         * does — useful for diagnosing false double-Ctrl triggers, unacceptable as a
         * default. Turning it off never changes which sequences trigger, only what
         * gets recorded.
         */
        const val KEY_TRACE_PROPERTY = "qtranslate.keytrace"

        val keyTracing: Boolean = System.getProperty(KEY_TRACE_PROPERTY).toBoolean()

        /** How long the key-trace window stays open after a Ctrl/Shift press. */
        const val TRACE_WINDOW_MS = 1000L

        /**
         * How long after injecting Ctrl+C the hook keeps discarding events. Covers
         * the trip through JNativeHook's dispatch queue; short enough that real
         * keystrokes right after a capture are not swallowed for long.
         */
        const val SYNTHETIC_GUARD_MS = 250L

        /** How many times to retry the copy before deciding nothing is selected. */
        const val COPY_ATTEMPTS = 2

        /**
         * Pause before injecting Ctrl+C, so the user's own modifier release has
         * reached the foreground app first. Without it the injected Ctrl is
         * cancelled mid-flight and the C types into their document.
         */
        const val MODIFIER_SETTLE_MS = 150L

        /** Robot's pause between individual synthetic key events. */
        const val ROBOT_KEY_DELAY_MS = 20

        /** Extra hold after pressing Ctrl before C is sent. */
        const val ROBOT_MODIFIER_HOLD_MS = 40

        /** Time given to the focused app to put the selection on the clipboard. */
        const val CLIPBOARD_SETTLE_MS = 50L
    }
}