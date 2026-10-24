package com.suiting.podcast

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener

object BrowserArticle {
    // These scripts only return text/metadata. There is no JavaScript interface into the app.
    val script = """
        (function() {
            var root = document.getElementById('js_content') || document.querySelector('article, main, [role=main], .entry-content, .post-content, #content');
            if (!root && location.hostname !== 'mp.weixin.qq.com') root = document.body;
            var text = root ? (root.innerText || root.textContent || '') : '';
            var title = document.getElementById('activity-name');
            return JSON.stringify({title: title ? title.innerText : document.title, text: text.slice(0,100001), length: text.length, url: location.href});
        })();
    """.trimIndent()
    val probeScript = """
        (function() {
            var root = document.getElementById('js_content') || document.querySelector('article, main, [role=main], .entry-content, .post-content, #content');
            var body = document.body ? (document.body.innerText || '') : '';
            var article = root ? (root.innerText || root.textContent || '') : '';
            return JSON.stringify({bodyCharacters: body.trim().length, articleCharacters: article.trim().length, images: document.images.length});
        })();
    """.trimIndent()
    fun decode(encoded: String): ImportedText {
        val raw = JSONTokener(encoded).nextValue() as? String ?: error("没有读取到页面正文，请等待页面加载后重试")
        val data = JSONObject(raw)
        require(data.optInt("length") <= TextSegments.MAX_INPUT) { "页面正文超过 10 万字符，没有截断，请分期复制正文或改用 PDF 导入" }
        val text = ImportLimits.checked(data.optString("text"))
        require(text.count { it.isLetterOrDigit() } >= 12) { "未找到文章正文。请先完成页面验证，并确认已看到正文后重试。" }
        return ImportedText(data.optString("title").trim().take(100), text, ArticleHtml.url(data.getString("url")).toString(),
            "从当前页面提取了 " + text.length + " 字符；请检查分段，正文可继续编辑。")
    }
}

private class BrowserViewHandle {
    var view: WebView? = null
    var released = false
}

@SuppressLint("SetJavaScriptEnabled")
@Composable fun WebArticleDialog(address: String, onDismiss: () -> Unit, onExtract: (ImportedText) -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var compatible by remember { mutableStateOf(false) }
    var state by remember(attempt) { mutableStateOf(BrowserLoadState(address)) }
    var extractionError by remember(attempt) { mutableStateOf("") }
    var showMore by remember(attempt) { mutableStateOf(false) }
    val handle = remember(attempt) { BrowserViewHandle() }

    fun retry(useCompatibility: Boolean) {
        handle.view?.stopLoading()
        compatible = useCompatibility
        attempt++
    }
    fun probe(view: WebView) {
        val navigation = state.navigation
        view.evaluateJavascript(BrowserArticle.probeScript) { encoded ->
            if (!handle.released && state.navigation == navigation) {
                runCatching { BrowserPageProbe.decode(encoded) }
                    .onSuccess { state = state.complete(it) }
                    .onFailure { if (state.phase != BrowserLoadState.Phase.FAILED) state = state.fail("页面未能显示正文，请尝试兼容重试。", "Page probe returned no document") }
            }
        }
    }
    LaunchedEffect(handle, state.navigation) {
        val navigation = state.navigation
        delay(30_000)
        if (!handle.released) {
            state = state.timeout(navigation)
            if (state.phase == BrowserLoadState.Phase.TIMED_OUT) handle.view?.stopLoading()
        }
    }
    LaunchedEffect(handle) {
        // Redirect loops must not keep restarting the navigation-specific timeout.
        delay(30_000)
        if (!handle.released && state.loading) {
            state = state.timeout(state.navigation)
            handle.view?.stopLoading()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss) { Text("返回") }
                    TextButton(onClick = { retry(compatible) }) { Text("刷新") }
                    Button(enabled = handle.view != null && !state.rendererGone, onClick = {
                        val view = handle.view
                        if (view == null) extractionError = "页面显示组件尚未就绪，请重试。"
                        else runCatching { view.evaluateJavascript(BrowserArticle.script) { result ->
                            if (!handle.released) runCatching { BrowserArticle.decode(result) }.onSuccess(onExtract)
                                .onFailure { extractionError = it.message ?: "提取失败，请等待正文显示后重试" }
                        } }.onFailure { extractionError = "页面显示组件暂不可用，请点兼容重试。" }
                    }) { Text("提取正文") }
                    Box {
                        TextButton(onClick = { showMore = true }) { Text("更多") }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(text = { Text("兼容重试") }, onClick = { showMore = false; retry(true) })
                            DropdownMenuItem(text = { Text("系统浏览器") }, onClick = {
                                showMore = false
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
                                    .onFailure { extractionError = "无法打开系统浏览器，可复制原链接到浏览器中打开。" }
                            })
                            DropdownMenuItem(text = { Text("复制诊断") }, onClick = {
                                showMore = false
                                val provider = runCatching { WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName } }.getOrNull().orEmpty()
                                val report = "随听 " + BuildConfig.VERSION_NAME + "\n设备：" + Build.MANUFACTURER + " " + Build.MODEL +
                                    " / Android " + Build.VERSION.RELEASE + "\nWebView：" + provider +
                                    "\n模式：" + (if (compatible) "兼容渲染" else "默认渲染") +
                                    "\n地址：" + BrowserCompatibility.reportAddress(state.address) +
                                    "\n状态：" + state.phase + "，进度 " + state.progress + "%，正文字符 " + state.articleCharacters +
                                    "\n" + state.message + "\n" + state.detail + "\n" + extractionError
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("随听网页诊断", report))
                                android.widget.Toast.makeText(context, "已复制诊断", android.widget.Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                }
                if (!state.loading) Text(if (state.phase == BrowserLoadState.Phase.READY && state.articleCharacters >= 12)
                    "正文 " + state.articleCharacters + " 字符" else state.message, style = MaterialTheme.typography.bodySmall,
                    color = if (state.phase == BrowserLoadState.Phase.FAILED || state.phase == BrowserLoadState.Phase.TIMED_OUT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (extractionError.isNotBlank()) Text(extractionError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                key(attempt) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { viewContext ->
                            FrameLayout(viewContext).apply {
                                val frame = this
                                setBackgroundColor(Color.WHITE)
                                try {
                                    val view = WebView(viewContext)
                                    handle.view = view
                                    view.setBackgroundColor(Color.WHITE)
                                    view.settings.apply {
                                        javaScriptEnabled = true // Normal WeChat pages/verification require scripts.
                                        allowFileAccess = false
                                        allowContentAccess = false
                                        domStorageEnabled = true
                                        loadsImagesAutomatically = true // Verification and article layouts can depend on images.
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        mediaPlaybackRequiresUserGesture = true
                                        if (compatible) userAgentString = BrowserCompatibility.mobileUserAgent(userAgentString)
                                    }
                                    if (compatible) {
                                        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                                    }
                                    view.webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(webView: WebView, progress: Int) {
                                            if (!handle.released) state = state.progress(progress)
                                        }
                                    }
                                    view.webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(webView: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                            if (!handle.released) { extractionError = ""; state = state.start(url) }
                                        }
                                        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                                            if (handle.released) return true
                                            val uri = request.url
                                            if (request.isForMainFrame) BrowserCompatibility.wechatHttps(uri.toString())?.let {
                                                webView.loadUrl(it); return true
                                            }
                                            if (uri.scheme !in setOf("https", "http")) {
                                                if (request.isForMainFrame && !handle.released) state = state.fail("页面需要跳转到其他应用，可尝试在系统浏览器或微信打开原链接。", "Unsupported navigation scheme: " + uri.scheme)
                                                return true
                                            }
                                            return false
                                        }
                                        override fun onPageFinished(webView: WebView, url: String) {
                                            if (!handle.released && url.startsWith("http", true) && state.phase != BrowserLoadState.Phase.FAILED) probe(webView)
                                        }
                                        override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                                            if (!handle.released) state = state.resourceError(request.isForMainFrame, error.errorCode, error.description.toString())
                                        }
                                        override fun onReceivedHttpError(webView: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                            if (!handle.released) state = state.httpError(request.isForMainFrame, response.statusCode)
                                        }
                                        override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
                                            handler.cancel()
                                            if (!handle.released) state = state.fail("网页证书校验失败，请检查手机时间或使用系统浏览器。", "SSL error " + error.primaryError)
                                        }
                                        override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
                                            if (!handle.released) {
                                                state = state.rendererStopped()
                                                handle.view = null
                                                frame.removeView(webView)
                                                webView.destroy()
                                            }
                                            return true
                                        }
                                    }
                                    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                    state = state.start(address)
                                    // View.post on a newly created, unattached View waits until attachment.
                                    view.post { if (!handle.released) view.loadUrl(address) }
                                } catch (e: Exception) {
                                    val partial = handle.view
                                    handle.view = null
                                    frame.removeAllViews()
                                    runCatching { partial?.stopLoading(); partial?.destroy() }
                                    state = state.fail("无法启动手机的网页显示组件，可在系统浏览器打开原链接。", e.javaClass.simpleName + ": " + e.message.orEmpty().take(200))
                                    addView(TextView(viewContext).apply { text = "网页显示组件不可用，请尝试上方的兼容重试或系统浏览器。" })
                                }
                            }
                        },
                        onReset = null,
                        onRelease = { frame ->
                            handle.released = true
                            val view = handle.view
                            handle.view = null
                            frame.removeAllViews()
                            view?.stopLoading()
                            view?.destroy()
                        }
                    )
                }
            }
        }
    }
}
