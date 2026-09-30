package app.saveit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.saveit.core.MediaResolver
import app.saveit.core.download.MediaDownloader
import app.saveit.core.net.Http
import app.saveit.core.net.SaveItCookieJar
import app.saveit.data.AppDatabase
import app.saveit.data.MediaStoreSaver
import app.saveit.data.SessionStore
import app.saveit.data.SettingsRepository
import app.saveit.download.DownloadQueue
import app.saveit.engine.AndroidMediaTools
import app.saveit.engine.AndroidYtDlpEngine
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SaveItApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
        // Unpacking Python/yt-dlp/ffmpeg takes a few seconds on first launch; start it right away.
        container.engine.warmUp()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http.client }))
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.notification_channel_downloads), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.notification_channel_downloads_desc); setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, getString(R.string.notification_channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "downloads"
        const val CHANNEL_RESULTS = "results"
    }
}

/** Manual dependency container: one instance of each service for the whole process. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cookieJar = SaveItCookieJar()
    val http: Http = Http.create(cookieJar)
    val settings = SettingsRepository(app)
    val sessions = SessionStore(app, cookieJar)
    val engine = AndroidYtDlpEngine(app, scope)
    val tools = AndroidMediaTools(app, engine)
    val resolver = MediaResolver(http, engine, cookieFileFor = sessions::cookieFile, loggedIn = sessions::isLoggedIn)
    val downloader = MediaDownloader(http, engine, tools, cookieFileFor = sessions::cookieFile)
    val database: AppDatabase = AppDatabase.build(app)
    val saver = MediaStoreSaver(app)
    val queue = DownloadQueue(app, this)
}

val Context.container: AppContainer get() = (applicationContext as SaveItApp).container
