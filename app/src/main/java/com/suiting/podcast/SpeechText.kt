package com.suiting.podcast

object SpeechText {
    fun clean(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0xFEFF || cp == 0x200B || cp == 0x2060 -> Unit
                cp == 0xA0 -> append(' ')
                cp == 10 || cp == 13 || cp == 9 -> appendCodePoint(cp)
                cp in 0..31 || cp in 127..159 || cp in 0xD800..0xDFFF || cp == 0xFFFE || cp == 0xFFFF -> append(' ')
                else -> appendCodePoint(cp)
            }
        }
    }.replace("\r\n", "\n").replace('\r', '\n')

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    // Transport chunks are independent of the paragraphs shown in the player.
    fun requests(text: String, maxEscapedBytes: Int = 2800): List<String> {
        require(maxEscapedBytes >= 16)
        var rest = clean(text).trim()
        require(rest.any { it.isLetterOrDigit() }) { "这一段没有可朗读的文字" }
        val parts = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            var end = 0; var size = 0
            while (end < rest.length) {
                val n = Character.charCount(rest.codePointAt(end))
                val cost = escape(rest.substring(end, end + n)).toByteArray(Charsets.UTF_8).size
                if (size + cost > maxEscapedBytes) break
                size += cost; end += n
            }
            if (end < rest.length) end = TextSegments.boundary(rest, end)
            val part = rest.substring(0, end).trim()
            if (part.isNotEmpty()) parts += part
            rest = rest.substring(end).trimStart()
        }
        return parts
    }
}
