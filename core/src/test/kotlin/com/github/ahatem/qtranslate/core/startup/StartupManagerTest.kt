package com.github.ahatem.qtranslate.core.startup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupManagerTest {

    @Test
    fun `buildCommand contains javaw and jar path`() {
        val jar = File("C:/Users/test/QTranslate.jar")
        val command = StartupManager.buildCommand(jar)
        assertContains(command, "javaw")
        assertContains(command, "-jar")
        assertContains(command, "QTranslate.jar")
    }

    @Test
    fun `buildCommand quotes the jar path`() {
        val jar = File("C:/Program Files/QTranslate/QTranslate.jar")
        val command = StartupManager.buildCommand(jar)
        assertTrue(command.contains("\""), "Command should quote the JAR path")
        assertTrue(command.contains("Program Files"), "Path with spaces should be preserved")
    }

    @Test
    fun `isWindows returns true on Windows`() {
        // This test runs on Windows in CI, so it should return true.
        // On non-Windows it returns false — both are valid assertions.
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (os.contains("win")) {
            assertTrue(StartupManager.isWindows())
        } else {
            assertFalse(StartupManager.isWindows())
        }
    }

    @Test
    fun `isEnabled returns false on non-Windows`() {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("win")) {
            val manager = StartupManager(object : com.github.ahatem.qtranslate.api.core.Logger {
                override fun debug(message: String) {}
                override fun info(message: String) {}
                override fun warn(message: String) {}
                override fun error(message: String, throwable: Throwable?) {}
            })
            assertFalse(manager.isEnabled(File("dummy.jar")))
        }
    }
}
