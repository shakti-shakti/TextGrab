package com.arslan.textgrab

import android.graphics.RectF
import kotlin.math.max

class LineIndex(
    private val words: List<OcrEngine.Word>,
    val containers: List<OcrEngine.Container> = emptyList(),
) {

    private val lines: List<Pair<IntRange, RectF>> = buildList {
        var i = 0
        while (i < words.size) {
            val box = RectF(words[i].box)
            var j = i
            while (j + 1 < words.size && words[j + 1].lineId == words[i].lineId) {
                j++
                box.union(words[j].box)
            }
            add(i..j to box)
            i = j + 1
        }
    }

    fun wordAt(x: Float, y: Float, slop: Float): Int =
        words.indexOfFirst { w ->
            x >= w.box.left - slop && x <= w.box.right + slop &&
                y >= w.box.top - slop && y <= w.box.bottom + slop
        }

    fun containerAt(x: Float, y: Float, slop: Float = 0f): OcrEngine.Container? =
        containers.firstOrNull { c ->
            x >= c.bounds.left - slop && x <= c.bounds.right + slop &&
                y >= c.bounds.top - slop && y <= c.bounds.bottom + slop
        }

    fun nearestWord(x: Float, y: Float, containerId: Int? = null): Int {
        val candidateLines = if (containerId != null) {
            lines.filter { (range, _) -> words[range.first].containerId == containerId }
        } else lines

        val effective = if (candidateLines.isNotEmpty()) candidateLines else lines
        val range = effective.minByOrNull { (_, box) ->
            val dx = distance(x, box.left, box.right)
            val dy = distance(y, box.top, box.bottom)
            dx * dx + dy * dy * 16f
        }?.first ?: return -1

        if (x <= words[range.first].box.right) return range.first
        if (x >= words[range.last].box.left) return range.last
        return range.minBy { distance(x, words[it].box.left, words[it].box.right) }
    }

    private fun distance(v: Float, from: Float, to: Float) = max(0f, max(from - v, v - to))
}
