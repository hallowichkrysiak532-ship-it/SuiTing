package com.suiting.podcast

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream

class ImportTest {
    @Test fun fullTextPipelinePreservesMoreThanTwentyThousandCharacters() {
        val text = "研究笔记。".repeat(18_000)
        assertEquals(90_000, text.length)
        val draft = ImportDraft("长文章", text, "TXT")
        val restored = ImportDraft.decode(draft.encode())
        assertEquals(text, restored.text)
        val parts = TextSegments.split(restored.text)
        assertEquals(text, parts.joinToString("") { it.text })
        val e = Episode(title = draft.title, voice = "en-US-AriaNeural", segments = parts, sourceText = restored.text)
        val saved = EpisodeCodec.decode(EpisodeCodec.encode(listOf(e))).single()
        assertEquals(90_000, saved.sourceText.length)
        assertEquals(text, saved.segments.joinToString("") { it.text })
    }
    @Test fun exactlyOneHundredThousandCharactersCanBeImportedWithoutClipping() {
        val text = "字".repeat(100_000)
        assertEquals(text, PlainTextImport.decode(text.toByteArray()))
        assertEquals(text, TextSegments.split(text).joinToString("") { it.text })
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedImportIsRejectedRatherThanTruncated() { PlainTextImport.decode("字".repeat(100_001).toByteArray()) }
    @Test fun utf16TextFilesAndUtf8BomAreUnderstood() {
        val original = "研究笔记\n\nResearch notes."
        assertEquals(original, PlainTextImport.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + original.toByteArray(Charsets.UTF_16LE)))
        assertEquals(original, PlainTextImport.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + original.toByteArray()))
    }
    @Test fun wechatArticleExtractsTitleAndParagraphsWithoutMenusScriptsOrFooter() {
        val html = """<html><head><title>分享页</title></head><body>
            <nav>返回首页 登录菜单</nav><h1 id="activity-name">研究方法</h1><div class="rich_media_meta_list">作者信息</div>
            <div id="js_content"><p>第一段：研究需要耐心。</p><p>Second paragraph with <strong>machine learning</strong> &amp; models.</p>
            <section>第三段。<br>这是下一行。</section><script>tracking_and_secrets()</script></div><footer>广告和推荐</footer></body></html>"""
        val result = ArticleHtml.extract(html.toByteArray(), "https://mp.weixin.qq.com/s/example")
        assertEquals("研究方法", result.title)
        assertTrue(result.text.contains("第一段：研究需要耐心。\n\nSecond paragraph"))
        assertTrue(result.text.contains("machine learning & models."))
        assertFalse(result.text.contains("广告")); assertFalse(result.text.contains("tracking")); assertFalse(result.text.contains("返回首页"))
    }
    @Test fun normalBlogExtractsTheArticleRatherThanSiteNavigation() {
        val result = ArticleHtml.extract("<html><title>ML Research</title><nav>About Contact</nav><article><h1>Choosing Problems</h1><p>Choose the right research problems.</p></article><footer>Related links</footer></html>".toByteArray(), "https://example.com/article")
        assertTrue(result.text.contains("Choosing Problems")); assertFalse(result.text.contains("Contact")); assertFalse(result.text.contains("Related links"))
    }
    @Test(expected = IllegalArgumentException::class) fun wechatVerificationPageIsNotImportedAsArticleText() {
        ArticleHtml.extract("<html><body>当前环境异常，请先完成验证，再访问文章正文。<form>验证</form></body></html>".toByteArray(), "https://mp.weixin.qq.com/s/example")
    }
    @Test fun pastedWechatShareCanContainATitleAndTrackingParameters() {
        val url = ArticleHtml.url("研究文章 https://mp.weixin.qq.com/s?__biz=abc&mid=123&idx=1。")
        assertEquals("mp.weixin.qq.com", url.host); assertEquals("123", url.queryParameter("mid"))
    }
    @Test(expected = IllegalArgumentException::class) fun localFileUrlsAreNotFetchedAsArticles() { ArticleHtml.url("file:///sdcard/private.txt") }
    @Test fun markdownWechatLinksCanBePastedFromChat() {
        val link = "https://mp.weixin.qq.com/s/zy-Q9Br15wBG0hNiUzK1Xw"
        assertEquals(link, ArticleHtml.url("[" + link + "](" + link + ")").toString())
    }
    @Test(expected = IllegalArgumentException::class) fun unknownLengthDownloadsStillRespectLimit() { ImportLimits.read(ByteArrayInputStream(ByteArray(4097)), 4096) }
    @Test fun webViewExtractionKeepsLongTextAndOriginalLineBreaks() {
        val text = "原文正文。".repeat(6000) + "\n\nFinal sentence."
        val data = JSONObject().put("title", "公众号文章").put("text", text).put("length", text.length).put("url", "https://mp.weixin.qq.com/s/article")
        val encoded = JSONObject.quote(data.toString())
        assertEquals(text, BrowserArticle.decode(encoded).text)
    }
    @Test(expected = IllegalArgumentException::class) fun webViewLengthOverflowIsNotQuietlyTruncated() {
        val data = JSONObject().put("title", "too long").put("text", "x".repeat(100001)).put("length", 200000).put("url", "https://example.com/article")
        BrowserArticle.decode(JSONObject.quote(data.toString()))
    }
}
