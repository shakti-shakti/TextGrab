package com.arslan.textgrab

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine

object OcrEngine {

    data class Word(
        val text: String,
        val box: RectF,
        val lineId: Int,
        val rowId: Int,
        val containerId: Int = 0,
    )

    data class Container(
        val id: Int,
        val bounds: RectF,
        val wordRange: IntRange,
        val lineRange: IntRange,
        val cornerRadius: Float = 12f,
    )

    data class Result(
        val words: List<Word>,
        val lineBoxes: List<RectF>,
        val lineBlocks: List<Int>,
        val containers: List<Container> = emptyList(),
    ) {
        val isEmpty get() = words.isEmpty()

        fun textOf(start: Int, end: Int): String {
            val sb = StringBuilder()
            for (i in start..end) {
                if (i > start) {

                    sb.append(if (words[i - 1].rowId != words[i].rowId) "\n" else " ")
                }
                sb.append(words[i].text)
            }
            return sb.toString()
        }

        val fullText: String get() = if (isEmpty) "" else textOf(0, words.size - 1)

        val lineTexts: List<String>
            get() {
                val byLine = words.groupBy { it.lineId }
                return lineBoxes.indices.map { id ->
                    byLine[id]?.joinToString(" ") { it.text }.orEmpty()
                }
            }
    }

    private class Line(val box: RectF, val words: List<Pair<String, RectF>>, val blockId: Int)

    private class Scored(val lines: List<Line>, val score: Float, val meanConfidence: Float)

    private const val MIN_CONFIDENCE = 0.45f

    private const val LATIN_TRUSTED_CONFIDENCE = 0.8f

    private val cjkOptions = listOf(
        ChineseTextRecognizerOptions.Builder().build(),
        JapaneseTextRecognizerOptions.Builder().build(),
        KoreanTextRecognizerOptions.Builder().build(),
    )

    private var latin: TextRecognizer? = null

    private fun latin(): TextRecognizer =
        latin ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { latin = it }

    fun warmUp() {
        if (latin != null) return
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        latin().process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { bmp.recycle() }
    }

    fun release() {
        latin?.close()
        latin = null
    }

    /** Lines centred inside [exclude] are dropped: the modules of a QR code read as junk text. */
    suspend fun recognize(bitmap: Bitmap, exclude: List<RectF> = emptyList()): Result {
        val image = InputImage.fromBitmap(bitmap, 0)
        var best = score(latin().run(image))

        if (best.lines.isEmpty() || best.meanConfidence < LATIN_TRUSTED_CONFIDENCE) {
            for (options in cjkOptions) {
                val candidate = TextRecognition.getClient(options).use { score(it.run(image)) }
                if (candidate.score > best.score) best = candidate
            }
        }
        return buildResult(best.lines.filterNot { line ->
            exclude.any { it.contains(line.box.centerX(), line.box.centerY()) }
        })
    }

    private suspend fun TextRecognizer.run(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun score(text: Text): Scored {
        val lines = ArrayList<Line>()
        var score = 0f
        var confSum = 0f
        var confCount = 0
        for ((blockId, block) in text.textBlocks.withIndex()) {
            for (line in block.lines) {
                val words = ArrayList<Pair<String, RectF>>()
                for (e in line.elements) {
                    val box = e.boundingBox ?: continue
                    val t = e.text.trim()

                    val conf = e.confidence.takeIf { it > 0f }
                    if (conf != null) {
                        confSum += conf
                        confCount++
                    }
                    if (isNoise(t, conf)) continue
                    words.add(t to RectF(box))
                    score += t.count { it.isLetterOrDigit() } * (conf ?: 0.7f)
                }
                if (words.isEmpty()) continue

                val lineBox = RectF(words[0].second).apply { words.forEach { union(it.second) } }
                lines.add(Line(lineBox, words, blockId))
            }
        }
        val mean = if (confCount > 0) confSum / confCount else 1f
        return Scored(lines, score, mean)
    }

    private fun isNoise(t: String, conf: Float?): Boolean {
        if (t.isEmpty()) return true
        if (conf != null && conf < MIN_CONFIDENCE) return true
        val letters = t.count { it.isLetterOrDigit() }

        if (letters == 0 && t.length <= 2) return true

        if (t.length == 1 && conf != null && conf < 0.7f) return true
        return false
    }

    private fun buildResult(lines: List<Line>): Result {
        if (lines.isEmpty()) {
            return Result(emptyList(), emptyList(), emptyList(), emptyList())
        }

        // Dynamic Document Layout & Container Analysis:
        // Segments screen into logical visual UI containers (panes, columns, cards, terminal windows)
        val containerLineGroups = clusterIntoContainers(lines)

        val words = ArrayList<Word>()
        val lineBoxes = ArrayList<RectF>()
        val lineBlocks = ArrayList<Int>()
        val containers = ArrayList<Container>()

        var lineId = 0
        var globalRowId = 0

        containerLineGroups.forEachIndexed { containerId, cLines ->
            val wordStart = words.size
            val lineStart = lineId

            // Sort lines in reading order strictly within this specific container
            val byTop = cLines.sortedBy { it.box.centerY() }
            val rows = ArrayList<MutableList<Line>>()
            for (line in byTop) {
                val row = rows.lastOrNull()
                if (row != null && sameRow(row[0].box, line.box)) {
                    row.add(line)
                } else {
                    rows.add(mutableListOf(line))
                }
            }

            var minL = Float.MAX_VALUE
            var minT = Float.MAX_VALUE
            var maxR = Float.MIN_VALUE
            var maxB = Float.MIN_VALUE

            for (row in rows) {
                for (line in row.sortedBy { it.box.left }) {
                    lineBoxes.add(line.box)
                    lineBlocks.add(line.blockId)

                    minL = min(minL, line.box.left)
                    minT = min(minT, line.box.top)
                    maxR = max(maxR, line.box.right)
                    maxB = max(maxB, line.box.bottom)

                    for ((t, box) in line.words.sortedBy { it.second.left }) {
                        words.add(Word(t, box, lineId, globalRowId, containerId))
                    }
                    lineId++
                }
                globalRowId++
            }

            val wordEnd = words.size - 1
            val lineEnd = lineId - 1

            if (wordEnd >= wordStart) {
                // Generate tight bounding box around the container with aesthetic padding
                val padH = 10f
                val padV = 8f
                val containerBounds = RectF(minL - padH, minT - padV, maxR + padH, maxB + padV)
                containers.add(
                    Container(
                        id = containerId,
                        bounds = containerBounds,
                        wordRange = wordStart..wordEnd,
                        lineRange = lineStart..lineEnd,
                    )
                )
            }
        }

        return Result(words, lineBoxes, lineBlocks, containers)
    }

    /**
     * Generalized layout analysis graph clustering:
     * Disjoint-set clustering dynamically groups lines into distinct visual containers/columns
     * based on spatial proximity, horizontal overlap, and vertical flow without any hardcoding.
     */
    private fun clusterIntoContainers(lines: List<Line>): List<List<Line>> {
        val n = lines.size
        if (n <= 1) return listOf(lines)

        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) root = parent[root]
            var curr = i
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }
        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) parent[rootI] = rootJ
        }

        for (i in 0 until n) {
            val a = lines[i]
            val aBox = a.box
            val aH = max(1f, aBox.height())

            for (j in i + 1 until n) {
                val b = lines[j]
                val bBox = b.box
                val bH = max(1f, bBox.height())
                val avgH = (aH + bH) / 2f

                // Vertical distance between lines
                val vDist = when {
                    aBox.bottom < bBox.top -> bBox.top - aBox.bottom
                    bBox.bottom < aBox.top -> aBox.top - bBox.bottom
                    else -> 0f
                }

                // If vertically too far apart, not part of same local text block/container
                if (vDist > avgH * 4.5f) continue

                // Horizontal overlap
                val hOverlap = min(aBox.right, bBox.right) - max(aBox.left, bBox.left)
                val minW = min(aBox.width(), bBox.width())
                val hOverlapRatio = if (minW > 0f) hOverlap / minW else 0f

                // Alignment distances
                val leftAlignDist = kotlin.math.abs(aBox.left - bBox.left)
                val rightAlignDist = kotlin.math.abs(aBox.right - bBox.right)

                // Connected if overlapping column or aligned edges within same vertical span
                val sameColumn = (hOverlapRatio > 0.15f) ||
                    (leftAlignDist < avgH * 2.5f && vDist < avgH * 3.0f) ||
                    (rightAlignDist < avgH * 2.5f && vDist < avgH * 3.0f)

                // MLKit native block grouping
                val sameBlock = (a.blockId == b.blockId) && (vDist < avgH * 3.0f)

                if (sameColumn || sameBlock) {
                    union(i, j)
                }
            }
        }

        val groups = HashMap<Int, MutableList<Line>>()
        for (i in 0 until n) {
            groups.getOrPut(find(i)) { ArrayList() }.add(lines[i])
        }

        // Sort containers in natural visual layout reading order:
        // Left-to-right columns, and top-to-bottom
        return groups.values.toList().sortedWith { c1, c2 ->
            val left1 = c1.minOf { it.box.left }
            val left2 = c2.minOf { it.box.left }
            val top1 = c1.minOf { it.box.top }
            val top2 = c2.minOf { it.box.top }

            if (kotlin.math.abs(left1 - left2) > 60f) {
                left1.compareTo(left2)
            } else {
                top1.compareTo(top2)
            }
        }
    }

    private fun sameRow(a: RectF, b: RectF): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * min(a.height(), b.height())
    }
}
