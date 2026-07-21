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
}
