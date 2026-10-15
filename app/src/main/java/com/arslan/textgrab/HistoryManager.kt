package com.arslan.textgrab

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val previewTitle: String,
    val timestamp: Long = System.currentTimeMillis(),
    val wordCount: Int,
    val charCount: Int,
    var isFavorite: Boolean = false,
    val source: String = "Scan"
)

object HistoryManager {
    private const val PREFS_NAME = "textgrab_history_prefs"
    private const val KEY_ITEMS = "history_items"
    private const val MAX_ITEMS = 150

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAll(context: Context): List<HistoryItem> {
        val jsonStr = getPrefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val list = mutableListOf<HistoryItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        text = obj.getString("text"),
                        previewTitle = obj.optString("previewTitle", "Captured Text"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        wordCount = obj.optInt("wordCount", 0),
                        charCount = obj.optInt("charCount", 0),
                        isFavorite = obj.optBoolean("isFavorite", false),
                        source = obj.optString("source", "Scan")
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("HistoryManager", "Failed to parse history", e)
        }
        return list
    }

    @Synchronized
    fun add(context: Context, text: String, source: String = "Scan"): HistoryItem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val items = getAll(context).toMutableList()
        // Deduplicate recent identical text
        if (items.isNotEmpty() && items.first().text == trimmed) {
            return items.first()
        }

        val firstLine = trimmed.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Captured Text"
        val title = if (firstLine.length > 50) firstLine.take(47) + "…" else firstLine
        val words = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }.size

        val newItem = HistoryItem(
            text = trimmed,
            previewTitle = title,
            wordCount = words,
            charCount = trimmed.length,
            source = source
        )

        items.add(0, newItem)
        if (items.size > MAX_ITEMS) {
            // Keep favorites, drop oldest non-favorites
            val pruned = mutableListOf<HistoryItem>()
            var count = 0
            for (item in items) {
                if (item.isFavorite || count < MAX_ITEMS) {
                    pruned.add(item)
                    count++
                }
            }
            saveList(context, pruned)
        } else {
            saveList(context, items)
        }
        return newItem
    }

    @Synchronized
    fun toggleFavorite(context: Context, id: String): Boolean {
        val items = getAll(context).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index != -1) {
            val updated = items[index].copy(isFavorite = !items[index].isFavorite)
            items[index] = updated
            saveList(context, items)
            return updated.isFavorite
        }
        return false
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val items = getAll(context).toMutableList()
        items.removeAll { it.id == id }
        saveList(context, items)
    }

    @Synchronized
    fun clearAll(context: Context) {
        getPrefs(context).edit().remove(KEY_ITEMS).apply()
    }

    private fun saveList(context: Context, items: List<HistoryItem>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("previewTitle", item.previewTitle)
                put("timestamp", item.timestamp)
                put("wordCount", item.wordCount)
                put("charCount", item.charCount)
                put("isFavorite", item.isFavorite)
                put("source", item.source)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
