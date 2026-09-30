package app.saveit.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.saveit.container
import app.saveit.core.model.Platform

/** Where to log in, which origins hold the session cookies, and the cookie that proves a login. */
private data class LoginTarget(val url: String, val cookieOrigins: List<String>, val sessionCookie: String?)

private fun Platform.loginTarget(): LoginTarget = when (this) {
    Platform.INSTAGRAM -> LoginTarget("https://www.instagram.com/accounts/login/", listOf("https://www.instagram.com"), "sessionid")
    Platform.FACEBOOK -> LoginTarget("https://m.facebook.com/login/", listOf("https://www.facebook.com", "https://m.facebook.com"), "c_user")
    Platform.SNAPCHAT -> LoginTarget("https://accounts.snapchat.com/accounts/v2/login", listOf("https://www.snapchat.com", "https://accounts.snapchat.com"), null)
    Platform.X -> LoginTarget("https://x.com/i/flow/login", listOf("https://x.com"), "auth_token")
    Platform.REDDIT -> LoginTarget("https://www.reddit.com/login/", listOf("https://www.reddit.com"), "reddit_session")
    Platform.TIKTOK -> LoginTarget("https://www.tiktok.com/login", listOf("https://www.tiktok.com"), "sessionid")
    Platform.PINTEREST -> LoginTarget("https://www.pinterest.com/login/", listOf("https://www.pinterest.com"), "_pinterest_sess")
    Platform.DIRECT -> error("No login for direct links")
}

private fun collectCookies(target: LoginTarget): String? {
    val cm = CookieManager.getInstance()
    val pairs = LinkedHashMap<String, String>()
    target.cookieOrigins.forEach { origin ->
        cm.getCookie(origin)?.split(';')?.forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty()) pairs[name] = part.substringAfter('=', "").trim()
        }
    }
    if (pairs.isEmpty()) return null
    if (target.sessionCookie != null && target.sessionCookie !in pairs) return null
    return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(platform: Platform, onDone: () -> Unit) {
    val context = LocalContext.current
    val sessions = context.container.sessions
    val target = remember(platform) { platform.loginTarget() }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Log in with your ${platform.displayName} account. SaveIt only keeps the session cookie, never your password.") }

    fun finish(save: Boolean) {
        if (save) collectCookies(target)?.let { sessions.save(platform, it) }
        // Keep the only copy of the session in encrypted storage, not in the WebView profile.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        onDone()
    }

    DisposableEffect(Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to ${platform.displayName}") },
                navigationIcon = { IconButton(onClick = { finish(save = false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel login") } },
                actions = { TextButton(onClick = { finish(save = true) }) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (collectCookies(target) != null && target.sessionCookie != null) {
                                    status = "Logged in. Tap Done to save the session."
                                }
                            }
                        }
                        loadUrl(target.url)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
