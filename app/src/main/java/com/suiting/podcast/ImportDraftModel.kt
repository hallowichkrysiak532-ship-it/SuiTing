package com.suiting.podcast

import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

data class ImportDraft(val title: String = "", val text: String = "", val source: String = "", val notice: String = "", val originalText: String = "", val coverUrl: String = "", val coverStyle: Int = -1) {
    fun encode(): String = JSONObject().put("title", title).put("text", text).put("source", source).put("notice", notice).put("originalText", originalText).put("coverUrl",coverUrl).put("coverStyle",coverStyle).toString()
    companion object {
        fun decode(raw: String): ImportDraft = JSONObject(raw).let { ImportDraft(it.optString("title"), it.optString("text"), it.optString("source"), it.optString("notice"), it.optString("originalText"),it.optString("coverUrl"),it.optInt("coverStyle",-1).coerceIn(-1,7)) }
        fun imported(value: ImportedText, previousTitle: String = ""): ImportDraft {
            val cleaned = ManuscriptCleaner.clean(value.text)
            // A shared URL is still needed to open the article, even though it is not narration.
            val body = cleaned.text.takeIf { it.any { c -> c.isLetterOrDigit() } } ?: value.text
            return ImportDraft(value.title.ifBlank { previousTitle }, body, value.source, value.notice + "\n" + cleaned.summary, value.text,value.coverUrl)
        }
    }
}

class ImportDraftModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ImportDraft())
    val draft = mutable.asStateFlow()
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow("")
    val pending = MutableStateFlow<ImportedText?>(null)
    val show = MutableStateFlow(false)
    private val snapshot = AtomicFile(File(application.filesDir, "import-draft.json"))
    private val writes = Mutex()
    private var saveJob: Job? = null
    private var importJob: Job? = null
    private var importTicket = 0
    private var revision = 0

    init {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching { if (snapshot.baseFile.exists()) snapshot.openRead().bufferedReader(Charsets.UTF_8).use { ImportDraft.decode(it.readText()) } else null }.getOrNull()
            }
            if (revision == 0 && restored != null) mutable.value = restored
        }
    }
    fun open() { show.value = true }
    fun close() { show.value = false }
    fun title(value: String) = change(mutable.value.copy(title = value.take(100)))
    fun text(value: String) = change(mutable.value.copy(text = value))
    fun cover(style: Int) = change(mutable.value.copy(coverStyle=style.coerceIn(-1,7)))
    fun clear() { importJob?.cancel(); pending.value = null; error.value = ""; change(ImportDraft()); show.value = false }
    fun sample(text: String) { change(ImportDraft("给耳朵的一封信", text, notice = "示例正文")); open() }
    private fun change(value: ImportDraft) {
        revision++
        mutable.value = value
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            try {
                delay(350)
                withContext(Dispatchers.IO) {
                    writes.withLock {
                        val stream = snapshot.startWrite()
                        try { stream.write(value.encode().toByteArray(Charsets.UTF_8)); snapshot.finishWrite(stream) }
                        catch (e: Exception) { snapshot.failWrite(stream); throw e }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = "草稿暂未保存：" + e.message }
        }
    }
    fun clipboard() {
        try {
            val clip = getApplication<Application>().getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.let { (0 until it.itemCount).mapNotNull { i -> it.getItemAt(i).text?.toString() }.joinToString("\n") }.orEmpty()
            require(text.isNotBlank()) { "剪贴板中没有可读取的文字，请重新复制，或选择文件/链接导入" }
            val hint = "直接从系统剪贴板读取了 " + text.length + " 字符。" +
                if (text.length == 20_000) "如果预期更多，剪贴板这次提供的就是 20000 字符；请改用链接或 PDF 文件获取全文。" else "未经过键盘剪贴板，也没有截取前 2 万字符。"
            offer(ImportedText(mutable.value.title, text, "系统剪贴板", hint))
        } catch (e: Exception) { error.value = e.message ?: "读取剪贴板失败" }
    }
    fun shareText(value: String) { open(); offer(ImportedText("", value, "其他应用分享", "收到分享正文 " + value.length + " 字符；可直接编辑或将链接放入链接导入。")) }
    fun link(value: String) = start {
        val importer = ArticleImporter()
        try { importer.read(value) } finally { importer.close() }
    }
    fun file(uri: Uri) = start { DocumentImporter.read(getApplication(), uri) }
    fun cleanText() {
        val current = mutable.value
        val cleaned = ManuscriptCleaner.clean(current.text)
        if (cleaned.text.none { it.isLetterOrDigit() }) { error.value = "清理后没有可朗读正文，请先打开链接提取文章"; return }
        change(current.copy(text = cleaned.text, originalText = current.originalText.ifBlank { current.text }, notice = cleaned.summary))
        error.value = ""
    }
    fun restoreOriginal() {
        val current = mutable.value
        if (current.originalText.isNotBlank()) change(current.copy(text = current.originalText, notice = "已恢复导入时的原稿；生成前仍会跳过网址和无效字符，可继续手动编辑。"))
    }
    private fun start(load: suspend () -> ImportedText) {
        if (busy.value) return
        val ticket = ++importTicket
        show.value = true; busy.value = true; error.value = ""
        importJob = viewModelScope.launch {
            try { offer(load()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "提取失败，请重试" }
            finally { if (ticket == importTicket) busy.value = false }
        }
    }
    fun cancel() { importTicket++; importJob?.cancel(); busy.value = false }
    fun offer(value: ImportedText) {
        if (mutable.value.text.isBlank() || mutable.value.text == value.text) apply(value) else pending.value = value
    }
    fun accept() { pending.value?.let(::apply); pending.value = null }
    private fun apply(value: ImportedText) {
        change(ImportDraft.imported(value, mutable.value.title))
        error.value = ""
    }
}
