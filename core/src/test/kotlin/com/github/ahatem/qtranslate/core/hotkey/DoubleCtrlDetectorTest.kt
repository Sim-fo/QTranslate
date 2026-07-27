package com.github.ahatem.qtranslate.core.hotkey

import kotlin.test.Test
import kotlin.test.assertEquals

class DoubleCtrlDetectorTest {

    // Default params: debounce=80, cooldown=1000, threshold=400

    // Arbitrary non-Ctrl key codes; the detector treats them as opaque map keys.
    private val SHIFT = 1
    private val WIN = 2
    private val C = 3
    private val PRINT_SCREEN = 4
    private val F6 = 6

    /**
     * A complete Ctrl tap: press then release. Real hardware always delivers both,
     * and the detector now requires the pair, so tests must supply it too.
     */
    private fun DoubleCtrlDetector.tap(releaseAt: Long, holdMs: Long = 10): DoubleCtrlDetector.Result {
        onCtrlPressed(releaseAt - holdMs)
        return onCtrlReleased(releaseAt)
    }

    // --- Timing ---

    @Test
    fun firstTapReturnsWaiting() {
        val detector = DoubleCtrlDetector()
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1000))
    }

    @Test
    fun secondTapWithinThresholdTriggers() {
        val detector = DoubleCtrlDetector()
        detector.tap(1000)
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(1300))  // +300ms < 400ms
    }

    @Test
    fun secondTapAfterThresholdIsWaiting() {
        val detector = DoubleCtrlDetector()
        detector.tap(1000)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1500))  // +500ms > 400ms
    }

    @Test
    fun chatterWithinDebounceIsFiltered() {
        val detector = DoubleCtrlDetector()
        detector.tap(1000)
        assertEquals(DoubleCtrlDetector.Result.CHATTER_FILTERED, detector.tap(1050, holdMs = 5))  // +50ms < 80ms
    }

    @Test
    fun chatterAfterDebounceButWithinThresholdTriggers() {
        val detector = DoubleCtrlDetector()
        detector.tap(1000)
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(1100))  // +100ms: >80ms, <400ms
    }

    @Test
    fun cooldownSuppressesAfterTrigger() {
        val detector = DoubleCtrlDetector()
        detector.tap(1000)
        detector.tap(1300)  // trigger
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.tap(1500))  // +200ms < 1000ms
    }

    @Test
    fun cooldownExpiresAndAllowsNewDoubleTap() {
        val detector = DoubleCtrlDetector()
        detector.tap(1000)
        detector.tap(1300)  // trigger
        // 2500 - 1300 = 1200 > 1000 cooldown, new first tap
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(2500))
    }

    @Test
    fun fullCycle() {
        val detector = DoubleCtrlDetector()
        // First double-tap
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1000))
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(1300))
        // Cooldown
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.tap(1800))
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.tap(2200))
        // Cooldown expired (2500 - 1300 = 1200 > 1000)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(2500))
        // Second double-tap
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(2800))
    }

    @Test
    fun resetClearsAllState() {
        val detector = DoubleCtrlDetector()
        detector.tap(1000)
        detector.tap(1300)  // trigger
        detector.reset()
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(5000))
    }

    @Test
    fun customDebounceAndCooldown() {
        val detector = DoubleCtrlDetector(debounce = 50, cooldown = 500, threshold = 300)
        detector.tap(1000)
        assertEquals(DoubleCtrlDetector.Result.CHATTER_FILTERED, detector.tap(1030, holdMs = 5))  // 30ms < 50ms
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(1200))  // >50ms, <300ms
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.tap(1500))  // 300ms < 500ms
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1800))  // 600ms > 500ms
    }

    @Test
    fun threeRapidTapsFirstTriggersRestFiltered() {
        val detector = DoubleCtrlDetector()
        detector.tap(1000)                                                                 // tap 1
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(1200))              // tap 2: trigger
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.tap(1300))        // tap 3: cooldown
    }

    // --- Contamination: Ctrl used as part of a shortcut is not a tap ---

    @Test
    fun ctrlPlusCIsNotATap() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlPressed(990)
        detector.onOtherKeyDown(C, 995)
        detector.onOtherKeyUp(C)
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1000))
    }

    @Test
    fun shiftHeldFirstThenCtrlIsNotATap() {
        val detector = DoubleCtrlDetector()
        // Layout switch: Shift is already held when Ctrl is tapped, twice.
        detector.onOtherKeyDown(SHIFT, 900)
        detector.onCtrlPressed(950)
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1000))
        detector.onCtrlPressed(1100)
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1150))
    }

    @Test
    fun comboDoesNotArmALaterLoneCtrl() {
        val detector = DoubleCtrlDetector()
        // Ctrl+C, then a single clean Ctrl tap soon after must NOT trigger.
        detector.onCtrlPressed(990)
        detector.onOtherKeyDown(C, 995)
        detector.onOtherKeyUp(C)
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1000))
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1100))
    }

    @Test
    fun keyBetweenTapsBreaksTheDouble() {
        val detector = DoubleCtrlDetector()
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1000))  // clean first tap
        detector.onOtherKeyDown(C, 1050)                                     // something in between
        detector.onOtherKeyUp(C)
        // Second tap is clean in itself, but the window was contaminated → restart.
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1150))
    }

    @Test
    fun autorepeatWinDoesNotLeakAndBlockLaterDoubleTap() {
        val detector = DoubleCtrlDetector()
        // Ctrl held, Win auto-repeats 20 down events, single up. A counter would
        // leak +19 and wedge the detector; a map keyed by code must not.
        detector.onCtrlPressed(990)
        repeat(20) { detector.onOtherKeyDown(WIN, 995) }
        detector.onOtherKeyUp(WIN)
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1000))

        // Later, a genuine clean double tap still works.
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(3000))
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(3200))
    }

    @Test
    fun swallowedPressStillContaminatesViaRelease() {
        val detector = DoubleCtrlDetector()
        // PrintScreen commonly delivers only a release. It must still poison the tap.
        detector.onCtrlPressed(990)
        detector.onOtherKeyUp(PRINT_SCREEN)   // release with no matching press
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1000))
    }

    @Test
    fun leakedKeyWithSwallowedReleaseStopsBlockingAfterTimeout() {
        val detector = DoubleCtrlDetector()
        // F6 pressed in a game; its release is swallowed, so it never comes.
        detector.onOtherKeyDown(F6, 1000)

        // Right away, F6 is genuinely "held" → Ctrl taps are contaminated.
        detector.onCtrlPressed(1100)
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1150))

        // More than five seconds later the phantom hold is treated as leaked and
        // ignored, so double-tap works again without ever seeing an F6 release.
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(7000))
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(7250))
    }

    // --- Unpaired releases: a release with no press is not a keystroke ---

    @Test
    fun loneReleaseWithoutPressIsIgnored() {
        val detector = DoubleCtrlDetector()
        assertEquals(DoubleCtrlDetector.Result.UNPAIRED_RELEASE, detector.onCtrlReleased(1000))
    }

    @Test
    fun twoUnpairedReleasesDoNotTrigger() {
        val detector = DoubleCtrlDetector()
        // Exactly the observed failure: two press-less Ctrl releases ~175ms apart
        // used to read as a clean double tap and opened the window on their own.
        assertEquals(DoubleCtrlDetector.Result.UNPAIRED_RELEASE, detector.onCtrlReleased(1000))
        assertEquals(DoubleCtrlDetector.Result.UNPAIRED_RELEASE, detector.onCtrlReleased(1175))
    }

    @Test
    fun unpairedReleaseDoesNotCompleteAPendingDoubleTap() {
        val detector = DoubleCtrlDetector()
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1000))                     // real first tap
        assertEquals(DoubleCtrlDetector.Result.UNPAIRED_RELEASE, detector.onCtrlReleased(1150)) // stray release
        // The pending tap survives untouched: a genuine second tap still triggers.
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.tap(1300))
    }

    @Test
    fun unpairedReleaseDoesNotArmALaterTap() {
        val detector = DoubleCtrlDetector()
        assertEquals(DoubleCtrlDetector.Result.UNPAIRED_RELEASE, detector.onCtrlReleased(1000))
        // The stray release must not count as the first tap of a double.
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.tap(1150))
    }

    @Test
    fun pressIsConsumedByItsReleaseSoTheNextReleaseIsUnpaired() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlPressed(990)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1000))
        // A duplicate release for the same press (no new press) is not a tap.
        assertEquals(DoubleCtrlDetector.Result.UNPAIRED_RELEASE, detector.onCtrlReleased(1100))
    }
}
