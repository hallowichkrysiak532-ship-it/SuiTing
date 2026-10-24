package com.suiting.podcast

data class CleanedManuscript(val text: String, val links: Int, val invalidCharacters: Int) {
    val summary: String get() = if (links == 0 && invalidCharacters == 0) "未发现需要清理的网址或无效字符。"
        else "已清理 " + links + " 处网址、" + invalidCharacters + " 个无效字符；可恢复原稿后检查。"
}

object ManuscriptCleaner {
    private val url = Regex("(?:https?://|ftp://|www\\.)[A-Za-z0-9][A-Za-z0-9._~:/?#@!$&'()*+,;=%\\[\\]-]*", RegexOption.IGNORE_CASE)
    private val markdown = Regex("!?\\[([^\\]\\n]*)]\\((?:https?://|ftp://|www\\.)[^\\s\\n]*?\\)", RegexOption.IGNORE_CASE)
    private val htmlLink = Regex("<a\\b[^>]*>(.*?)</a\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val dataUri = Regex("data:(?:image|audio|video|application)/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+", RegexOption.IGNORE_CASE)
    private val label = Regex("(?:[-*•]\\s*)?(?:论文|原文|项目|源码|源代码|代码|资料|文章|参考|相关|下载|详情)?(?:链接|地址|网址)|(?:url|link|source|references?|resources?|paper|code|github)", RegexOption.IGNORE_CASE)
    fun clean(original: String): CleanedManuscript {
        var links = 0
        var invalid = 0
        var text = buildString {
            var i = 0
            while (i < original.length) {
                val cp = original.codePointAt(i)
                i += Character.charCount(cp)
                val low = cp and 0xFFFF
                val unsafe = (cp in 0..31 && cp != 9 && cp != 10 && cp != 13) || cp in 127..159 ||
                    cp in 0xD800..0xDFFF || cp == 0xFFFD || low == 0xFFFE || low == 0xFFFF || cp in 0xFDD0..0xFDEF ||
                    cp == 0xFEFF || cp == 0x200B || cp == 0x2060 || cp == 0xAD || cp in 0x202A..0x202E || cp in 0x2066..0x2069 ||
                    Character.getType(cp) == Character.PRIVATE_USE.toInt()
                if (unsafe) { invalid++; if (cp !in setOf(0xAD, 0xFEFF, 0x200B, 0x2060)) append(' ') }
                else appendCodePoint(cp)
            }
        }.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
        text = htmlLink.replace(text) { match -> links++; match.groupValues[1] }
        text = markdown.replace(text) { match -> links++; match.groupValues[1] }
        text = dataUri.replace(text) { links++; " " }
        text = Regex("\\((?:cid|gid):\\s*\\d+\\)", RegexOption.IGNORE_CASE).replace(text) { match -> invalid += match.value.length; " " }
        text = text.lines().map { line ->
            val before = links
            var cleaned = url.replace(line) { match ->
                links++
                var address = match.value
                var suffix = ""
                while (address.isNotEmpty()) {
                    val c = address.last()
                    val trailing = c in ".,;:!?" || (c == ')' && address.count { it == ')' } > address.count { it == '(' })
                    if (!trailing) break
                    suffix = c + suffix; address = address.dropLast(1)
                }
                " " + suffix
            }
            cleaned = cleaned.replace(Regex("[ \\t]+"), " ").replace(Regex("[ \\t]+([，。；：！？、,.!?;:])"), "$1").trim()
            val bare = cleaned.trim(' ', ':', '：', '.', '。', '-', '—', '(', ')', '（', '）')
            if (links > before && (label.matches(bare) || cleaned.matches(Regex("\\[[^]]*]\\s*:\\s*")) || cleaned.none { it.isLetterOrDigit() })) "" else cleaned
        }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return CleanedManuscript(text, links, invalid)
    }
}
