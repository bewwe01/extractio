package app.saveit.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.saveit.core.model.Platform
import app.saveit.core.net.NetscapeCookies
import app.saveit.core.net.SaveItCookieJar
import app.saveit.core.net.cookieDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Login sessions imported from the in-app WebView login. Cookies are stored encrypted
 * (EncryptedSharedPreferences, key in the Android Keystore) and handed to the engine as a
 * Netscape cookie file in app-private storage that is rewritten per use and removed on logout.
 */
@Suppress("DEPRECATION") // security-crypto is deprecated upstream but remains the simplest audited option.
class SessionStore(private val context: Context, private val cookieJar: SaveItCookieJar) {

    private val prefs: SharedPreferences? = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "sessions", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure { Log.e("SaveItSessions", "Encrypted storage unavailable; logins won't persist", it) }.getOrNull()

    private val memory = HashMap<Platform, String>()
    private val _loggedIn = MutableStateFlow(emptySet<Platform>())
    val loggedIn: StateFlow<Set<Platform>> = _loggedIn.asStateFlow()

    init {
        Platform.entries.forEach { p -> prefs?.getString(p.name, null)?.let { memory[p] = it } }
        memory.forEach { (p, header) -> seedJar(p, header) }
        _loggedIn.value = memory.keys.toSet()
    }

    fun isLoggedIn(p: Platform): Boolean = p in _loggedIn.value

    fun cookieHeader(p: Platform): String? = memory[p]

    fun save(p: Platform, cookieHeader: String) {
        memory[p] = cookieHeader
        prefs?.edit()?.putString(p.name, cookieHeader)?.apply()
        seedJar(p, cookieHeader)
        _loggedIn.value = memory.keys.toSet()
    }

    fun logout(p: Platform) {
        memory.remove(p)
        prefs?.edit()?.remove(p.name)?.apply()
        domainsFor(p).forEach { cookieJar.clearDomain(it) }
        cookieFileFor(p).delete()
        _loggedIn.value = memory.keys.toSet()
    }

    /** Netscape cookie file for yt-dlp, or null when not logged in to [p]. */
    fun cookieFile(p: Platform): File? {
        val header = memory[p] ?: return null
        val file = cookieFileFor(p)
        file.parentFile?.mkdirs()
        file.writeText(NetscapeCookies.build(domainsFor(p).associateWith { header }))
        file.setReadable(false, false)
        file.setReadable(true, true)
        return file
    }

    private fun cookieFileFor(p: Platform) = File(context.noBackupFilesDir, "cookies/${p.name.lowercase()}.txt")

    private fun seedJar(p: Platform, header: String) = domainsFor(p).forEach { cookieJar.seed(it, header) }

    companion object {
        fun domainsFor(p: Platform): List<String> = when (p) {
            Platform.X -> listOf("x.com", "twitter.com")
            else -> listOfNotNull(p.cookieDomain())
        }
    }
}
