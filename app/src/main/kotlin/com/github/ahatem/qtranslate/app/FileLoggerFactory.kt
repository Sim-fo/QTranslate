package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [LoggerFactory] that writes to both stdout (coloured) and a rotating log file.
 *
 * The file lives at `[logDir]/qtranslate-app.log` and rotates daily.
 * Old files are kept for 14 days.
 */
class FileLoggerFactory(
    private val logDir: File,
    private val minLogLevel: LogLevel = LogLevel.DEBUG
) : LoggerFactory {

    enum class LogLevel(val priority: Int, val ansiCode: String) {
        DEBUG(0, "\u001B[34m"),
        INFO(1, "\u001B[32m"),
        WARN(2, "\u001B[33m"),
        ERROR(3, "\u001B[31m")
    }

    private val lock = ReentrantLock()
    private var currentWriter: BufferedWriter? = null
    private var currentFileDate: String = ""

    private val fileTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val consoleTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    init {
        logDir.mkdirs()
    }

    override fun getLogger(name: String): Logger =
        CompositeLogger(name, minLogLevel)

    private fun currentDate(): String = LocalDateTime.now().format(fileDateFmt)

    private fun rotateIfNeeded() {
        val today = currentDate()
        if (today == currentFileDate && currentWriter != null) return

        runCatching { currentWriter?.close() }

        // Clean up old log files
        runCatching {
            logDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("qtranslate-app") && f.name.endsWith(".log") && f.name != "qtranslate-app.log") {
                    val ageDays = try {
                        val fileDate = f.name.removePrefix("qtranslate-app-").removeSuffix(".log")
                        val fileDateObj = java.time.LocalDate.parse(fileDate)
                        java.time.temporal.ChronoUnit.DAYS.between(fileDateObj, java.time.LocalDate.now())
                    } catch (_: Exception) { Long.MAX_VALUE }
                    if (ageDays > 14) f.delete()
                }
            }
        }

        val targetFile = File(logDir, "qtranslate-app.log")
        if (today != currentFileDate && targetFile.exists()) {
            val archiveFile = File(logDir, "qtranslate-app-${currentFileDate}.log")
            runCatching { targetFile.renameTo(archiveFile) }
        }

        currentFileDate = today
        currentWriter = BufferedWriter(FileWriter(targetFile, true), 8192)
    }

    private fun writeToFile(line: String) {
        lock.withLock {
            runCatching {
                rotateIfNeeded()
                currentWriter?.apply {
                    write(line)
                    newLine()
                    flush()
                }
            }
        }
    }

    fun close() {
        lock.withLock {
            runCatching { currentWriter?.close() }
            currentWriter = null
        }
    }

    private inner class CompositeLogger(
        private val name: String,
        private val minLevel: LogLevel
    ) : Logger {

        private fun emit(level: LogLevel, message: String, error: Throwable? = null) {
            if (level.priority < minLevel.priority) return

            val ts = LocalDateTime.now()

            // Console (coloured)
            synchronized(System.out) {
                val consoleTs = ts.format(consoleTimeFmt)
                val thread = Thread.currentThread().name
                val tag = "${level.ansiCode}[${level.name}]\u001B[0m"
                println("[$consoleTs] [$thread] $tag [$name] $message")
                error?.printStackTrace(System.out)
            }

            // File (plain text)
            val fileTs = ts.format(fileTimeFmt)
            val thread = Thread.currentThread().name
            val fileLine = "$fileTs [$thread] ${level.name} $name - $message"
            writeToFile(fileLine)
            if (error != null) {
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                writeToFile("$fileTs [$thread] ${level.name} $name - $sw")
            }
        }

        override fun debug(message: String)                    = emit(LogLevel.DEBUG, message)
        override fun info(message: String)                     = emit(LogLevel.INFO, message)
        override fun warn(message: String)                     = emit(LogLevel.WARN, message)
        override fun error(message: String, error: Throwable?) = emit(LogLevel.ERROR, message, error)
    }
}
