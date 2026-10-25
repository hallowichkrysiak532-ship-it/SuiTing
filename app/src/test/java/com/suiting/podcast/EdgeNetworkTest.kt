package com.suiting.podcast

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EdgeNetworkTest {
    @Test fun concurrentChineseSegmentsRemainComplete() = runBlocking {
        assumeTrue("Opt in with SUITING_EDGE_SMOKE=1", System.getProperty("edge.smoke") == "1")
        val files = (0 until EdgeBatches.WORKERS).map { File("build/smoke/edge-parallel-$it.mp3").apply { parentFile!!.mkdirs(); delete() } }
        withTimeout(90_000) {
            generateSegments(files.size, EdgeBatches.WORKERS, { EdgeSpeechEngine() }) { engine, index ->
                engine.synthesize("这是并行生成的第${index + 1}段音频。下载完成后，可以继续收听。", "zh-CN-XiaoxiaoNeural", files[index])
            }
        }
        files.forEach { file ->
            assertTrue("Complete audio: ${file.name}", file.length() > 1000)
            val bytes = file.readBytes()
            assertTrue("Expected MP3 frames", (0 until bytes.size - 1).any {
                (bytes[it].toInt() and 255) == 255 && (bytes[it + 1].toInt() and 224) == 224
            })
            assertTrue("No unfinished download", !File(file.path + ".part").exists())
        }
    }
    @Test fun mergedChineseParagraphsHaveExactWordTiming() = runBlocking {
        assumeTrue("Opt in with SUITING_EDGE_SMOKE=1", System.getProperty("edge.smoke") == "1")
        val texts = listOf("春天的故事。", "今天我们一起去公园。", "这里有很多美丽的花。")
        val file = File("build/smoke/edge-batch.mp3").apply { parentFile!!.mkdirs(); delete() }
        val engine = EdgeSpeechEngine()
        val words = try { engine.synthesizeBatch(texts, "zh-CN-XiaoxiaoNeural", file) } finally { engine.close() }
        // At 48 kbps CBR, 6 bytes represent 1 ms; allow for metadata/header overhead.
        val duration = file.length() / 6 + 1000
        val slices = EdgeBatches.slices(texts, words, duration)
        File("build/smoke/edge-batch-words.txt").writeText(words.joinToString("\n"))
        assertTrue("Word metadata: $words", words.isNotEmpty())
        assertTrue("Exact paragraph alignment: $words", slices != null && slices.size == texts.size)
        assertTrue(slices!!.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
    }
    @Test fun generateRealChineseSample() = sample("zh-CN-XiaoxiaoNeural", "edge-zh.mp3", MIXED)
    @Test fun generateMaleMixedSample() = sample("zh-CN-YunxiNeural", "edge-yunxi.mp3", MIXED)
    @Test fun generateXiaoyiMixedSample() = sample("zh-CN-XiaoyiNeural", "edge-xiaoyi.mp3", MIXED)
    @Test fun generateEnglishSample() = sample("en-US-AriaNeural", "edge-en.mp3", "An Opinionated Guide to ML Research. Choosing Problems. Your ability to choose the right problems is more important than your raw technical skill.")
    @Test fun longParagraphIsAssembledFromMultipleRequests() = sample("zh-CN-XiaoxiaoNeural", "edge-long.mp3", "Research & development. Read papers, keep notes, and improve your understanding. ".repeat(45))
    private fun sample(voice: String, name: String, text: String) = runBlocking {
        assumeTrue("Opt in with SUITING_EDGE_SMOKE=1", System.getProperty("edge.smoke") == "1")
        val file = File("build/smoke/" + name)
        file.parentFile!!.mkdirs(); file.delete()
        val engine = EdgeSpeechEngine()
        try { engine.synthesize(text, voice, file) }
        finally { engine.close() }
        assertTrue("Real audio was saved", file.length() > 1000)
        val bytes = file.readBytes()
        assertTrue("Expected MP3 frame or ID3", bytes.take(3) == "ID3".toByteArray().toList() || (0 until bytes.size - 1).any { (bytes[it].toInt() and 255) == 255 && (bytes[it + 1].toInt() and 224) == 224 })
    }
    companion object {
        private const val MIXED = "我无法直接生成语音或播放音频，但可以把文档整理成“可直接复制到 TTS/朗读软件”的朗读稿。下面是英文原文朗读版，已去掉网页页眉页脚和乱码，按段落整理。\n\n朗读稿：An Opinionated Guide to ML Research，作者 John Schulman。"
    }
}
