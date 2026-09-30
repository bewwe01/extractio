package app.saveit.core.net

import app.saveit.core.error.ErrorClassifier
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.SaveItException
import app.saveit.core.model.Platform
import app.saveit.core.util.parseJsonOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object UserAgents {
    const val DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    const val MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
}

/**
 * In-memory cookie jar. Login cookies imported from the WebView are seeded per platform domain; cookies
 * set by platforms during extraction (TikTok's tt_chain_token, Instagram's csrftoken...) are kept so the
 * later media download sends them back.
 */
class SaveItCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, Cookie>()

    private fun key(c: Cookie) = "${c.domain}|${c.path}|${c.name}"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { store[key(it)] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.values.removeIf { it.expiresAt < now }
        return store.values.filter { it.matches(url) }
    }

    /** Seeds cookies from a `name=value; name2=value2` header string (as returned by Android's CookieManager). */
    fun seed(domain: String, cookieHeader: String) {
        val url = "https://${domain.trimStart('.')}/".toHttpUrlOrNull() ?: return
        cookieHeader.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty()) null else Cookie.Builder()
                .domain(url.host).path("/").name(name).value(value).secure()
                .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)
                .build()
        }.forEach { store[key(it)] = it }
    }

    fun get(host: String, name: String): String? =
        store.values.firstOrNull { it.name == name && (host == it.domain || host.endsWith("." + it.domain)) }?.value

    fun clearDomain(domain: String) {
        store.values.removeIf { it.domain == domain || it.domain.endsWith(".$domain") }
    }
}

class HttpResult(val code: Int, val finalUrl: String, val body: String, val headers: Map<String, String>) {
    val isSuccess: Boolean get() = code in 200..299
    fun json(): JsonElement? = parseJsonOrNull(body)
}

/** Thin coroutine wrapper around OkHttp shared by extractors and the downloader. */
class Http(
    val client: OkHttpClient,
    val cookieJar: SaveItCookieJar,
) {
    companion object {
        fun create(cookieJar: SaveItCookieJar = SaveItCookieJar(), configure: OkHttpClient.Builder.() -> Unit = {}): Http {
            val client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .apply(configure)
                .build()
            return Http(client, cookieJar)
        }
    }

    /** Client that does not follow redirects, for step-by-step short link resolution. */
    val noRedirectClient: OkHttpClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    fun request(url: String, headers: Map<String, String> = emptyMap(), userAgent: String = UserAgents.DESKTOP): Request =
        Request.Builder().url(url).apply {
            header("User-Agent", userAgent)
            header("Accept-Language", "en-US,en;q=0.9")
            headers.forEach { (k, v) -> header(k, v) }
        }.build()

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = UserAgents.DESKTOP,
        maxBytes: Int = 12 * 1024 * 1024,
    ): HttpResult = withContext(Dispatchers.IO) {
        client.newCall(request(url, headers, userAgent)).await().use { resp ->
            val source = resp.body?.source()
            val body = if (source == null) "" else {
                source.request(maxBytes.toLong())
                val n = minOf(source.buffer.size, maxBytes.toLong())
                source.buffer.readUtf8(n)
            }
            HttpResult(resp.code, resp.request.url.toString(), body, resp.headers.toMap())
        }
    }

    /** GET that throws a classified [SaveItException] on non-2xx. */
    suspend fun getOk(url: String, platform: Platform, headers: Map<String, String> = emptyMap(), userAgent: String = UserAgents.DESKTOP): HttpResult {
        val r = get(url, headers, userAgent)
        if (!r.isSuccess) throw SaveItException(ErrorClassifier.fromHttpStatus(r.code), platform, "HTTP ${r.code} for ${url.substringBefore('?')}")
        return r
    }

    suspend fun getJson(url: String, platform: Platform, headers: Map<String, String> = emptyMap(), userAgent: String = UserAgents.DESKTOP): JsonElement {
        val r = getOk(url, platform, headers + mapOf("Accept" to "application/json"), userAgent)
        return r.json() ?: throw SaveItException(ErrorKind.ENGINE_OUTDATED, platform, "Unexpected non-JSON response")
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

fun Platform.cookieDomain(): String? = when (this) {
    Platform.REDDIT -> "reddit.com"
    Platform.X -> "x.com"
    Platform.INSTAGRAM -> "instagram.com"
    Platform.TIKTOK -> "tiktok.com"
    Platform.SNAPCHAT -> "snapchat.com"
    Platform.FACEBOOK -> "facebook.com"
    Platform.PINTEREST -> "pinterest.com"
    Platform.DIRECT -> null
}
