package com.suiting.podcast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate, bounded image requests: never occupy a speech-generation worker. */
internal class ArtworkDownload(private val client: OkHttpClient=OkHttpClient.Builder()
    .connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()) {
    companion object { const val MAX_BYTES=4*1024*1024 }
    suspend fun read(url: String, source: String): ByteArray=suspendCancellableCoroutine {continuation->
        val address=ArticleArtwork.normalize(url,source.ifBlank{url})
        require(address.isNotEmpty()){"无效的封面地址"}
        val request=Request.Builder().url(address).header("User-Agent","Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .header("Accept","image/*")
        if(source.startsWith("https://"))request.header("Referer",source)
        val call=client.newCall(request.build())
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response) {
                try {response.use {
                    require(it.isSuccessful&&it.request.url.scheme=="https"){"封面暂不可用"}
                    val body=it.body?:error("空白封面")
                    require(body.contentType()?.type=="image"){"封面不是图片"}
                    require(body.contentLength()<=MAX_BYTES){"封面图片过大"}
                    val bytes=body.byteStream().use{stream->ImportLimits.read(stream,MAX_BYTES){continuation.isActive}}
                    if(continuation.isActive)continuation.resume(bytes)
                }} catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            }
        })
    }
}

/** Persistent, size-limited thumbnails keep original covers available offline. */
internal object ArtworkStore {
    private val writes=Mutex()
    private val downloader=ArtworkDownload()
    private val memory=object:LruCache<String,Bitmap>(8*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private const val DISK_BYTES=32L*1024*1024
    suspend fun load(context:Context,url:String,source:String):Bitmap?=withContext(Dispatchers.IO) {
        val address=ArticleArtwork.normalize(url,source.ifBlank{url})
        if(address.isEmpty())return@withContext null
        memory.get(address)?.let{return@withContext it}
        writes.withLock {
            memory.get(address)?.let{return@withLock it}
            val directory=File(context.applicationContext.filesDir,"artwork").apply{mkdirs()}
            val key=MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString(""){"%02x".format(it)}
            val file=File(directory,"$key.jpg")
            if(file.isFile) {
                val cached=BitmapFactory.decodeFile(file.path)
                if(cached!=null){file.setLastModified(System.currentTimeMillis());memory.put(address,cached);return@withLock cached}
                file.delete()
            }
            val bytes=downloader.read(address,source)
            currentCoroutineContext().ensureActive()
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=32_000_000){"封面尺寸不支持"}
            val options=BitmapFactory.Options().apply{
                inSampleSize=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>1536)inSampleSize*=2
            }
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:error("封面无法显示")
            val factor=minOf(1f,768f/maxOf(decoded.width,decoded.height))
            val bitmap=if(factor<1f)Bitmap.createScaledBitmap(decoded,(decoded.width*factor).toInt().coerceAtLeast(1),(decoded.height*factor).toInt().coerceAtLeast(1),true).also{decoded.recycle()} else decoded
            currentCoroutineContext().ensureActive()
            val atomic=AtomicFile(file)
            val out=atomic.startWrite()
            try{check(bitmap.compress(Bitmap.CompressFormat.JPEG,88,out));atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
            var total=directory.listFiles().orEmpty().sumOf{it.length()}
            directory.listFiles().orEmpty().filter{it!=file}.sortedBy{it.lastModified()}.forEach{old->val size=old.length();if(total>DISK_BYTES&&old.delete())total-=size}
            memory.put(address,bitmap)
            bitmap
        }
    }
}
