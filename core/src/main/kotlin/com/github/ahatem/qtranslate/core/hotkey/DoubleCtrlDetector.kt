package com.github.ahatem.qtranslate.core.hotkey

/**
 * Decides whether a stream of raw keyboard events is a deliberate **double-tap of
 * Ctrl** — the gesture that shows the main window.
 *
 * A double tap counts only when Ctrl is tapped twice **cleanly**: no other key
 * touched while Ctrl was down, and no other key in between the two taps. That is
 * what separates the gesture from ordinary shortcuts that merely involve Ctrl —
 * Ctrl+C, Ctrl+Shift (a layout switch), Ctrl+Win, and so on.
 *
 * The detector is a pure state machine: no threading, no I/O, no knowledge of the
 * key-event library. The caller routes events to four entry points and acts on the
 * [Result] returned by [onCtrlReleased].
 *
 * ### Why a set of held keys, not a counter
 * Auto-repeat sends many key-**down** events for one physically held key but a
 * single key-**up**. A `+1/-1` counter would leak on every held key and eventually
 * wedge the detector shut. [heldOtherKeys] stores key codes, so a key repeating 50
 * times is still one member, cleared by its single release.
 *
 * ### Parameters
 * - **debounce** — two releases closer than this (ms) are keyboard chatter (one
 *   physical release bouncing); the extra one is ignored.
 * - **cooldown** — after a trigger, suppress further triggers for this long, so the
 *   window is not re-shown by a lingering third tap.
 * - **threshold** — maximum gap (ms) between two clean taps to count as a double.
 */
class DoubleCtrlDetector(
    debounce: Int = 80,
    cooldown: Int = 1000,
    threshold: Int = 400
) {
    /** What a single Ctrl release meant. */
    enum class Result {
        /** Release too close to the previous one — keyboard bounce. */
        CHATTER_FILTERED,
        /** A trigger fired recently; this release is suppressed. */
        COOLDOWN_ACTIVE,
        /** Ctrl was combined with another key — a shortcut, not a tap. */
        CONTAMINATED,
        /** A Ctrl release arrived with no matching press — not a tap at all. */
        UNPAIRED_RELEASE,
        /** Clean double tap detected — show the window. */
        TRIGGERED,
        /** First clean tap recorded — waiting for the second. */
        WAITING
    }

    private var debounce = debounce
    private var cooldown = cooldown
    private var threshold = threshold

    /** Non-Ctrl keys currently held down: key code → press timestamp (ms). */
    private val heldOtherKeys = HashMap<Int, Long>()

    /** The in-progress Ctrl hold has been combined with another key. */
    private var pressContaminated = false

    /**
     * Whether the Ctrl release we are waiting for has a press behind it.
     *
     * A release with no press is not a keystroke the user made in front of us: the
     * press was either swallowed by a foreground app that grabs raw input (game
     * engines, full-screen apps) or belongs to synthetic input. Counting it as a
     * tap is what let the window open with nobody touching Ctrl.
     */
    private var ctrlPressSeen = false

    /** Another key has been involved since the first tap of the current window. */
    private var windowContaminated = false

    /** Timestamp of the last clean Ctrl release (0 = none pending). */
    private var lastTapTime = 0L

    /** Timestamp of the last successful trigger. */
    private var lastTriggerTime = 0L

    /**
     * Updates the timing parameters without disturbing the state machine, so
     * settings changes take effect mid-session. [threshold] has no user setting
     * and is left untouched.
     */
    fun configure(debounceMs: Int, cooldownMs: Int) {
        debounce = debounceMs
        cooldown = cooldownMs
    }

    /** A non-Ctrl key went down at [now]. */
    fun onOtherKeyDown(keyCode: Int, now: Long) {
        heldOtherKeys[keyCode] = now
        contaminate()
    }

    /**
     * A non-Ctrl key went up. Contaminates even if we never saw the matching
     * press (the OS can swallow a press inside a system combo), so a key we only
     * see released still disqualifies the current window.
     */
    fun onOtherKeyUp(keyCode: Int) {
        heldOtherKeys.remove(keyCode)
        contaminate()
    }

    private fun contaminate() {
        pressContaminated = true
        windowContaminated = true
    }

    /**
     * Ctrl went down at [now]. The tap is born dirty if another key is genuinely
     * held.
     *
     * A key still "held" after [STALE_HOLD_MS] almost certainly leaked — its
     * release was swallowed, which happens routinely when a full-screen game or
     * another app grabs the key-up. Such phantom holds are dropped here, so one
     * lost release cannot mark every future Ctrl tap as a shortcut forever.
     */
    fun onCtrlPressed(now: Long) {
        heldOtherKeys.entries.removeAll { now - it.value > STALE_HOLD_MS }
        pressContaminated = heldOtherKeys.isNotEmpty()
        ctrlPressSeen = true
    }

    /**
     * Ctrl went up. Returns what this release means; only [Result.TRIGGERED]
     * should show the window.
     */
    fun onCtrlReleased(now: Long): Result {
        val contaminated = pressContaminated
        val paired = ctrlPressSeen
        pressContaminated = false
        ctrlPressSeen = false

        // No press behind this release: ignore it outright, and leave any pending
        // first tap alone so a stray release neither starts nor completes a double.
        if (!paired) return Result.UNPAIRED_RELEASE

        // Cooldown: swallow everything, including a stray third tap after a trigger.
        if (now - lastTriggerTime < cooldown) {
            lastTapTime = 0L
            windowContaminated = false
            return Result.COOLDOWN_ACTIVE
        }

        // The Ctrl hold touched another key — this was a shortcut. Cancel any
        // pending first tap so a later lone Ctrl cannot pair with it.
        if (contaminated) {
            lastTapTime = 0L
            windowContaminated = false
            return Result.CONTAMINATED
        }

        // Keyboard bounce: keep the first tap, ignore this duplicate.
        if (lastTapTime != 0L && now - lastTapTime < debounce) {
            return Result.CHATTER_FILTERED
        }

        // Second clean tap, close enough, with nothing else in between.
        if (lastTapTime != 0L && !windowContaminated && now - lastTapTime < threshold) {
            lastTriggerTime = now
            lastTapTime = 0L
            windowContaminated = false
            return Result.TRIGGERED
        }

        // First clean tap — or a restart after a stale or contaminated window.
        lastTapTime = now
        windowContaminated = false
        return Result.WAITING
    }

    /** Clears all state. */
    fun reset() {
        heldOtherKeys.clear()
        pressContaminated = false
        ctrlPressSeen = false
        windowContaminated = false
        lastTapTime = 0L
        lastTriggerTime = 0L
    }

    private companion object {
        /**
         * How long a non-Ctrl key may be "held" before it is treated as a leaked
         * (swallowed-release) key and ignored. A real key involved in a shortcut is
         * held far under a second; only a lost release keeps one down for seconds.
         */
        const val STALE_HOLD_MS = 5_000L
    }
}
