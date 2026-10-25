package com.suiting.podcast

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ArtworkDownloadTest {
    private suspend fun httpsTest(run:suspend(MockWebServer,ArtworkDownload)->Unit) {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server=MockWebServer().apply{useHttps(serverTls.sslSocketFactory(),false);start()}
        val client=OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager).build()
        try{run(server,ArtworkDownload(client))}finally{client.dispatcher.cancelAll();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();server.shutdown()}
    }
    private fun url(server:MockWebServer,path:String)=server.url(path).newBuilder().host("localhost").build().toString()
    @Test fun imageRedirectAndArticleRefererArePreserved()=runBlocking {
        httpsTest{server,downloader->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",url(server,"/image.jpg")))
            server.enqueue(MockResponse().setHeader("Content-Type","image/jpeg").setBody("jpeg fixture"))
            assertEquals("jpeg fixture",downloader.read(url(server,"/cover"),url(server,"/article")).toString(Charsets.UTF_8))
            assertEquals(url(server,"/article"),server.takeRequest().getHeader("Referer"))
        }
    }
    @Test fun errorPagesAndOversizedUnknownLengthImagesAreRejected()=runBlocking {
        httpsTest{server,downloader->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("captcha"))
            server.enqueue(MockResponse().setHeader("Content-Type","image/jpeg").setChunkedBody("x".repeat(ArtworkDownload.MAX_BYTES+1),32768))
            repeat(2){try{downloader.read(url(server,"/image"),"");fail("Expected image limit or type rejection")}catch(_:IllegalArgumentException){}}
        }
    }
    @Test fun leavingAnImportCancelsTheImageRequestPromptly()=runBlocking {
        httpsTest{server,downloader->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val loading=async{downloader.read(url(server,"/slow"),"")}
            withContext(Dispatchers.IO){assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}
            withTimeout(1500){loading.cancelAndJoin()}
            assertTrue(loading.isCancelled)
        }
    }
}
