package com.suiting.podcast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.UUID

object PlainTextImport {
    fun decode(bytes: ByteArray): String {
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: java.nio.charset.CharacterCodingException) { String(bytes, Charset.forName("GB18030")) }
        }
        return ImportLimits.checked(text)
    }
}

object DocumentImporter {
    suspend fun read(context: Context, uri: Uri): ImportedText = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请通过系统文件选择器选择 PDF 文件" }
        val resolver = context.contentResolver
        var name = "导入文档"
        var knownSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0) name = cursor.getString(n) ?: name
                val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (s >= 0 && !cursor.isNull(s)) knownSize = cursor.getLong(s)
            }
        }
        val type = resolver.getType(uri).orEmpty()
        val pdf = type == "application/pdf" || name.endsWith(".pdf", true)
        require(pdf) { "目前仅开放 PDF 文件导入；其他内容可以直接粘贴正文" }
        val maximum = ImportLimits.FILE_BYTES
        require(knownSize <= maximum) { "PDF 超过 20 MB，请先拆分文件" }
        val current = currentCoroutineContext()
        check(context.cacheDir.usableSpace > 64L * 1024 * 1024) { "手机可用空间不足 64 MB，请先释放空间" }
        val directory = File(context.cacheDir, "imports/" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(directory, "source.pdf")
        try {
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        current.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= maximum) { "PDF 超过 20 MB，请先拆分文件" }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("无法打开 PDF，请重新选择")
            PDFBoxResourceLoader.init(context.applicationContext)
            val result = PdfTextImporter.read(file, directory, active = { current.isActive })
            result.copy(title = if (result.title == "source") name.substringBeforeLast('.').take(100) else result.title, source = name + " · " + result.source)
        } finally { directory.deleteRecursively() }
    }
}
