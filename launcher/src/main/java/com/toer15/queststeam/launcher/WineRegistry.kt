package com.toer15.queststeam.launcher

import java.io.File

/**
 * Small edits to the Wine prefix's registry files (user.reg / system.reg) while Wine isn't running,
 * like Winlator's WineRegistryEditor. Keys use single backslashes ("Software\Wine\Drivers").
 */
object WineRegistry {
    fun setString(regFile: File, key: String, name: String, value: String) =
        setValue(regFile, key, name, "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")

    fun setDword(regFile: File, key: String, name: String, value: Int) =
        setValue(regFile, key, name, "dword:" + "%08x".format(value))

    /** Sets "name"=[data] ([data] already in .reg syntax) in [key]; key names match ignoring case, like Windows. */
    private fun setValue(regFile: File, key: String, name: String, data: String) {
        if (!regFile.isFile) return
        val lines = regFile.readLines().toMutableList()
        val header = "[" + key.replace("\\", "\\\\") + "]"
        val entry = "\"$name\"=$data"
        val start = lines.indexOfFirst { it.startsWith("$header ", ignoreCase = true) || it.equals(header, ignoreCase = true) }
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "$header ${System.currentTimeMillis() / 1000}"
            lines += entry
        } else {
            var end = start + 1
            while (end < lines.size && !lines[end].startsWith("[")) end++
            val existing = (start + 1 until end).firstOrNull { lines[it].startsWith("\"$name\"=", ignoreCase = true) }
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
