package com.suiting.podcast

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtworkTest {
    private val page="https://example.com/posts/research"
    private fun article(head:String)=ArticleHtml.extract("<html><head>$head</head><article>这是一篇用来测试原文封面提取的文章，正文应保持完整。</article></html>".toByteArray(),page)
    @Test fun articleCoverSurvivesImportDraftAndEpisodePersistence() {
        val imported=article("<meta property='og:image' content='../covers/research.jpg?a=1&amp;b=2'>")
        assertEquals("https://example.com/covers/research.jpg?a=1&b=2",imported.coverUrl)
        val draft=ImportDraft.imported(imported).copy(coverStyle=3)
        assertEquals(draft,ImportDraft.decode(draft.encode()))
        val episode=Episode(title=draft.title,voice="system",segments=TextSegments.split(draft.text),sourceUrl=draft.source,coverUrl=draft.coverUrl,coverStyle=draft.coverStyle)
        assertEquals(episode,EpisodeCodec.decode(EpisodeCodec.encode(listOf(episode))).single())
    }
    @Test fun twitterAndLinkMetadataAreFallbacksButSiteIconsAreNot() {
        assertEquals("https://cdn.example.com/cover.png",article("<meta property='og:image' content='data:image/png;base64,abc'><meta name='twitter:image' content='//cdn.example.com/cover.png'>").coverUrl)
        assertEquals("https://example.com/cover.jpg",article("<link rel='image_src' href='/cover.jpg'>").coverUrl)
        assertEquals("",article("<link rel='icon' href='/favicon.png'><img src='/avatar.png'>").coverUrl)
    }
    @Test fun wechatScriptCoverAndHtmlEntitiesAreRecognized() {
        val html="""<html><script>var msg_cdn_url = 'https:\/\/mmbiz.qpic.cn\/cover?x=1\x26y=2';</script><div id='js_content'>公众号原文的正文内容，在这里保留完整的一段文字。</div></html>"""
        assertEquals("https://mmbiz.qpic.cn/cover?x=1&y=2",ArticleHtml.extract(html.toByteArray(),"https://mp.weixin.qq.com/s/example").coverUrl)
    }
    @Test fun browserExtractionPreservesCoverWithoutNarratingMetadata() {
        val data=JSONObject().put("title","原文").put("text","这里是足够完整的文章正文，只朗读文字而不是图片地址。").put("length",30).put("url",page).put("coverUrl","/cover.jpg")
        val result=BrowserArticle.decode(JSONObject.quote(data.toString()))
        assertEquals("https://example.com/cover.jpg",result.coverUrl)
        assertFalse(result.text.contains("cover.jpg"))
    }
    @Test fun unavailableMetadataAndOldLibraryRemainCompatible() {
        val episode=Episode(title="历史节目",voice="system",segments=listOf(Segment("原来的正文")))
        val json=JSONArray(EpisodeCodec.encode(listOf(episode)))
        json.getJSONObject(0).apply{remove("sourceUrl");remove("coverUrl");remove("coverStyle")}
        assertEquals(episode,EpisodeCodec.decode(json.toString()).single())
        val draft=ImportDraft.decode("""{"title":"历史草稿","text":"正文"}""")
        assertEquals("",draft.coverUrl);assertEquals(-1,draft.coverStyle)
    }
    @Test fun coverUrlsCannotReferenceLocalFilesOrCredentials() {
        listOf("file:///sdcard/private.jpg","content://media/private","javascript:alert(1)","https://name:secret@example.com/cover.jpg").forEach{assertEquals("",ArticleArtwork.normalize(it,page))}
        assertEquals("https://cdn.example.com/a.jpg",ArticleArtwork.normalize("http://cdn.example.com/a.jpg#fragment",page))
    }
}
