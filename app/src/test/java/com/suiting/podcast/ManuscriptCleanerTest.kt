package com.suiting.podcast

import org.junit.Assert.*
import org.junit.Test

class ManuscriptCleanerTest {
    @Test fun urlsDisappearWithoutEatingChineseTextOrSentencePunctuation() {
        val result = ManuscriptCleaner.clean("请阅读https://example.com/paper?id=1。然后继续研究。\nMore at https://example.org/results. Next sentence.")
        assertFalse(result.text.contains("https://")); assertFalse(result.text.contains("example"))
        assertTrue(result.text.contains("然后继续研究。")); assertTrue(result.text.contains("Next sentence."))
        assertEquals(2, result.links)
    }
    @Test fun standaloneLinksAndTheirEmptyLabelsDoNotBecomeSpokenParagraphs() {
        val result = ManuscriptCleaner.clean("正文开始。\n\n论文链接：https://arxiv.org/abs/1234.5678\nhttps://github.com/org/repo\nwww.example.com\n[1]: https://example.org/ref\n\n这是结论。")
        assertEquals("正文开始。\n\n这是结论。", result.text)
        assertEquals(4, result.links)
        assertEquals(2, TextSegments.split(result.text).size)
    }
    @Test fun markdownAndHtmlLinkLabelsRemainReadable() {
        val result = ManuscriptCleaner.clean("参考 [研究方法](https://example.com/paper) 和 <a href=\"https://github.com/org/repo\">项目源码</a>，然后看 [下一篇](https://example.org/next)。")
        assertEquals("参考 研究方法 和 项目源码，然后看 下一篇。", result.text)
        assertEquals(3, result.links)
    }
    @Test fun replacementGlyphsPrivateSymbolsAndInvalidPdfGlyphCodesAreRemoved() {
        val result = ManuscriptCleaner.clean("研究\uFFFD笔记\u0000。\n\uFFFD\uFFFD\uE000\n(cid:123)继续研究。\nEnglish\uD800 text.")
        assertFalse(result.text.contains('\uFFFD')); assertFalse(result.text.contains('\uE000'))
        assertFalse(result.text.contains("cid:")); assertFalse(result.text.contains('\uD800'))
        assertTrue(result.text.contains("继续研究。")); assertTrue(result.text.contains("English text."))
        assertTrue(result.invalidCharacters > 0)
    }
    @Test fun languagesNumbersMathematicsAndEmojiArePreserved() {
        val text = "中文与 English, café, 日本語。α + β = 3.14；E = mc²；f(x) < y，调用 f()。2026-10-24，模型 30B，增长 12%。😀\n讨论乱码与“锟斤拷”这个词。"
        val result = ManuscriptCleaner.clean(text)
        assertEquals(text, result.text)
        assertEquals(0, result.invalidCharacters); assertEquals(0, result.links)
    }
    @Test fun softHyphensAndFormattingDoNotSplitNormalWords() {
        val result = ManuscriptCleaner.clean("re\u00ADsearch 与\uFEFF知识\u200B。")
        assertEquals("research 与知识。", result.text)
    }
    @Test fun embeddedImageDataIsNotReadAloud() {
        val result = ManuscriptCleaner.clean("图示 data:image/png;base64,AAAABBBBCCCC1111== 后面继续正文。")
        assertFalse(result.text.contains("base64")); assertTrue(result.text.contains("后面继续正文。"))
    }
    @Test fun cleanupCanBeRepeatedWithoutChangingTheResult() {
        val once = ManuscriptCleaner.clean("正文 [来源](https://example.com).\n原文链接：https://example.org\n研究\uFFFD继续。")
        assertEquals(once.text, ManuscriptCleaner.clean(once.text).text)
    }
    @Test fun importedDraftPreservesOriginalTextForRestorationAndRestart() {
        val raw = "研究笔记。\n链接：https://example.com/paper\n数据\uFFFD与结论。"
        val draft = ImportDraft.imported(ImportedText("笔记", raw, "PDF"))
        assertFalse(draft.text.contains("https://")); assertEquals(raw, draft.originalText)
        assertEquals(draft, ImportDraft.decode(draft.encode()))
    }
    @Test fun oldDraftsWithoutOriginalTextRemainCompatible() {
        val old = "{\"title\":\"旧草稿\",\"text\":\"正文\",\"source\":\"PDF\"}"
        assertEquals("正文", ImportDraft.decode(old).text)
        assertEquals("", ImportDraft.decode(old).originalText)
    }
    @Test fun sharedLinkIsRetainedSoThePageCanStillBeOpened() {
        val link = "https://mp.weixin.qq.com/s/zy-Q9Br15wBG0hNiUzK1Xw"
        val draft = ImportDraft.imported(ImportedText("", link, "其他应用分享"))
        assertEquals(link, draft.text); assertEquals(link, draft.originalText)
    }
    @Test fun narrationPreviewAlwaysUsesTheCleanedText() {
        val parts = TextSegments.split("研究开始。\n原文链接：https://example.com/paper\n研究\uFFFD继续。")
        assertFalse(parts.any { it.text.contains("https://") || it.text.contains('\uFFFD') })
        assertTrue(parts.joinToString("") { it.text }.contains("研究"))
    }
    @Test fun aLinkInTheSameLineDoesNotRemoveEmptyFunctionArguments() {
        val result = ManuscriptCleaner.clean("调用 f()，参考 https://example.com/docs。")
        assertTrue(result.text.contains("f()")); assertFalse(result.text.contains("https://"))
    }
}
