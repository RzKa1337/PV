package com.solartracker.pro.core.update

import java.time.Instant

enum class LogLevel { INFO, WARN, ERROR }

data class UpdateLogEntry(val time: Instant, val level: LogLevel, val message: String) {
    /** One line: "2026-10-05T10:00:00Z|INFO|message" (message without line breaks). */
    fun serialize(): String = "$time|$level|$message"

    companion object {
        fun parse(line: String): UpdateLogEntry? {
            val parts = line.split('|', limit = 3)
            if (parts.size != 3) return null
            val time = runCatching { Instant.parse(parts[0]) }.getOrNull() ?: return null
            val level = LogLevel.entries.firstOrNull { it.name == parts[1] } ?: return null
            return UpdateLogEntry(time, level, parts[2])
        }
    }
}

/** Bounded, thread-safe in-memory update log with text (de)serialization for persistence. */
class UpdateLog(private val capacity: Int = 200, private val clock: () -> Instant = { Instant.now() }) {
    private val entries = ArrayDeque<UpdateLogEntry>()

    @Synchronized
    fun log(level: LogLevel, message: String) {
        entries.addLast(UpdateLogEntry(clock(), level, message.replace('\n', ' ').replace('|', '/')))
        while (entries.size > capacity) entries.removeFirst()
    }

    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun error(message: String) = log(LogLevel.ERROR, message)

    @Synchronized
    fun entries(): List<UpdateLogEntry> = entries.toList()

    @Synchronized
    fun serialize(): String = entries.joinToString("\n") { it.serialize() }

    @Synchronized
    fun restore(text: String) {
        entries.clear()
        text.lineSequence().mapNotNull(UpdateLogEntry::parse).toList().takeLast(capacity).forEach(entries::addLast)
    }
}
