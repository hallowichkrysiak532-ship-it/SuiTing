package com.suiting.podcast

import java.util.Locale
import org.json.JSONObject
import org.jsoup.parser.Parser

internal data class EdgeWord(val text: String, val startMs: Long, val durationMs: Long)
internal data class AudioSlice(val startMs: Long, val endMs: Long)
internal data class GenerationBatch(val indices: List<Int>)

internal object EdgeBatches {
    const val WORKERS = 4
    private const val SHORT_LENGTH = 180
    private const val BATCH_LENGTH = 600
    private const val BATCH_BYTES = 2400
    private const val MAX_PARAGRAPHS = 8

    fun plan(segments: List<Segment>, ready: (Int) -> Boolean, merge: Boolean): List<GenerationBatch> {
        val batches = mutableListOf<GenerationBatch>()
        var group = mutableListOf<Int>()
        fun flush() { if (group.isNotEmpty()) { batches += GenerationBatch(group.toList()); group = mutableListOf() } }
        for (index in segments.indices) {
            if (ready(index)) { flush(); continue }
            val text = segments[index].text
            if (!merge || text.length > SHORT_LENGTH) { flush(); batches += GenerationBatch(listOf(index)); continue }
            val combined = (group + index).joinToString("\n") { segments[it].text }
            if (group.size >= MAX_PARAGRAPHS || combined.length > BATCH_LENGTH || SpeechText.escape(combined).toByteArray(Charsets.UTF_8).size > BATCH_BYTES) flush()
            group += index
        }
        flush()
        return batches
    }

    fun parseMetadata(body: String): List<EdgeWord> {
        val array = JSONObject(body).optJSONArray("Metadata") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val value = array.getJSONObject(index)
            if (value.optString("Type") != "WordBoundary") return@mapNotNull null
            val data = value.getJSONObject("Data")
            EdgeWord(Parser.unescapeEntities(data.getJSONObject("text").getString("Text"), false),
                data.getLong("Offset") / 10_000, data.getLong("Duration") / 10_000)
        }
    }

    private fun key(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

    /** Only merge when the service's words align exactly. Numbers/abbreviations with changed spelling fall back to individual requests. */
    fun slices(texts: List<String>, words: List<EdgeWord>, durationMs: Long): List<AudioSlice>? {
        if (texts.size < 2 || words.isEmpty() || durationMs <= 0) return null
        val keys = texts.map(::key)
        if (keys.any { it.isEmpty() }) return null
        val expected = keys.joinToString("")
        val offsets = mutableListOf<Pair<Int, Long>>()
        var cursor = 0
        var previousTime = -1L
        for (word in words) {
            val token = key(word.text)
            if (token.isEmpty()) continue
            if (!expected.startsWith(token, cursor) || word.startMs < previousTime || word.startMs >= durationMs) return null
            offsets += cursor to word.startMs
            cursor += token.length
            previousTime = word.startMs
        }
        if (cursor != expected.length) return null
        val starts = mutableListOf(0L)
        var boundary = 0
        for (key in keys.dropLast(1)) {
            boundary += key.length
            val start = offsets.firstOrNull { it.first == boundary }?.second ?: return null
            if (start <= starts.last()) return null
            starts += start
        }
        return starts.mapIndexed { index, start -> AudioSlice(start, starts.getOrNull(index + 1) ?: durationMs) }
    }
}
