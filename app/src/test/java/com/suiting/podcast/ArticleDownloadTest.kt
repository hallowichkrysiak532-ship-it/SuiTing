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

class ArticleDownloadTest {
    private fun url(server: MockWebServer, path: String) = server.url(path).newBuilder().host("localhost").build()
    private suspend fun httpsTest(run: suspend (MockWebServer, ArticleImporter) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val importer = ArticleImporter(client)
        try { run(server, importer) } finally { importer.close(); server.shutdown() }
    }
    @Test fun httpsRedirectAndArticleResponseAreReadEndToEnd() = runBlocking {
        httpsTest { server, importer ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url(server, "/article")))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><h1 id='activity-name'>测试文章</h1><div id='js_content'><p>这是可读取的公众号正文，包含中英文。</p><p>Research requires patience and careful experiments.</p></div></html>"))
            val article = importer.read(url(server, "/redirect").toString())
            assertEquals("测试文章", article.title)
            assertTrue(article.text.contains("Research requires")); assertTrue(article.source.endsWith("/article"))
        }
    }
    @Test fun deniedHttpResponseHasAnActionableError() = runBlocking {
        httpsTest { server, importer ->
            server.enqueue(MockResponse().setResponseCode(403))
            try { importer.read(url(server, "/article").toString()); fail("Expected HTTP error") }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("403")); assertTrue(e.message.orEmpty().contains("页面")) }
        }
    }
    @Test fun cancellingAnImportDoesNotWaitForTheWholeResponse() = runBlocking {
        httpsTest { server, importer ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val reading = async { importer.read(url(server, "/slow").toString()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(1500) { reading.cancelAndJoin() }
            assertTrue(reading.isCancelled)
        }
    }
    @Test fun interruptedDownloadIsRetriedWithoutReturningPartialArticle() = runBlocking {
        httpsTest { server, importer ->
            val body = "<html><title>Recovered</title><article><p>Research notes and complete article text.</p></article></html>"
            server.enqueue(MockResponse().setBody(body.repeat(100)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "text/html; charset=utf-8"))
            val result = importer.read(url(server, "/article").toString())
            assertEquals("Recovered", result.title)
            assertTrue(result.text.contains("complete article text"))
        }
    }
}
