package com.suiting.podcast

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/** Shared by direct HTML imports and WebView extraction. Never use favicons or site logos. */
internal object ArticleArtwork {
    fun normalize(raw: String, page: String): String {
        val value=Parser.unescapeEntities(raw.trim(),true).replace("\\/","/").replace("\\x26","&").replace("\\u0026","&")
        if(value.isBlank()||value.length>4096)return ""
        val base=page.toHttpUrlOrNull()?:return ""
        val resolved=base.resolve(value)?:return ""
        if(resolved.scheme !in setOf("http","https")||resolved.username.isNotEmpty()||resolved.password.isNotEmpty())return ""
        return resolved.newBuilder().scheme("https").fragment(null).build().toString()
    }

    fun extract(document: Document, page: String): String {
        val candidates=mutableListOf<String>()
        listOf("meta[property=og:image:secure_url]","meta[property=og:image]","meta[property=og:image:url]","meta[name=twitter:image]","meta[property=twitter:image]","meta[name=twitter:image:src]").forEach{selector->
            document.select(selector).forEach{candidates+=it.attr("content")}
        }
        document.select("link[rel=image_src]").forEach{candidates+=it.attr("href")}
        if(page.toHttpUrlOrNull()?.host=="mp.weixin.qq.com") {
            document.select("script").forEach {script->
                Regex("(?:var\\s+)?(?:msg_cdn_url|cdn_url)\\s*=\\s*['\"]([^'\"]+)['\"]").findAll(script.data()).forEach{candidates+=it.groupValues[1]}
            }
        }
        return candidates.firstNotNullOfOrNull{normalize(it,page).takeIf(String::isNotEmpty)}.orEmpty()
    }
}
