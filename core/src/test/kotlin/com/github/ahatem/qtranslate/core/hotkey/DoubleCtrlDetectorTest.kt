package com.github.ahatem.qtranslate.core.hotkey

import kotlin.test.Test
import kotlin.test.assertEquals

class DoubleCtrlDetectorTest {

    // Default params: debounce=80, cooldown=1000, threshold=400

    @Test
    fun firstTapReturnsWaiting() {
        val detector = DoubleCtrlDetector()
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1000))
    }

    @Test
    fun secondTapWithinThresholdTriggers() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlReleased(1000)
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(1300))  // +300ms < 400ms
    }

    @Test
    fun secondTapAfterThresholdIsWaiting() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlReleased(1000)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1500))  // +500ms > 400ms
    }

    @Test
    fun chatterWithinDebounceIsFiltered() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlReleased(1000)
        assertEquals(DoubleCtrlDetector.Result.CHATTER_FILTERED, detector.onCtrlReleased(1050))  // +50ms < 80ms
    }

    @Test
    fun chatterAfterDebounceButWithinThresholdTriggers() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlReleased(1000)
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(1100))  // +100ms: >80ms, <400ms
    }

    @Test
    fun cooldownSuppressesAfterTrigger() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlReleased(1000)
        detector.onCtrlReleased(1300)  // trigger
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.onCtrlReleased(1500))  // +200ms < 1000ms
    }

    @Test
    fun cooldownExpiresAndAllowsNewDoubleTap() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlReleased(1000)
        detector.onCtrlReleased(1300)  // trigger
        // 2500 - 1300 = 1200 > 1000 cooldown, new first tap
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(2500))
    }

    @Test
    fun fullCycle() {
        val detector = DoubleCtrlDetector()
        // First double-tap
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1000))
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(1300))
        // Cooldown
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.onCtrlReleased(1800))
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.onCtrlReleased(2200))
        // Cooldown expired (2500 - 1300 = 1200 > 1000)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(2500))
        // Second double-tap
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(2800))
    }

    @Test
    fun resetClearsAllState() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlReleased(1000)
        detector.onCtrlReleased(1300)  // trigger
        detector.reset()
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(5000))
    }

    @Test
    fun customDebounceAndCooldown() {
        val detector = DoubleCtrlDetector(debounce = 50, cooldown = 500, threshold = 300)
        detector.onCtrlReleased(1000)
        assertEquals(DoubleCtrlDetector.Result.CHATTER_FILTERED, detector.onCtrlReleased(1030))  // 30ms < 50ms
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(1200))  // >50ms, <300ms
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.onCtrlReleased(1500))  // 300ms < 500ms
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1800))  // 600ms > 500ms
    }

    @Test
    fun threeRapidTapsFirstTriggersRestFiltered() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlReleased(1000)  // tap 1
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(1200))  // tap 2: trigger
        assertEquals(DoubleCtrlDetector.Result.COOLDOWN_ACTIVE, detector.onCtrlReleased(1300))  // tap 3: cooldown
    }

    // --- Contamination: the behaviour the old filter got wrong ---
    // Arbitrary non-Ctrl key codes; the detector treats them as opaque set members.

    private val SHIFT = 1
    private val WIN = 2
    private val C = 3
    private val PRINT_SCREEN = 4

    @Test
    fun ctrlPlusCIsNotATap() {
        val detector = DoubleCtrlDetector()
        // Ctrl down, C down, C up, Ctrl up — a copy shortcut.
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
        // Ctrl+C, then a single clean Ctrl tap soon after must NOT trigger:
        // the combo must not count as the first tap of a double.
        detector.onCtrlPressed(990)
        detector.onOtherKeyDown(C, 995)
        detector.onOtherKeyUp(C)
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1000))
        detector.onCtrlPressed(1050)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1100))
    }

    @Test
    fun keyBetweenTapsBreaksTheDouble() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlPressed(990)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1000))  // clean first tap
        detector.onOtherKeyDown(C, 1050)                                                // something in between
        detector.onOtherKeyUp(C)
        detector.onCtrlPressed(1100)
        // Second tap is clean in itself, but the window was contaminated → restart.
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1150))
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
        detector.onCtrlPressed(2990)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(3000))
        detector.onCtrlPressed(3190)
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(3200))
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
    fun cleanDoubleTapWithExplicitPressEvents() {
        val detector = DoubleCtrlDetector()
        detector.onCtrlPressed(990)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(1000))
        detector.onCtrlPressed(1240)
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(1250))
    }

    @Test
    fun leakedKeyWithSwallowedReleaseStopsBlockingAfterTimeout() {
        val detector = DoubleCtrlDetector()
        val f6 = 6

        // F6 pressed in a game; its release is swallowed, so it never comes.
        detector.onOtherKeyDown(f6, 1000)

        // Right away, F6 is genuinely "held" → Ctrl taps are contaminated.
        detector.onCtrlPressed(1100)
        assertEquals(DoubleCtrlDetector.Result.CONTAMINATED, detector.onCtrlReleased(1150))

        // More than five seconds later, the phantom hold is treated as leaked and
        // ignored, so double-tap works again without ever seeing an F6 release.
        detector.onCtrlPressed(7000)
        assertEquals(DoubleCtrlDetector.Result.WAITING, detector.onCtrlReleased(7000))
        detector.onCtrlPressed(7250)
        assertEquals(DoubleCtrlDetector.Result.TRIGGERED, detector.onCtrlReleased(7300))
    }
}
