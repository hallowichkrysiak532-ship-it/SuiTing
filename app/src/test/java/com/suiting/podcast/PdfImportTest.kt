package com.suiting.podcast

import android.content.res.AssetManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import java.io.File

class PdfImportTest {
    @get:Rule val temporary = TemporaryFolder()
    companion object {
        @JvmStatic @BeforeClass fun resources() {
            val assets = mock(AssetManager::class.java)
            `when`(assets.open(anyString())).thenAnswer { invocation ->
                val path = "assets/" + invocation.getArgument<String>(0)
                PdfImportTest::class.java.classLoader!!.getResourceAsStream(path) ?: throw java.io.FileNotFoundException(path)
            }
            PDFBoxResourceLoader::class.java.getDeclaredField("ASSET_MANAGER").apply { isAccessible = true }.set(null, assets)
        }
    }
    private fun fixture(vararg pages: String): File {
        val file = temporary.newFile("sample.pdf")
        PDDocument().use { document ->
            document.documentInformation.title = "Research notes"
            pages.forEach { text ->
                val page = PDPage(); document.addPage(page)
                PDPageContentStream(document, page).use { out ->
                    out.beginText(); out.setFont(PDType1Font.HELVETICA, 12f); out.newLineAtOffset(50f, 700f); out.showText(text); out.endText()
                }
            }
            document.save(file)
        }
        return file
    }
    @Test fun actualPdfTextIsExtractedAndPageRangeRespected() {
        val file = fixture("First page research notes.", "Second page contains different text.")
        val all = PdfTextImporter.read(file, temporary.newFolder())
        assertEquals("Research notes", all.title)
        assertTrue(all.text.contains("First page research notes.")); assertTrue(all.text.contains("Second page contains different text."))
        val second = PdfTextImporter.read(file, temporary.newFolder(), 2, 2)
        assertTrue(second.text.contains("Second page")); assertFalse(second.text.contains("First page"))
    }
    @Test(expected = IllegalArgumentException::class) fun unavailablePageRangeIsRejected() { PdfTextImporter.read(fixture("A real PDF page."), temporary.newFolder(), 2, 3) }
    @Test fun pdfWithoutTextReportsOcrRequirement() {
        val file = temporary.newFile("no-text.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(file) }
        try { PdfTextImporter.read(file, temporary.newFolder()); fail("Expected missing text layer") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("OCR")) }
    }
    @Test fun encryptedPdfHasAnActionableError() {
        val file = fixture("Protected document.")
        PDDocument.load(file.readBytes()).use { document -> document.protect(StandardProtectionPolicy("owner", "password", AccessPermission())); document.save(file) }
        try { PdfTextImporter.read(file, temporary.newFolder()); fail("Expected password requirement") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("密码")) }
    }
}
