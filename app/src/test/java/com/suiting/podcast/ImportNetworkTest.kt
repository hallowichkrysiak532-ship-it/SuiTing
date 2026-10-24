package com.suiting.podcast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ImportNetworkTest {
    @Test fun importSuppliedWechatArticle() = runBlocking {
        assumeTrue(System.getProperty("import.smoke") == "1")
        val importer = ArticleImporter()
        try {
            val result = importer.read("https://mp.weixin.qq.com/s/zy-Q9Br15wBG0hNiUzK1Xw")
            assertEquals("30B大模型跑进手机，推理引擎开始自我进化", result.title); assertTrue(result.text.length > 1000)
            val cleaned = ManuscriptCleaner.clean(result.text)
            val draft = ImportDraft.imported(result)
            assertEquals(result.text, draft.originalText)
            assertEquals(cleaned.text, draft.text)
            File("build/smoke/wechat-import-summary.txt").apply { parentFile!!.mkdirs() }.writeText("Title: " + result.title + "\nOriginal characters: " + result.text.length + "\nCleaned characters: " + draft.text.length + "\nRemoved links: " + cleaned.links + "\nRemoved invalid characters: " + cleaned.invalidCharacters + "\nParagraphs: " + TextSegments.split(draft.text).size)
        } finally { importer.close() }
    }
}
