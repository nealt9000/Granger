package app.granger.core.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Implemented by the Application so the queue worker and the notification reach the one queue of the process. */
interface DownloadsHost {
    val downloadQueue: DownloadQueue
    val downloads: Downloads
}

/**
 * The one download worker (Plan DL Decision 1): unique work [WorkManagerScheduler.QUEUE_WORK], which drains the
 * [DownloadQueue] one file at a time. When only retrying or Wi-Fi-only records are left it books its own next run.
 */
class DownloadQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = DownloadNotifications(context)

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(QueueSummary())

    override suspend fun doWork(): Result {
        val queue = (applicationContext as DownloadsHost).downloadQueue
        try {
            setForeground(notifications.foregroundInfo(queue.summary()))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background; download anyway.
        }
        try {
            val rest = coroutineScope {
                val shown = launch {
                    while (true) {
                        delay(NOTIFY_EVERY_MS)
                        notifications.update(queue.summary())
                    }
                }
                queue.drain().also { shown.cancel() }
            }
            if (rest is QueueRest.Later) WorkManagerScheduler.later(WorkManager.getInstance(applicationContext), rest.delayMillis, rest.unmeteredOnly)
            return Result.success()
        } finally {
            // Paused or idle, it's gone; this also removes one posted when setForeground was refused.
            notifications.cancel()
        }
    }

    private companion object {
        const val NOTIFY_EVERY_MS = 2_000L
    }
}

/**
 * Work queued before Plan DL (one per item, or a group's chain) may still be in WorkManager after an update. It only
 * wakes the queue, which runs the records in their stored order.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

/**
 * Starts, stops and books [DownloadQueueWorker], and reaches the [queue] running in this process. [ensure] replaces a
 * waiting (delayed or Wi-Fi-bound) run with one that starts now; while the queue is draining it only tells it to look
 * again, so the running file is never cut off.
 */
class WorkManagerScheduler(context: Context, private val queueOf: () -> DownloadQueue) : DownloadScheduler {
    private val work = WorkManager.getInstance(context)
    private val queue: DownloadQueue get() = queueOf()

    override fun ensure(unmeteredOnly: Boolean) {
        if (queue.wake()) work.enqueueUniqueWork(QUEUE_WORK, ExistingWorkPolicy.REPLACE, request(0, unmeteredOnly))
    }

    override suspend fun stopRun(itemId: String) = queue.stopRun(itemId)

    override suspend fun stopQueue() {
        work.cancelUniqueWork(QUEUE_WORK)
        queue.stopRun(null)
    }

    override fun isRunning(itemId: String): Boolean = queue.isRunning(itemId)

    /** Work from before Plan DL would only wake the queue; it is cancelled at startup instead. */
    override fun forgetLegacyWork() {
        work.cancelAllWorkByTag(DownloadWorker::class.java.name)
    }

    companion object {
        const val QUEUE_WORK = "download-queue"

        /** The queue's next run, after the one finishing now: [delayMillis] on, once the network allows. */
        fun later(work: WorkManager, delayMillis: Long, unmeteredOnly: Boolean) {
            work.enqueueUniqueWork(QUEUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(delayMillis, unmeteredOnly))
        }

        private fun request(delayMillis: Long, unmeteredOnly: Boolean): OneTimeWorkRequest {
            val network = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            return OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}

/** The queue's one notification (Plan DL Decision 9): the file downloading, the round, the time left and Pause. */
internal class DownloadNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    fun foregroundInfo(summary: QueueSummary) =
        ForegroundInfo(NOTIFICATION_ID, build(summary), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Silently dropped when the user declined notifications. */
    fun update(summary: QueueSummary) {
        manager.notify(NOTIFICATION_ID, build(summary))
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    fun build(summary: QueueSummary): Notification {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, downloadsIntent(it), PendingIntent.FLAG_IMMUTABLE)
        }
        val pause = PendingIntent.getBroadcast(context, 0, pauseIntent(context), PendingIntent.FLAG_IMMUTABLE)
        val (title, text) = notificationText(summary)
        val permille = (summary.fraction * 1000).toInt()
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(1000, permille, summary.bytes <= 0)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, PAUSE, pause).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"

        /** The queue's one notification. */
        const val NOTIFICATION_ID = 0x6772616e

        const val PAUSE = "Pause"
    }
}

/** "Downloading Wikipedia Top" over "3 of 14 · about 25 min left". */
fun notificationText(summary: QueueSummary): Pair<String, String> {
    val title = summary.current?.title?.let { "Downloading $it" } ?: "Downloading"
    val parts = mutableListOf<String>()
    if (summary.files > 0) parts += "${summary.filesDone} of ${summary.files}"
    summary.timeLeftMillis?.let { parts += "${QueueText.timeLeft(it)} left" }
    return title to parts.joinToString(" · ").ifEmpty { "Starting…" }
}

/** What the notification's Pause sends: [DownloadActionReceiver], inside the app only. */
fun pauseIntent(context: Context): Intent =
    Intent(context, DownloadActionReceiver::class.java).setAction(DownloadActionReceiver.ACTION_PAUSE)

/** Pause all from the notification, through the app's [Downloads] (so the setting is kept and Home shows it). */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val host = context.applicationContext as? DownloadsHost ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                host.downloads.pause()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "app.granger.downloads.PAUSE"
    }
}

/** [isUnmetered] as it changes, for "Waiting for Wi-Fi". */
fun unmeteredFlow(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }

        override fun onLost(network: Network) {
            trySend(isUnmetered(context))
        }
    }
    trySend(isUnmetered(context))
    cm?.registerDefaultNetworkCallback(callback)
    awaitClose { cm?.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

/** Whether the network now is unmetered (Wi-Fi, Ethernet): what a Wi-Fi-only download may use. */
fun isUnmetered(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
}

/** The link a download notification opens: the app's Downloads screen (Plan U3b Decision 1). */
const val DOWNLOADS_LINK = "granger://downloads"

/**
 * The app's [launch] intent, made to open Downloads: [DOWNLOADS_LINK] as its data, for the activity to read. The
 * intent is explicit, so the link needs no intent filter. A running app gets it in onNewIntent rather than a second
 * activity on top.
 */
internal fun downloadsIntent(launch: Intent): Intent = Intent(launch)
    .setData(Uri.parse(DOWNLOADS_LINK))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
