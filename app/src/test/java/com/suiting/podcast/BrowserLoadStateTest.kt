package com.suiting.podcast

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserLoadStateTest {
    private val url = "https://mp.weixin.qq.com/s/example"
    @Test fun mainDocumentErrorStopsTheSpinner() {
        val state = BrowserLoadState(url).start(url).resourceError(true, -6, "net::ERR_CONNECTION_FAILED")
        assertFalse(state.loading)
        assertEquals(BrowserLoadState.Phase.FAILED, state.phase)
        assertTrue(state.message.contains("连接")); assertTrue(state.detail.contains("-6"))
    }
    @Test fun failingImagesDoNotHideAUsableDocument() {
        val state = BrowserLoadState(url).complete(BrowserPageProbe(3000, 2500, 8))
        assertEquals(state, state.resourceError(false, -2, "image DNS failure"))
        assertEquals(state, state.httpError(false, 404))
    }
    @Test fun pageFinishedAfterAnHttpErrorDoesNotEraseTheError() {
        val state = BrowserLoadState(url).httpError(true, 403)
        val after = state.complete(BrowserPageProbe(100, 0, 0))
        assertEquals(state, after); assertTrue(after.message.contains("403"))
    }
    @Test fun emptyDocumentHasAVisibleRetryMessageInsteadOfSilentWhiteScreen() {
        val state = BrowserLoadState(url).complete(BrowserPageProbe(0, 0, 0))
        assertEquals(BrowserLoadState.Phase.FAILED, state.phase)
        assertTrue(state.message.contains("空白")); assertTrue(state.message.contains("兼容"))
    }
    @Test fun usableTextCanBeExtractedEvenWhenTheRenderedPageIsBlank() {
        val state = BrowserLoadState(url).complete(BrowserPageProbe(0, 3500, 0))
        assertEquals(BrowserLoadState.Phase.READY, state.phase)
        assertEquals(3500, state.articleCharacters)
        assertTrue(state.message.contains("3500"))
    }
    @Test fun timeoutStopsLoadingAndProvidesRecoveryOptions() {
        val start = BrowserLoadState(url).start(url)
        val state = start.timeout(start.navigation)
        assertFalse(state.loading)
        assertEquals(BrowserLoadState.Phase.TIMED_OUT, state.phase)
        assertTrue(state.message.contains("系统浏览器"))
    }
    @Test fun anOldNavigationTimerCannotTimeOutANewPage() {
        val old = BrowserLoadState(url).start(url)
        val next = old.start("https://mp.weixin.qq.com/s/next")
        assertEquals(next, next.timeout(old.navigation))
    }
    @Test fun lateDocumentSuccessCanRecoverFromATimeout() {
        val start = BrowserLoadState(url).start(url)
        val state = start.timeout(start.navigation).complete(BrowserPageProbe(3000, 2800, 4))
        assertEquals(BrowserLoadState.Phase.READY, state.phase)
        assertEquals("", state.detail)
    }
    @Test fun rendererFailureRequiresANewView() {
        val failed = BrowserLoadState(url).rendererStopped()
        assertTrue(failed.rendererGone); assertFalse(failed.loading)
        val retry = failed.start(url)
        assertFalse(retry.rendererGone); assertTrue(retry.loading)
    }
    @Test fun compatibilityUserAgentKeepsTheActualBrowserVersion() {
        val raw = "Mozilla/5.0 (Linux; Android 12; Test wv; wv) Version/4.0 Chrome/125.0.6422.1 Mobile Safari/537.36"
        val agent = BrowserCompatibility.mobileUserAgent(raw)
        assertFalse(agent.contains("; wv")); assertFalse(agent.contains("Version/4.0"))
        assertTrue(agent.contains("Chrome/125.0.6422.1"))
    }
    @Test fun diagnosticAddressesDoNotContainCredentialsOrQueryTokens() {
        val address = BrowserCompatibility.reportAddress("https://user:password@mp.weixin.qq.com/s/example?token=secret#fragment")
        assertEquals(url, address)
        assertFalse(address.contains("password")); assertFalse(address.contains("secret"))
    }
    @Test fun onlyWechatHttpNavigationIsUpgraded() {
        assertEquals(url, BrowserCompatibility.wechatHttps("http://mp.weixin.qq.com/s/example"))
        assertNull(BrowserCompatibility.wechatHttps("https://mp.weixin.qq.com/s/example"))
        assertNull(BrowserCompatibility.wechatHttps("http://mp.weixin.qq.com.example.org/s/example"))
    }
    @Test fun pageProbeReadsCountsWithoutTheArticleBody() {
        val json = JSONObject().put("bodyCharacters", 3500).put("articleCharacters", 3000).put("images", 6)
        val probe = BrowserPageProbe.decode(JSONObject.quote(json.toString()))
        assertEquals(BrowserPageProbe(3500, 3000, 6), probe)
    }
    @Test fun aCompletedPageDoesNotBecomeTimedOutLater() {
        val ready = BrowserLoadState(url).complete(BrowserPageProbe(300, 200, 1))
        assertEquals(ready, ready.timeout(ready.navigation))
    }
}
