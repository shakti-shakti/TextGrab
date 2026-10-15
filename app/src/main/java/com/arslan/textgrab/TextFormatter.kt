package com.arslan.textgrab

import java.util.Locale

data class TextStatistics(
    val wordCount: Int,
    val charCountWithSpaces: Int,
    val charCountWithoutSpaces: Int,
    val lineCount: Int
)

object TextFormatter {

    fun computeStats(text: String): TextStatistics {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TextStatistics(0, 0, 0, 0)

        val words = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val charsWithSpaces = trimmed.length
        val charsNoSpaces = trimmed.count { !it.isWhitespace() }
        val lines = trimmed.lines().filter { it.isNotBlank() }.size

        return TextStatistics(
            wordCount = words,
            charCountWithSpaces = charsWithSpaces,
            charCountWithoutSpaces = charsNoSpaces,
            lineCount = lines
        )
    }

    /** Unwraps single line breaks while preserving double line breaks (paragraphs). */
    fun mergeLines(text: String): String {
        val paragraphs = text.split(Regex("(?:\r?\n){2,}"))
        return paragraphs.joinToString("\n\n") { p ->
            p.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /** Removes redundant multiple spaces and tabs. */
    fun trimExtraSpaces(text: String): String {
        return text.lines().joinToString("\n") { line ->
            line.trim().replace(Regex("[ \\t]+"), " ")
        }.trim()
    }

    fun toUpperCase(text: String): String = text.uppercase(Locale.getDefault())

    fun toLowerCase(text: String): String = text.lowercase(Locale.getDefault())

    fun toTitleCase(text: String): String {
        return text.split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) {
                word.substring(0, 1).uppercase(Locale.getDefault()) + word.substring(1).lowercase(Locale.getDefault())
            } else ""
        }
    }

    fun toMarkdownBullets(text: String): String {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "• $it" }
    }
}
