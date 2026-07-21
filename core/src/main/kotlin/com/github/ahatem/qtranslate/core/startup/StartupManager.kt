package com.github.ahatem.qtranslate.core.startup

import com.github.ahatem.qtranslate.api.core.Logger
import java.io.File

/**
 * Manages the Windows "launch on startup" registry entry.
 *
 * Uses `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` to register
 * the application to start automatically when the user logs in.
 *
 * The registry value is a `javaw -jar "<path>"` command pointing to the
 * running QTranslate JAR. If the JAR path changes (e.g. after an update),
 * the registry entry is updated automatically.
 */
class StartupManager(private val logger: Logger) {

    companion object {
        private const val APP_NAME = "QTranslate"
        private const val REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

        /**
         * Builds the registry command string for a given JAR path.
         * Visible for testing.
         */
        fun buildCommand(jarPath: File): String {
            val javaHome = System.getProperty("java.home") ?: ""
            val javaw = File(javaHome, "bin/javaw.exe").let {
                if (it.exists()) it.absolutePath else "javaw"
            }
            return "$javaw -jar \"${jarPath.absolutePath}\""
        }

        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }

    /**
     * Enables or disables launch on system startup.
     *
     * @param enabled Whether to add or remove the registry entry.
     * @param jarPath Absolute path to the QTranslate.jar file.
     */
    fun setEnabled(enabled: Boolean, jarPath: File) {
        if (!isWindows()) {
            logger.debug("Startup registration is only supported on Windows — skipping")
            return
        }

        try {
            if (enabled) {
                val command = buildCommand(jarPath)
                runRegCommand("add", "\"$REG_KEY\"", "/v", "\"$APP_NAME\"", "/t", "REG_SZ", "/d", "\"$command\"", "/f")
                logger.info("Startup entry registered: $command")
            } else {
                runRegCommand("delete", "\"$REG_KEY\"", "/v", "\"$APP_NAME\"", "/f")
                logger.info("Startup entry removed")
            }
        } catch (e: Exception) {
            logger.error("Failed to update startup registry entry: ${e.message}")
        }
    }

    /**
     * Checks if the current startup entry matches the given JAR path.
     * Returns true if the entry exists and points to the correct JAR.
     */
    fun isEnabled(jarPath: File): Boolean {
        if (!isWindows()) return false

        return try {
            val output = runRegCommand("query", "\"$REG_KEY\"", "/v", "\"$APP_NAME\"")
            val command = buildCommand(jarPath)
            output.contains(command, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun runRegCommand(vararg args: String): String {
        val command = listOf("reg") + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0 && !output.contains("SUCCESS")) {
            throw RuntimeException("reg command failed (exit $exitCode): $output")
        }

        return output
    }
}
