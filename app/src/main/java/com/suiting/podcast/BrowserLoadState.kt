package com.suiting.podcast

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONTokener

data class BrowserPageProbe(val bodyCharacters: Int, val articleCharacters: Int, val images: Int) {
    companion object {
        fun decode(encoded: String): BrowserPageProbe {
            val raw = JSONTokener(encoded).nextValue() as? String ?: error("页面检查未返回结果")
            val data = JSONObject(raw)
            return BrowserPageProbe(data.optInt("bodyCharacters").coerceAtLeast(0), data.optInt("articleCharacters").coerceAtLeast(0), data.optInt("images").coerceAtLeast(0))
        }
    }
}

data class BrowserLoadState(
    val address: String,
    val navigation: Int = 0,
    val phase: Phase = Phase.LOADING,
    val progress: Int = 0,
    val articleCharacters: Int = 0,
    val message: String = "正在打开文章…",
    val detail: String = "",
    val rendererGone: Boolean = false
) {
    enum class Phase { LOADING, READY, FAILED, TIMED_OUT }
    val loading get() = phase == Phase.LOADING
    fun start(url: String) = BrowserLoadState(url, navigation + 1)
    fun progress(percent: Int) = copy(progress = percent.coerceIn(0, 100))
    fun fail(message: String, detail: String = "") = copy(phase = Phase.FAILED, message = message, detail = detail)
    fun resourceError(mainFrame: Boolean, code: Int, description: String): BrowserLoadState {
        if (!mainFrame) return this
        val message = when (code) {
            -2 -> "找不到网页地址，请检查网络后重试。"
            -6 -> "无法连接文章网站，请重试或在系统浏览器打开。"
            -8 -> "网页连接超时，可刷新或尝试兼容重试。"
            -11 -> "网页的安全连接未能建立，请检查手机时间或在系统浏览器打开。"
            else -> "网页加载失败，可刷新或在系统浏览器打开。"
        }
        return fail(message, "WebView error " + code + ": " + description.take(300))
    }
    fun httpError(mainFrame: Boolean, status: Int): BrowserLoadState = if (!mainFrame) this
        else fail("网站未能返回文章（HTTP " + status + "），可在系统浏览器检查链接。", "HTTP " + status)
    fun timeout(expectedNavigation: Int): BrowserLoadState = if (!loading || navigation != expectedNavigation) this
        else copy(phase = Phase.TIMED_OUT, message = "页面加载较久。可刷新、兼容重试或在系统浏览器打开；如果正文已经出现，仍可直接提取。", detail = "No completion callback within 30 seconds")
    fun complete(probe: BrowserPageProbe): BrowserLoadState {
        if (phase == Phase.FAILED) return this // Android can send onPageFinished after a failed request.
        if (probe.bodyCharacters == 0 && probe.articleCharacters == 0 && probe.images == 0) return fail("网页返回了空白内容，请尝试兼容重试或在系统浏览器打开。", "Empty document")
        return copy(phase = Phase.READY, progress = 100, articleCharacters = probe.articleCharacters, detail = "",
            message = when {
                probe.articleCharacters >= 12 -> "已读取正文 " + probe.articleCharacters + " 字符，可以提取。页面仍白屏时可尝试兼容重试。"
                probe.bodyCharacters == 0 -> "当前页面主要是图片，暂时没有可提取的文字。"
                else -> "页面已加载，请先完成验证并确认已看到正文，再点提取。"
            })
    }
    fun rendererStopped() = fail("网页显示组件已退出，请点兼容重试；草稿仍已保留。", "WebView renderer exited").copy(rendererGone = true)
}

object BrowserCompatibility {
    fun mobileUserAgent(system: String): String = system.replace("; wv", "").replace("Version/4.0 ", "")
    fun reportAddress(raw: String): String = raw.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.toString()
        ?: "未取得网页地址"
    fun wechatHttps(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        return if (url.scheme == "http" && url.host.equals("mp.weixin.qq.com", true))
            url.newBuilder().scheme("https").port(443).build().toString() else null
    }
}
