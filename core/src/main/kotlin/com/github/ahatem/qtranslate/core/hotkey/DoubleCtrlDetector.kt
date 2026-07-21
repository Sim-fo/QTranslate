package com.github.ahatem.qtranslate.core.hotkey

/**
 * Detects double-tap Ctrl with debounce and cooldown protection.
 *
 * Extracted from [com.github.ahatem.qtranslate.ui.swing.main.MainGlobalKeyListener]
 * for testability. The detector is a pure state machine — no I/O, no threading.
 *
 * ### Parameters
 * - **debounce** — releases closer than this (ms) are keyboard chatter, ignored.
 * - **cooldown** — after a successful trigger, suppress new triggers for this long.
 * - **threshold** — maximum gap (ms) between two taps to count as a double-tap.
 *
 * ### State transitions
 * ```
 * IDLE → (release within debounce) → IDLE  (chatter, ignored)
 * IDLE → (release within threshold) → TRIGGERED
 * TRIGGERED → (release within cooldown) → TRIGGERED  (suppressed)
 * TRIGGERED → (release after cooldown) → IDLE  (ready for next double-tap)
 * ```
 */
class DoubleCtrlDetector(
    private val debounce: Int = 80,
    private val cooldown: Int = 1000,
    private val threshold: Int = 400
) {
    /**
     * Result of processing a single Ctrl release event.
     */
    enum class Result {
        /** Chatter filtered — release was too close to the previous one. */
        CHATTER_FILTERED,
        /** Cooldown active — trigger was suppressed. */
        COOLDOWN_ACTIVE,
        /** Double-tap detected — fire the action. */
        TRIGGERED,
        /** First tap recorded — waiting for the second. */
        WAITING
    }

    private var lastCtrlTime = 0L
    private var lastTriggerTime = 0L

    /**
     * Process a Ctrl release event at the given [now] timestamp (ms).
     * Returns [Result] indicating what happened.
     */
    fun onCtrlReleased(now: Long): Result {
        // Cooldown: suppress if we triggered recently.
        // Do NOT update lastCtrlTime — after cooldown expires, the next release
        // must be a fresh first tap, not a false second tap.
        if (now - lastTriggerTime < cooldown) {
            return Result.COOLDOWN_ACTIVE
        }

        // Chatter: ignore if this release is too close to the previous one.
        if (now - lastCtrlTime < debounce) {
            return Result.CHATTER_FILTERED
        }

        // Double-tap check: is this within the threshold of the first tap?
        if (now - lastCtrlTime < threshold) {
            lastTriggerTime = now
            lastCtrlTime = now
            return Result.TRIGGERED
        }

        // First tap — just record the time.
        lastCtrlTime = now
        return Result.WAITING
    }

    /** Reset all state — useful for testing. */
    fun reset() {
        lastCtrlTime = 0L
        lastTriggerTime = 0L
    }
}
