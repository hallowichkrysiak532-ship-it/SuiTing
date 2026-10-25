package com.suiting.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ImportedText(val title: String, val text: String, val source: String, val notice: String = "", val coverUrl: String = "")

object ImportLimits {
    const val HTML_BYTES = 4 * 1024 * 1024
    const val FILE_BYTES = 20 * 1024 * 1024
    const val TEXT_FILE_BYTES = 2 * 1024 * 1024
    fun checked(text: String): String {
        val clean = SpeechText.clean(text).trim()
        require(clean.any { it.isLetterOrDigit() }) { "没有提取到可朗读的文字" }
        require(clean.length <= TextSegments.MAX_INPUT) { "内容超过 10 万字符，未截断或替换正文。请先将 PDF 或正文拆分成几期。" }
        return clean
    }
    fun read(input: InputStream, maxBytes: Int, active: () -> Boolean = { true }): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            check(active()) { "导入已取消" }
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size().toLong() + n <= maxBytes) { "文件或网页过大，请缩小内容后导入" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}

object ArticleHtml {
    fun url(raw: String): HttpUrl {
        val markdown = Regex("\\[[^\\]]*]\\((https?://[^\\s]+)\\)", RegexOption.IGNORE_CASE).find(raw.trim())?.groupValues?.get(1)
        val candidate = (markdown ?: Regex("https?://[^\\s<>\"“”\\]\\[]+", RegexOption.IGNORE_CASE).find(raw.trim())?.value
            ?.trimEnd('。', '，', ')', '）', ']', '】') ?: raw.trim()).replace("&amp;", "&")
        val secure = if (candidate.startsWith("http://", true)) "https://" + candidate.substring(7) else candidate
        return secure.toHttpUrlOrNull()?.takeIf { it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() }
            ?: throw IllegalArgumentException("请输入有效的网页链接（https://…）")
    }
    fun looksLikeLink(raw: String): Boolean = raw.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) }
    fun extract(bytes: ByteArray, address: String, charset: String? = null): ImportedText {
        val document = Jsoup.parse(ByteArrayInputStream(bytes), charset, address)
        val cover = ArticleArtwork.extract(document, address)
        val title = document.selectFirst("#activity-name, .rich_media_title")?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
            .ifBlank { document.title() }.trim()
        document.select("script, style, noscript, nav, footer, header, aside, form, iframe, button, svg, canvas, [hidden], [aria-hidden=true]").remove()
        val wechat = address.toHttpUrlOrNull()?.host.equals("mp.weixin.qq.com", true)
        val primary = document.selectFirst("#js_content")
        val candidates = document.select("article, main, [role=main], .entry-content, .post-content, .article-content, .markdown-body, #content")
        val root = primary ?: candidates.maxByOrNull { it.text().length - 2 * it.select("a").sumOf { a -> a.text().length } }
            ?: if (!wechat) document.body() else null
        require(root != null) { "公众号页面没有返回正文，可能需要验证或文章已失效。可点“打开页面提取”后重试。" }
        root.select(".rich_media_meta_list, #js_pc_qr_code, .qr_code_pc, .reward_area, #js_article_bottom_bar, .share_notice, .article-copyright, .related-posts").remove()
        val text = blocks(root)
        val letters = text.count { it.isLetterOrDigit() }
        require(letters >= 12 && !(text.length < 350 && listOf("环境异常", "完成验证", "访问过于频繁", "enable javascript", "access denied", "captcha").any { text.contains(it, true) })) {
            "网页未返回可读取正文，可能需要登录或验证。可点“打开页面提取”，或复制正文/选择文件导入。"
        }
        return ImportedText(title.take(100), ImportLimits.checked(text), address,
            "已提取正文 " + text.length + " 字符；不包含图片中的文字、视频和评论。请预览检查。", cover)
    }
    internal fun blocks(root: Element): String {
        val out = StringBuilder()
        val blocks = setOf("p", "div", "section", "article", "main", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "tr", "ul", "ol")
        fun newline() { while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1); if (out.isNotEmpty() && out.last() != '\n') out.append('\n') }
        fun visit(node: Node) {
            if (node is TextNode) { out.append(node.wholeText.replace(Regex("\\s+"), " ")); return }
            val element = node as? Element ?: return
            val tag = element.normalName()
            if (tag == "br") { newline(); return }
            if (tag in blocks) newline()
            element.childNodes().forEach(::visit)
            if (tag in blocks) {
                newline()
                if (tag in setOf("p", "section", "h1", "h2", "h3", "h4", "blockquote") && out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
            } else if (tag == "td" || tag == "th") out.append("  ")
        }
        visit(root)
        return out.lines().map { it.trim() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

class ArticleImporter(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build()) : AutoCloseable {
    private data class Download(val bytes: ByteArray, val url: String, val charset: String?)
    suspend fun read(raw: String): ImportedText {
        val address = ArticleHtml.url(raw)
        val request = Request.Builder().url(address).header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml").build()
        var result: Download? = null
        for (attempt in 0..1) {
            try { result = download(request); break }
            catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                val certificateError = generateSequence<Throwable>(e) { it.cause }.any { it is javax.net.ssl.SSLHandshakeException || it is javax.net.ssl.SSLPeerUnverifiedException }
                if (attempt == 1 || certificateError) throw e
                delay(500)
            }
        }
        val downloaded = result ?: error("没有收到网页内容")
        return withContext(Dispatchers.IO) { ArticleHtml.extract(downloaded.bytes, downloaded.url, downloaded.charset) }
    }
    private suspend fun download(request: Request): Download = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(IOException("网页读取失败：" + e.message + "。可尝试打开页面提取，或检查手机网络。", e)) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        require(it.isSuccessful) { "网页返回 HTTP " + it.code + "。可尝试页面提取，或复制正文导入。" }
                        require(it.request.url.scheme == "https") { "网页跳转到不支持的 HTTP 地址" }
                        val body = it.body ?: error("网页内容为空")
                        require(body.contentLength() <= ImportLimits.HTML_BYTES) { "网页超过 4 MB，无法直接提取" }
                        require(body.contentType()?.subtype?.contains("pdf") != true) { "这是 PDF 下载链接，请先下载 PDF，再选择文件导入" }
                        val bytes = body.byteStream().use { stream -> ImportLimits.read(stream, ImportLimits.HTML_BYTES) { cont.isActive } }
                        if (cont.isActive) cont.resume(Download(bytes, it.request.url.toString(), body.contentType()?.charset()?.name()))
                    }
                } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
            }
        })
    }
    override fun close() { client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
