package com.suiting.podcast

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Segment(
    val text: String,
    val durationMs: Long = 0,
    val audioIndex: Int? = null,
    val startMs: Long = 0,
    val endMs: Long? = null
)

data class Episode(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val voice: String,
    val segments: List<Segment>,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "draft",
    val error: String = "",
    val segmentIndex: Int = 0,
    val positionMs: Long = 0,
    val sourceText: String = "",
    val sourceUrl: String = "",
    val coverUrl: String = "",
    val coverStyle: Int = -1
)

object TextSegments {
    const val MAX_INPUT = 100_000
    enum class Mode { AUTO, KEEP_LINES }
    private val listStart = Regex("^(?:[-*+•]\\s+|(?:\\d+|[a-zA-Z]|[一二三四五六七八九十]+)[.)、．]\\s+).+")
    private val heading = Regex("^(?:#{1,6}\\s+.+|第[一二三四五六七八九十百千\\d]+[章节部篇].*)$")
    private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "etc", "fig", "eq", "inc", "al", "e.g", "i.e")
    private const val CLOSERS = "\"'”’）)]】」』"

    fun split(text: String, limit: Int = 1200, mode: Mode = Mode.AUTO): List<Segment> {
        require(limit >= 2)
        val normalized = SpeechText.clean(text).trim()
        require(normalized.isNotBlank()) { "请先添加正文" }
        require(normalized.length <= MAX_INPUT) { "每期最多支持 10 万字符，请分期添加；正文不会被自动截断" }
        val clean = ManuscriptCleaner.clean(normalized).text
        require(clean.any { it.isLetterOrDigit() }) { "清理后没有可朗读正文，请先打开链接提取文章，或重新导入 PDF" }
        val result = mutableListOf<Segment>()
        for (paragraph in paragraphs(clean, mode)) {
            var rest = paragraph
            while (rest.isNotEmpty()) {
                val end = if (rest.length <= limit) rest.length else boundary(rest, limit)
                rest.substring(0, end).trim().takeIf { s -> s.any { it.isLetterOrDigit() } }?.let { result += Segment(it) }
                rest = rest.substring(end).trimStart()
            }
        }
        require(result.isNotEmpty()) { "正文中没有可朗读的文字，请去掉分隔线或只包含符号的内容" }
        return result
    }

    private fun paragraphs(text: String, mode: Mode): List<String> {
        if (mode == Mode.KEEP_LINES) return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val output = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { output += current.toString().trim(); current.clear() } }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) { flush(); continue }
            if (heading.matches(line)) { flush(); output += line; continue }
            if (listStart.matches(line)) flush()
            if (current.isNotEmpty()) {
                // A copied visual line break inside an English sentence needs a space.
                val last = current.last()
                if (last.code < 128 && line.first().code < 128 && last != '-') current.append(' ')
            }
            current.append(line)
            val tail = line.trimEnd { it in CLOSERS }
            if ((tail.lastOrNull() ?: ' ') in "。！？!?；;：:" || (tail.endsWith('.') && sentenceEnd(tail, tail.lastIndex))) flush()
        }
        flush()
        return output
    }

    private fun sentenceEnd(text: String, i: Int): Boolean {
        val c = text[i]
        if (c in "。！？!?") return true
        if (c != '.') return false
        if (i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()) return false
        val token = text.substring(0, i).takeLastWhile { it.isLetter() || it == '.' }.lowercase(java.util.Locale.ROOT)
        if (token in abbreviations || (token.length == 1 && token[0].isLetter()) || token.matches(Regex("(?:[a-z]\\.)+[a-z]?"))) return false
        return i == text.lastIndex || text[i + 1].isWhitespace() || text[i + 1] in CLOSERS
    }

    internal fun boundary(text: String, maximum: Int): Int {
        var end = maximum.coerceAtMost(text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        var sentence = 0
        for (i in 0 until end) {
            if (!sentenceEnd(text, i)) continue
            var stop = i + 1
            while (stop < text.length && text[stop] in CLOSERS) stop++
            if (stop <= end) sentence = stop
        }
        // Prefer a whole sentence even when it leaves some unused room.
        if (sentence > 0) return sentence
        val clause = (end - 1 downTo end / 3).firstOrNull { text[it] in "；;，,:：" }
        if (clause != null) return clause + 1
        val word = (end - 1 downTo 1).firstOrNull { text[it].isWhitespace() }
        if (word != null) return word
        return end
    }
}

object EpisodeCodec {
    fun encode(items: List<Episode>): String = JSONArray().apply {
        items.forEach { e -> put(JSONObject().apply {
            put("id", e.id); put("title", e.title); put("voice", e.voice); put("createdAt", e.createdAt)
            put("status", e.status); put("error", e.error); put("segmentIndex", e.segmentIndex); put("positionMs", e.positionMs)
            put("sourceText", e.sourceText)
            put("sourceUrl", e.sourceUrl); put("coverUrl", e.coverUrl); put("coverStyle", e.coverStyle)
            put("segments", JSONArray().apply { e.segments.forEach { s -> put(JSONObject().put("text", s.text).put("durationMs", s.durationMs)
                .put("audioIndex", s.audioIndex).put("startMs", s.startMs).put("endMs", s.endMs)) } })
        }) }
    }.toString(2)

    fun decode(raw: String): List<Episode> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            val id = o.getString("id")
            require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "节目编号无效" }
            val parts = o.getJSONArray("segments")
            val segments = (0 until parts.length()).map { i -> parts.getJSONObject(i).let {
                val audioIndex = if (it.isNull("audioIndex")) null else it.getInt("audioIndex").also { n -> require(n in 0 until parts.length()) }
                val start = it.optLong("startMs").coerceAtLeast(0)
                val end = if (it.isNull("endMs")) null else it.getLong("endMs").also { n -> require(n > start) }
                Segment(it.getString("text"), it.optLong("durationMs").coerceAtLeast(0), audioIndex, start, end)
            } }
            require(segments.isNotEmpty())
            Episode(id, o.getString("title"), o.getString("voice"), segments, o.getLong("createdAt"), o.optString("status", "draft"),
                o.optString("error"), o.optInt("segmentIndex").coerceIn(0, segments.lastIndex), o.optLong("positionMs").coerceAtLeast(0), o.optString("sourceText"),
                o.optString("sourceUrl"), o.optString("coverUrl"), o.optInt("coverStyle", -1).coerceIn(-1, 7))
        }
    }
}
