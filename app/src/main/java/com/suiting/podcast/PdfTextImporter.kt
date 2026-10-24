package com.suiting.podcast

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.io.Writer

object PdfTextImporter {
    fun read(file: File, scratch: File, first: Int = 1, last: Int? = null, active: () -> Boolean = { true }): ImportedText {
        require(file.length() <= ImportLimits.FILE_BYTES) { "PDF 超过 20 MB，请先拆分文件" }
        require(first >= 1 && (last == null || last >= first)) { "PDF 页码范围无效" }
        scratch.mkdirs()
        try {
            PDDocument.load(file, "", MemoryUsageSetting.setupMixed(4L * 1024 * 1024).setTempDir(scratch)).use { document ->
                val end = last ?: document.numberOfPages
                require(first <= document.numberOfPages && end <= document.numberOfPages) { "PDF 只有 " + document.numberOfPages + " 页，请修改页码范围" }
                require(end - first < 300) { "每份 PDF 最多 300 页，请先拆分文件后导入" }
                require(document.currentAccessPermission.canExtractContent()) { "这份 PDF 不允许提取文字，请使用允许提取的版本" }
                val output = LimitedTextWriter(active)
                val stripper = PDFTextStripper().apply { startPage = first; endPage = end; sortByPosition = true; lineSeparator = "\n"; paragraphEnd = "\n\n"; pageEnd = "\n\n" }
                stripper.writeText(document, output)
                val raw = output.toString()
                require(raw.count { it.isLetterOrDigit() } >= 3) { "未找到 PDF 文字层。这可能是扫描件或图片 PDF，需要先 OCR；本版暂不包含 OCR 模型。" }
                return ImportedText(document.documentInformation.title.orEmpty().ifBlank { file.nameWithoutExtension }.take(100),
                    ImportLimits.checked(raw), "PDF 第 " + first + "–" + end + " 页 / 共 " + document.numberOfPages + " 页",
                    "已在手机本地提取 " + (end - first + 1) + " 页。多栏论文、页眉页脚可能影响顺序，请预览检查；原 PDF 不会长期保留。")
            }
        } catch (e: InvalidPasswordException) { throw IllegalArgumentException("PDF 有密码，请先解锁后再导入", e) }
    }
    private class LimitedTextWriter(private val active: () -> Boolean) : Writer() {
        private val output = StringBuilder()
        override fun write(chars: CharArray, offset: Int, length: Int) {
            if (!active()) throw IOException("PDF 导入已取消")
            require(output.length.toLong() + length <= TextSegments.MAX_INPUT) { "提取文字超过 10 万字符，没有截断。请先拆分 PDF 后再导入。" }
            output.append(chars, offset, length)
        }
        override fun flush() {}
        override fun close() {}
        override fun toString() = output.toString()
    }
}
