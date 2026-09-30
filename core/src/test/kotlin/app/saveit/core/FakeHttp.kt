package app.saveit.core

import app.saveit.core.net.Http
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Canned HTTP responses keyed by URL prefix. Unmatched requests fail with 599 so tests never touch the network. */
class FakeHttp {
    data class Canned(val code: Int, val body: ByteArray, val contentType: String, val headers: Map<String, String>)

    private val routes = LinkedHashMap<String, Canned>()
    val requests = mutableListOf<String>()

    fun on(urlPrefix: String, code: Int = 200, body: String = "", contentType: String = "text/html", headers: Map<String, String> = emptyMap()) {
        routes[urlPrefix] = Canned(code, body.toByteArray(), contentType, headers)
    }

    fun onBytes(urlPrefix: String, bytes: ByteArray, contentType: String) {
        routes[urlPrefix] = Canned(200, bytes, contentType, emptyMap())
    }

    fun redirect(from: String, to: String, code: Int = 301) = on(from, code, "", headers = mapOf("Location" to to))

    private val interceptor = Interceptor { chain ->
        val url = chain.request().url.toString()
        requests += url
        // Longest matching prefix wins.
        val c = routes.entries.filter { url.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
            ?: Canned(599, "no fake route for $url".toByteArray(), "text/plain", emptyMap())
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(c.code)
            .message("fake")
            .apply { c.headers.forEach { (k, v) -> header(k, v) } }
            .header("Content-Type", c.contentType)
            .body(c.body.toResponseBody(c.contentType.toMediaType()))
            .build()
    }

    val http: Http = Http.create { addInterceptor(interceptor) }
}

fun fixture(name: String): String =
    requireNotNull(FakeHttp::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()
