package com.suiting.podcast

import org.junit.Assert.*
import org.junit.Test

class EdgeBatchesTest {
    @Test fun shortParagraphsShareRequestsAndLongParagraphsKeepTheirOwnRequest() {
        val segments = listOf(Segment("标题"), Segment("第一段。"), Segment("长正文".repeat(100)), Segment("第三段。"), Segment("第四段。"))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), EdgeBatches.plan(segments, { false }, true).map { it.indices })
        assertEquals(5, EdgeBatches.plan(segments, { false }, false).size)
    }

    @Test fun resumingNeverMergesAcrossAlreadyDownloadedParagraphs() {
        val segments = List(5) { Segment("第 $it 段") }
        assertEquals(listOf(listOf(0, 1), listOf(3, 4)), EdgeBatches.plan(segments, { it == 2 }, true).map { it.indices })
    }

    @Test fun batchLimitsIncludeXmlEscapingAndDoNotLoseAnyParagraphs() {
        val segments = List(35) { Segment("标题 <$it> & 内容。".repeat(5)) }
        val batches = EdgeBatches.plan(segments, { false }, true)
        assertEquals(segments.indices.toList(), batches.flatMap { it.indices })
        assertTrue(batches.all { it.indices.size <= 8 })
        assertTrue(batches.all { SpeechText.escape(it.indices.joinToString("\n") { i -> segments[i].text }).toByteArray().size <= 2400 })
    }

    @Test fun timingsPreserveChineseAndEnglishParagraphsIncludingPunctuation() {
        val words = listOf(EdgeWord("你好", 100, 200), EdgeWord("世界", 400, 250), EdgeWord("Hello", 1000, 300), EdgeWord("world", 1500, 300))
        assertEquals(listOf(AudioSlice(0, 1000), AudioSlice(1000, 2200)), EdgeBatches.slices(listOf("你好，世界。", "Hello world!"), words, 2200))
    }

    @Test fun repeatedTextUsesItsOwnOccurrenceAndDoesNotJumpBackwards() {
        val words = listOf(EdgeWord("你好", 100, 200), EdgeWord("你好", 800, 200), EdgeWord("你好", 1400, 200))
        assertEquals(listOf(AudioSlice(0, 800), AudioSlice(800, 1400), AudioSlice(1400, 1800)), EdgeBatches.slices(List(3) { "你好。" }, words, 1800))
    }

    @Test fun missingOrAmbiguousBoundariesFallBackToIndividualSynthesis() {
        assertNull(EdgeBatches.slices(listOf("第一段", "第二段"), emptyList(), 3000))
        assertNull(EdgeBatches.slices(listOf("第一段", "第二段"), listOf(EdgeWord("第一段第二段", 0, 2500)), 3000))
        assertNull(EdgeBatches.slices(listOf("12", "下一段"), listOf(EdgeWord("十二", 100, 300)), 3000))
        assertNull(EdgeBatches.slices(listOf("你好", "世界"), listOf(EdgeWord("你好", 1000, 200), EdgeWord("世界", 500, 200)), 3000))
    }

    @Test fun wordMetadataConvertsTicksAndUnescapesXml() {
        val data = """{"Metadata":[{"Type":"WordBoundary","Data":{"Offset":12340000,"Duration":2500000,"text":{"Text":"A&amp;B"}}},{"Type":"SessionEnd"}]}"""
        assertEquals(listOf(EdgeWord("A&B", 1234, 250)), EdgeBatches.parseMetadata(data))
    }
}
