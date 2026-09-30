package app.saveit.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.saveit.MainActivity
import app.saveit.R
import app.saveit.SaveItApp
import app.saveit.container
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Foreground service that keeps downloads alive while the app is in the background. */
class DownloadService : LifecycleService() {
    private var drainJob: Job? = null
    private var notifyJob: Job? = null
    private val queue get() = container.queue

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL_ALL) {
            queue.cancelAll()
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, progressNotification(queue.tasks.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (notifyJob?.isActive != true) {
            notifyJob = lifecycleScope.launch {
                queue.tasks.collectLatest { tasks ->
                    notifySafely(NOTIFICATION_ID, progressNotification(tasks))
                    delay(500) // throttle notification updates
                }
            }
        }
        if (drainJob?.isActive != true) {
            drainJob = lifecycleScope.launch {
                queue.drain()
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        notifyJob?.cancel()
        val tasks = queue.tasks.value
        val done = tasks.count { it.state == TaskState.DONE }
        val failed = tasks.count { it.state == TaskState.FAILED }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (done + failed > 0) {
            val text = buildString {
                append(if (done == 1) "1 file saved to your gallery" else "$done files saved to your gallery")
                if (failed > 0) append(" · $failed failed")
            }
            notifySafely(
                RESULT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, SaveItApp.CHANNEL_RESULTS)
                    .setSmallIcon(R.drawable.ic_stat_saveit)
                    .setContentTitle(if (failed > 0 && done == 0) "Download failed" else "Downloads finished")
                    .setContentText(text)
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .build(),
            )
        }
        stopSelf()
    }

    private fun progressNotification(tasks: List<DownloadTask>): Notification {
        val running = tasks.firstOrNull { it.state == TaskState.RUNNING }
        val queued = tasks.count { it.state == TaskState.QUEUED }
        val title = running?.post?.title?.take(60) ?: running?.post?.platform?.displayName ?: "Preparing downloads"
        val builder = NotificationCompat.Builder(this, SaveItApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_saveit)
            .setContentTitle(title)
            .setContentText(
                listOfNotNull(
                    running?.stage,
                    running?.progress?.let { "${(it * 100).toInt()}%" },
                    if (queued > 0) "$queued waiting" else null,
                ).joinToString(" · ").ifEmpty { "Starting…" },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notification_cancel), cancelIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val p = running?.progress
        if (p != null) builder.setProgress(100, (p * 100).toInt(), false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    @SuppressLint("MissingPermission") // checked just below
    private fun notifySafely(id: Int, n: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(id, n)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val RESULT_NOTIFICATION_ID = 43
        const val ACTION_CANCEL_ALL = "app.saveit.action.CANCEL_ALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
