package com.suiting.podcast

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun researchArticleParagraphIsNotCutInto220CharacterFragments() {
        val paragraph = "In this essay, I provide some advice to up-and-coming researchers in machine learning, based on my experience doing research and advising others. The advice covers how to choose problems and organize your time. I also recommend the following prior essays on similar topics:"
        val source = "朗读稿：An Opinionated Guide to ML Research，作者 John Schulman\n\nChoosing Problems\n\n" + paragraph
        val parts = TextSegments.split(source)
        assertEquals(3, parts.size)
        assertEquals(paragraph, parts.last().text)
        assertEquals("Choosing Problems", parts[1].text)
    }
    @Test fun longParagraphSplitsAtSentencesRatherThanHalfAnEnglishWord() {
        val text = "Your ability to choose the right problems matters more than raw technical skill. " +
            "This taste develops by observing which ideas prosper and which ones are forgotten. " +
            "Seek advice from experienced researchers and keep a notebook of experiments."
        val parts = TextSegments.split(text, limit = 120)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.text.endsWith('.') })
        assertEquals(text.filterNot { it.isWhitespace() }, parts.joinToString("") { it.text }.filterNot { it.isWhitespace() })
    }
    @Test fun decimalsAndAbbreviationsAreNotSentenceEndings() {
        val text = "Dr. Smith studied version 3.14 with Prof. Jones and found consistent results. " +
            "The next experiment compared another model."
        val parts = TextSegments.split(text, limit = 90)
        assertEquals("Dr. Smith studied version 3.14 with Prof. Jones and found consistent results.", parts.first().text)
        assertTrue(parts.none { it.text.endsWith("Dr.") || it.text.endsWith("Prof.") || it.text.endsWith("3.") })
    }
    @Test fun copiedVisualLineBreaksAreRepairedWithoutJoiningSeparateParagraphs() {
        val text = "A machine learning\nresearch project needs patience.\n\n这是复制过来的一行\n接着是同一句话。"
        val parts = TextSegments.split(text)
        assertEquals(listOf("A machine learning research project needs patience.", "这是复制过来的一行接着是同一句话。"), parts.map { it.text })
        assertEquals(4, TextSegments.split(text, mode = TextSegments.Mode.KEEP_LINES).size)
    }
    @Test fun listItemsAndBlankLinesRemainSeparate() {
        val parts = TextSegments.split("1. Read papers\n2. Keep notes\n\n## Personal Development\n\nRead textbooks.")
        assertEquals(4, parts.size)
        assertTrue(parts[0].text.startsWith("1.")); assertTrue(parts[1].text.startsWith("2."))
    }
    @Test fun xmlCharactersAndUnpairedSurrogatesCannotInvalidateTheRequest() {
        val clean = SpeechText.clean("Hello\u000bworld\uFEFF\u0000\uD800\uFFFE😀")
        assertTrue(clean.contains("Hello world")); assertTrue(clean.contains("😀"))
        assertFalse(clean.contains('\uD800')); assertFalse(clean.contains('\uFFFE'))
        val xml = EdgeSpeechEngine.ssml("Hello <world> & \"research\"", "zh-CN-XiaoxiaoNeural")
        assertTrue(xml.contains("Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)"))
        assertTrue(xml.contains("Hello &lt;world&gt; &amp; &quot;research&quot;"))
    }
    @Test fun requestsRespectEscapedUtf8LimitIndependentlyOfDisplayParagraphs() {
        val text = "研究 <models> & learning 😀 is useful. ".repeat(150)
        val requests = SpeechText.requests(text)
        assertTrue(requests.size > 1)
        assertTrue(requests.all { SpeechText.escape(it).toByteArray(Charsets.UTF_8).size <= 2800 })
        assertTrue(requests.all { !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
        assertEquals(text.filterNot { it.isWhitespace() }, requests.joinToString("").filterNot { it.isWhitespace() })
    }
    @Test fun originalTextAndOldEpisodeFormatRemainCompatible() {
        val episode = Episode(title = "mixed", voice = "system", segments = listOf(Segment("旧段落")), sourceText = "原文\n\nOriginal text.")
        assertEquals(episode, EpisodeCodec.decode(EpisodeCodec.encode(listOf(episode))).single())
        // The old field order is unrestricted; construct a genuine v0.1.0-style object.
        val json = org.json.JSONArray(EpisodeCodec.encode(listOf(episode)))
        json.getJSONObject(0).remove("sourceText")
        assertEquals("", EpisodeCodec.decode(json.toString()).single().sourceText)
    }
    private class MemorySnapshot : SnapshotStore {
        var raw: String? = null
        var failWrites = false
        override fun read() = raw
        override fun write(raw: String) { check(!failWrites) { "disk full" }; this.raw = raw }
    }
    @Test fun deleteRemovesFailedEpisodeAudioAndTemporaryFilesAndSurvivesReload() {
        val store = MemorySnapshot()
        val base = temporary.newFolder()
        val library = Library(base, store)
        val e = Episode(title = "Failed", voice = "zh-CN-XiaoxiaoNeural", segments = listOf(Segment("正文")), status = "failed")
        library.add(e)
        val audio = library.file(e.id, 0)
        audio.parentFile!!.mkdirs(); audio.writeBytes(ByteArray(1024))
        File(audio.path + ".part").writeBytes(ByteArray(512))
        assertEquals(1536L, library.audioBytes())
        library.beginDelete(e.id); library.delete(e.id)
        assertNull(library.find(e.id)); assertFalse(audio.parentFile!!.exists())
        assertEquals(0L, library.audioBytes()); assertTrue(Library(base, store).episodes.value.isEmpty())
    }
    @Test fun lateGenerationOrPlaybackUpdatesCannotReviveDeletingEpisode() {
        val library = Library(temporary.newFolder(), MemorySnapshot())
        val e = Episode(title = "Generating", voice = "system", segments = listOf(Segment("正文", 3000)), status = "generating")
        library.add(e); library.beginDelete(e.id)
        library.update(e.id) { it.copy(status = "ready") }; library.progress(e.id, 0, 2000)
        assertEquals("deleting", library.find(e.id)!!.status)
        assertEquals(0L, library.find(e.id)!!.positionMs)
        library.delete(e.id)
        library.update(e.id) { it.copy(status = "ready") }; library.progress(e.id, 0, 1000)
        assertTrue(library.episodes.value.isEmpty())
    }
    @Test fun interruptedDeletionRemainsVisibleAndCanBeRetried() {
        val store = MemorySnapshot()
        val base = temporary.newFolder()
        val library = Library(base, store)
        val e = Episode(title = "Pending", voice = "system", segments = listOf(Segment("正文")))
        library.add(e); library.beginDelete(e.id)
        val restored = Library(base, store)
        assertEquals("failed", restored.find(e.id)!!.status)
        restored.beginDelete(e.id); restored.delete(e.id)
        assertNull(restored.find(e.id))
    }
    @Test fun failedManifestWriteDoesNotLeaveDeleteButtonPermanentlyDisabled() {
        val store = MemorySnapshot()
        val library = Library(temporary.newFolder(), store)
        val e = Episode(title = "Full disk", voice = "system", segments = listOf(Segment("正文")))
        library.add(e); library.beginDelete(e.id)
        store.failWrites = true
        library.deletionFailed(e.id, "disk full")
        assertEquals("failed", library.find(e.id)!!.status)
        assertEquals("disk full", library.find(e.id)!!.error)
        store.failWrites = false
        library.beginDelete(e.id); library.delete(e.id)
        assertNull(library.find(e.id))
    }
}
