package com.arslan.textgrab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartToolsTest {

    @Test
    fun testEntityExtractor() {
        val sample = "Visit https://android.com or email contact@example.com, call +1 555-123-4567. Price: $49.99"
        val entities = SmartEntityExtractor.extract(sample)

        assertTrue(entities.any { it.type == EntityType.URL && it.value == "https://android.com" })
        assertTrue(entities.any { it.type == EntityType.EMAIL && it.value == "contact@example.com" })
        assertTrue(entities.any { it.type == EntityType.PHONE })
        assertTrue(entities.any { it.type == EntityType.CURRENCY })
    }

    @Test
    fun testTextFormatterMergeLines() {
        val broken = "This is a line\nthat was broken\nby OCR.\n\nNew paragraph."
        val merged = TextFormatter.mergeLines(broken)
        assertTrue(merged.contains("This is a line that was broken by OCR."))
        assertTrue(merged.contains("New paragraph."))
    }

    @Test
    fun testTextFormatterStats() {
        val text = "Kotlin is amazing for Android"
        val stats = TextFormatter.computeStats(text)
        assertEquals(5, stats.wordCount)
        assertEquals(29, stats.charCountWithSpaces)
    }

    @Test
    fun testCaseConversions() {
        val text = "hello world"
        assertEquals("HELLO WORLD", TextFormatter.toUpperCase(text))
        assertEquals("Hello World", TextFormatter.toTitleCase(text))
    }
}
