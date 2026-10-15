package com.toer15.queststeam.launcher

import java.io.File

/**
 * Small edits to the Wine prefix's registry files (user.reg / system.reg) while Wine isn't running,
 * like Winlator's WineRegistryEditor. Keys use single backslashes ("Software\Wine\Drivers").
 */
object WineRegistry {
    fun setString(regFile: File, key: String, name: String, value: String) {
        if (!regFile.isFile) return
        val lines = regFile.readLines().toMutableList()
        val header = "[" + key.replace("\\", "\\\\") + "]"
        val entry = "\"$name\"=\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val start = lines.indexOfFirst { it.startsWith("$header ") || it == header }
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "$header ${System.currentTimeMillis() / 1000}"
            lines += entry
        } else {
            var end = start + 1
            while (end < lines.size && !lines[end].startsWith("[")) end++
            val existing = (start + 1 until end).firstOrNull { lines[it].startsWith("\"$name\"=") }
            if (existing != null) lines[existing] = entry
            else {
                // After the #time line if there is one, before a trailing blank line.
                var at = end
                while (at > start + 1 && lines[at - 1].isBlank()) at--
                lines.add(at, entry)
            }
        }
        regFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
