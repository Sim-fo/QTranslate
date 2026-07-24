package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A [LoggerFactory] that writes to both stdout (coloured) and a rotating log file.
 *
 * The file lives at `[logDir]/qtranslate-app.log` and rotates daily.
 * Old files are kept for 14 days.
 *
 * ### Why writing is asynchronous
 * Callers only format the line and hand it to a queue; a dedicated daemon thread
 * does the actual I/O. This matters because the global key hook logs from
 * JNativeHook's dispatch thread: Windows silently unregisters a low-level
 * keyboard hook whose handler exceeds `LowLevelHooksTimeout` (~300 ms), so a
 * blocking `flush()` per keystroke could disable the app's own hotkeys.
 *
 * If the queue fills up (disk stalled) lines are dropped rather than blocking
 * the caller; the number of dropped lines is reported once the writer catches up.
 */
class FileLoggerFactory(
    private val logDir: File,
    private val minLogLevel: LogLevel = LogLevel.DEBUG
) : LoggerFactory {

    enum class LogLevel(val priority: Int, private val colour: Int) {
        DEBUG(0, 34),
        INFO(1, 32),
        WARN(2, 33),
        ERROR(3, 31);

        /** SGR sequence tinting the level tag on a colour-capable console. */
        val ansiCode: String get() = "$ESC[${colour}m"
    }

    /** One formatted record, ready to be written verbatim. */
    private class Record(val console: String, val file: String)

    private val queue = ArrayBlockingQueue<Record>(QUEUE_CAPACITY)
    private val droppedRecords = AtomicLong()
    private val running = AtomicBoolean(true)

    @Volatile private var currentWriter: BufferedWriter? = null
    private var currentFileDate: String = ""

    private val fileTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val consoleTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val writerThread: Thread

    init {
        logDir.mkdirs()
        writerThread = Thread(::drainLoop, "FileLogger").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    override fun getLogger(name: String): Logger =
        CompositeLogger(name, minLogLevel)

    /**
     * Hands a record to the writer thread. Never blocks: if the queue is full the
     * record is dropped and counted.
     */
    private fun enqueue(record: Record) {
        if (!queue.offer(record)) droppedRecords.incrementAndGet()
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        // Do not interrupt: let the loop notice `running` and drain what is queued.
        runCatching { writerThread.join(CLOSE_TIMEOUT_MS) }
        runCatching { currentWriter?.close() }
        currentWriter = null
    }

    // ---------------------------------------------------------------- writer

    private fun drainLoop() {
        while (running.get() || queue.isNotEmpty()) {
            val first = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
            write(first)
            // Batch whatever else has piled up, then flush once for the whole batch.
            while (true) write(queue.poll() ?: break)
            reportDrops()
            runCatching { currentWriter?.flush() }
        }
        runCatching { currentWriter?.flush() }
    }

    private fun write(record: Record) {
        runCatching { println(record.console) }
        val writer = ensureWriter() ?: return   // could not open; skip, retry next record
        try {
            writer.write(record.file)
            writer.newLine()
        } catch (_: Exception) {
            // The stream broke. Drop it so [ensureWriter] reopens a fresh one next
            // time instead of silently no-op'ing forever.
            runCatching { writer.close() }
            currentWriter = null
        }
    }

    private fun reportDrops() {
        val dropped = droppedRecords.getAndSet(0)
        if (dropped == 0L) return
        val ts = LocalDateTime.now()
        write(
            Record(
                console = "[${ts.format(consoleTimeFmt)}] [FileLogger] ${LogLevel.WARN.ansiCode}[WARN]$RESET " +
                    "[FileLogger] dropped $dropped log records — writer could not keep up",
                file = "${ts.format(fileTimeFmt)} [FileLogger] WARN FileLogger - " +
                    "dropped $dropped log records — writer could not keep up"
            )
        )
    }

    private fun currentDate(): String = LocalDateTime.now().format(fileDateFmt)

    /**
     * Returns an open writer for today's file, rotating at a date change and
     * reopening if a previous write broke the stream. Returns null if the file
     * cannot be opened right now — the caller skips that record and retries later.
     *
     * Crucially, [currentFileDate] is only advanced **after** a writer is
     * successfully open. An earlier version set it first, so a single failed
     * reopen (e.g. a transient lock at the midnight rotation) wedged logging
     * permanently: the date already matched, so every later call early-returned
     * onto a dead writer.
     */
    private fun ensureWriter(): BufferedWriter? {
        val today = currentDate()
        if (currentWriter != null && today == currentFileDate) return currentWriter

        // No writer yet, the date rolled over, or a prior write broke the stream.
        runCatching { currentWriter?.close() }
        currentWriter = null

        val targetFile = File(logDir, "qtranslate-app.log")

        // On a genuine date rollover (not first open, not a mid-day reopen),
        // archive the finished day's file and prune old archives.
        if (currentFileDate.isNotEmpty() && today != currentFileDate) {
            if (targetFile.exists()) {
                runCatching {
                    targetFile.renameTo(File(logDir, "qtranslate-app-${currentFileDate}.log"))
                }
            }
            pruneOldLogs()
        }

        val opened = runCatching { BufferedWriter(FileWriter(targetFile, true), 8192) }.getOrNull()
        if (opened != null) currentFileDate = today
        currentWriter = opened
        return opened
    }

    /** Deletes archived logs older than 14 days. */
    private fun pruneOldLogs() {
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
    }

    // ---------------------------------------------------------------- logger

    private inner class CompositeLogger(
        private val name: String,
        private val minLevel: LogLevel
    ) : Logger {

        private fun emit(level: LogLevel, message: String, error: Throwable? = null) {
            if (level.priority < minLevel.priority) return

            val ts     = LocalDateTime.now()
            val thread = Thread.currentThread().name
            val stack  = error?.let { t ->
                java.io.StringWriter().also { sw -> t.printStackTrace(java.io.PrintWriter(sw)) }.toString()
            }

            val body = if (stack == null) message else "$message\n$stack"
            enqueue(
                Record(
                    console = "[${ts.format(consoleTimeFmt)}] [$thread] " +
                        "${level.ansiCode}[${level.name}]001B[0m [$name] $body",
                    file = "${ts.format(fileTimeFmt)} [$thread] ${level.name} $name - $body"
                )
            )
        }

        override fun debug(message: String)                    = emit(LogLevel.DEBUG, message)
        override fun info(message: String)                     = emit(LogLevel.INFO, message)
        override fun warn(message: String)                     = emit(LogLevel.WARN, message)
        override fun error(message: String, error: Throwable?) = emit(LogLevel.ERROR, message, error)
    }

    private companion object {
        /** ASCII escape, written as a code point so no control byte lives in the source. */
        val ESC: Char = Char(27)

        /** SGR reset sequence. */
        val RESET: String = "$ESC[0m"

        /** Records buffered before the writer starts dropping them. */
        const val QUEUE_CAPACITY = 8192

        /** How long the writer waits for a record before re-checking [running]. */
        const val POLL_TIMEOUT_MS = 200L

        /** How long [close] waits for the writer to drain. */
        const val CLOSE_TIMEOUT_MS = 2000L
    }
}
