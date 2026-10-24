package com.suiting.podcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface SpeechEngine { suspend fun synthesize(text: String, voice: String, file: File); fun close() {} }

class EdgeSpeechEngine : SpeechEngine {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS).build()
    private var clockOffset = 0L
    companion object {
        const val VERSION = "143.0.3650.75"
        val voices = linkedMapOf("zh-CN-XiaoxiaoNeural" to "晓晓 · 女声", "zh-CN-YunxiNeural" to "云希 · 男声",
            "zh-CN-XiaoyiNeural" to "晓伊 · 女声", "en-US-AriaNeural" to "Aria · 英语女声", "system" to "手机系统中文声音")
        fun escape(text: String) = SpeechText.escape(text)
        fun fullVoiceName(voice: String): String {
            val match = Regex("^([a-z]{2,}-[A-Z]{2,})-(.+Neural)$").matchEntire(voice)
                ?: throw IllegalArgumentException("不支持的音色")
            return "Microsoft Server Speech Text to Speech Voice (" + match.groupValues[1] + ", " + match.groupValues[2] + ")"
        }
        fun ssml(text: String, voice: String): String =
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='" +
                fullVoiceName(voice) + "'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                escape(SpeechText.clean(text)) + "</prosody></voice></speak>"
        fun audioPayload(bytes: ByteArray): ByteArray? {
            require(bytes.size >= 2) { "音频响应不完整" }
            val n = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            require(2 + n <= bytes.size) { "音频响应头无效" }
            val headers = String(bytes, 2, n, Charsets.UTF_8)
            return if (headers.lineSequence().any { it.substringBefore(':').trim().equals("Path", true) && it.substringAfter(':').trim() == "audio" })
                bytes.copyOfRange(n + 2, bytes.size) else null
        }
    }
    private fun timestamp() = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(System.currentTimeMillis() + clockOffset))

    override suspend fun synthesize(text: String, voice: String, file: File) = withContext(Dispatchers.IO) {
        require(voice in voices && voice != "system")
        val requests = SpeechText.requests(text)
        if (requests.size == 1) { requestWithRetry(requests.single(), voice, file); return@withContext }
        file.parentFile!!.mkdirs()
        val assembled = File(file.path + ".part")
        val piece = File(file.path + ".request")
        try {
            FileOutputStream(assembled).use { output ->
                for (part in requests) {
                    ensureActive()
                    requestWithRetry(part, voice, piece)
                    check(assembled.length() + piece.length() <= 8L * 1024 * 1024) { "单段音频超出 8 MB，请拆短正文" }
                    piece.inputStream().use { it.copyTo(output) }
                    piece.delete()
                }
                output.fd.sync()
            }
            check(assembled.renameTo(file)) { "音频保存失败" }
        } finally { assembled.delete(); piece.delete(); File(piece.path + ".part").delete() }
    }

    private suspend fun requestWithRetry(text: String, voice: String, file: File) {
        for (attempt in 0..2) {
            try {
                try { withTimeout(75_000) { request(text, voice, file) } }
                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw IOException("语音请求超时", e) }
                return
            } catch (e: IOException) {
                if (e is EdgeClockError && e.serverTime != null) clockOffset = e.serverTime - System.currentTimeMillis()
                if (attempt == 2) throw IOException("Edge 生成失败（已尝试 3 次，音色 " + (voices[voice] ?: voice) + "）：" + e.message +
                    "。可点“检查并试听”测试固定示例；手机未安装中文 TTS 时，系统声音不可用。", e)
                delay(500L * (attempt + 1))
            }
        }
    }
    private class EdgeClockError(val serverTime: Long?) : IOException("服务拒绝连接（HTTP 403）")

    private suspend fun request(text: String, voice: String, file: File) = withContext(Dispatchers.IO) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.path + ".part")
        suspendCancellableCoroutine<Unit> { cont ->
            val done = AtomicBoolean(false)
            val output = FileOutputStream(temporary)
            val lock = Any()
            var received = 0L
            var binaryFrames = 0
            var serviceCode = ""
            fun fail(error: Throwable) {
                synchronized(lock) {
                    if (done.compareAndSet(false, true)) {
                        runCatching { output.close() }; temporary.delete()
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                }
            }
            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val requestId = UUID.randomUUID().toString().replace("-", "")
            val url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=" +
                EdgeToken.CLIENT_TOKEN + "&ConnectionId=" + connectionId + "&Sec-MS-GEC=" +
                EdgeToken.generate((System.currentTimeMillis() + clockOffset) / 1000) + "&Sec-MS-GEC-Version=2-" + VERSION
            val request = Request.Builder().url(url)
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US) + ";")
                .header("Pragma", "no-cache").header("Cache-Control", "no-cache").build()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    response.headers.getDate("Date")?.let { clockOffset = it.time - System.currentTimeMillis() }
                    val config = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                    try {
                        check(webSocket.send("X-Timestamp:" + timestamp() + "\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" + config + "\r\n"))
                        check(webSocket.send("X-RequestId:" + requestId + "\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" + timestamp() + "Z\r\nPath:ssml\r\n\r\n" + ssml(text, voice)))
                    } catch (e: Exception) { fail(IOException("语音请求发送失败", e)); webSocket.cancel() }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    synchronized(lock) {
                        if (done.get()) return
                        binaryFrames++
                        try { audioPayload(bytes.toByteArray())?.let { data ->
                            received += data.size
                            check(received <= 8L * 1024 * 1024) { "单段音频超出上限" }
                            output.write(data)
                        } } catch (e: Exception) { fail(e); webSocket.cancel() }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val headers = text.substringBefore("\r\n\r\n")
                    val path = headers.lineSequence().firstOrNull { it.startsWith("Path:", true) }?.substringAfter(':')?.trim()
                    headers.lineSequence().firstOrNull { it.startsWith("X-StatusCode:", true) }?.let { serviceCode = it.take(80) }
                    if (path != "turn.end") return
                    synchronized(lock) {
                        if (done.get()) return
                        try {
                            if (received <= 128) throw IOException("服务结束但没有音频（音频帧 " + binaryFrames + (if (serviceCode.isNotBlank()) "，" + serviceCode else "") + "）")
                            output.fd.sync(); output.close()
                            check(temporary.renameTo(file)) { "音频保存失败" }
                            if (done.compareAndSet(false, true) && cont.isActive) cont.resume(Unit)
                        } catch (e: Exception) { fail(e) }
                    }
                    webSocket.close(1000, "complete")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 403) fail(EdgeClockError(response.headers.getDate("Date")?.time))
                    else fail(IOException("语音连接失败" + (response?.let { "（HTTP " + it.code + "）" } ?: "") + "：" + t.message, t))
                    response?.close()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!done.get()) fail(IOException("语音连接提前结束（" + code + "）"))
                }
            })
            cont.invokeOnCancellation { socket.cancel(); fail(IOException("生成已停止")) }
        }
    }
    override fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}

data class SystemVoiceStatus(val available: Boolean, val message: String)

class SystemSpeechEngine(private val context: Context) : SpeechEngine {
    private var tts: TextToSpeech? = null
    private var description = ""
    companion object {
        suspend fun inspect(context: Context): SystemVoiceStatus {
            val engine = SystemSpeechEngine(context)
            return try { withTimeout(25_000) { engine.engine() }; SystemVoiceStatus(true, engine.description) }
            catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); SystemVoiceStatus(false, "系统语音引擎响应超时，可前往系统语音设置检查") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { SystemVoiceStatus(false, e.message ?: "未检测到可用中文 TTS") }
            finally { engine.close() }
        }
    }
    @Suppress("DEPRECATION")
    private suspend fun engine(): TextToSpeech = withContext(Dispatchers.Main) {
        tts?.let { return@withContext it }
        val pm = context.packageManager
        val packages = pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }.distinct()
        check(packages.isNotEmpty()) { "手机未安装向第三方应用开放的 TTS 引擎。手机自带朗读不一定能被本应用调用，可在系统语音设置中检查。" }
        val defaultPackage = Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        val failures = mutableListOf<String>()
        for (pkg in packages.sortedBy { if (it == defaultPackage) 0 else 1 }) {
            val initialized = CompletableDeferred<Int>()
            val candidate = TextToSpeech(context, { initialized.complete(it) }, pkg)
            try {
                if (withTimeout(8000) { initialized.await() } != TextToSpeech.SUCCESS) { failures += pkg + " 初始化失败"; continue }
                val voices = candidate.voices.orEmpty().filter { it.locale.language in setOf("zh", "zho", "chi") &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.country != "CN" }, { it.name }))
                val selected = voices.firstOrNull()
                val ready = if (selected != null) candidate.setVoice(selected) == TextToSpeech.SUCCESS
                    else candidate.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
                if (!ready) { failures += pkg + " 缺少中文语音包"; continue }
                tts = candidate
                description = "可用：" + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)) +
                    (selected?.let { if (it.isNetworkConnectionRequired) "（中文声音需联网）" else "（已安装中文声音）" } ?: "（中文）")
                return@withContext candidate
            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); failures += pkg + " 响应超时" }
            finally { if (tts !== candidate) candidate.shutdown() }
        }
        error("未检测到可用中文系统声音：" + failures.joinToString("；") + "。请在系统语音设置中安装或启用中文语音包。")
    }
    override suspend fun synthesize(text: String, voice: String, file: File) {
        val clean = SpeechText.clean(text)
        require(clean.any { it.isLetterOrDigit() }) { "这一段没有可朗读的文字" }
        val engine = engine()
        file.parentFile!!.mkdirs()
        val temporary = File(file.path + ".part")
        withTimeout(90_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val id = UUID.randomUUID().toString()
                val done = AtomicBoolean(false)
                fun fail(message: String) {
                    if (done.compareAndSet(false, true)) { temporary.delete(); if (cont.isActive) cont.resumeWithException(Exception(message)) }
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != id || done.get()) return
                        if (temporary.length() > 128 && temporary.renameTo(file)) { if (done.compareAndSet(false, true) && cont.isActive) cont.resume(Unit) }
                        else fail("系统语音引擎未生成有效音频。请先使用“检查并试听”确认引擎支持保存音频。")
                    }
                    @Deprecated("Legacy TTS callback") override fun onError(utteranceId: String?) { if (utteranceId == id) fail("系统语音合成失败，请检查中文语音包") }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) fail("系统语音合成失败（" + errorCode + "），请检查语音包与网络") }
                })
                if (engine.synthesizeToFile(clean, Bundle(), temporary, id) != TextToSpeech.SUCCESS) fail("系统语音引擎拒绝保存音频")
                cont.invokeOnCancellation { done.set(true); engine.stop(); temporary.delete() }
            }
        }
    }
    override fun close() { tts?.stop(); tts?.shutdown(); tts = null }
}
