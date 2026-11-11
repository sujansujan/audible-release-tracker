package com.plaintext.audible.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.plaintext.audible.MainActivity
import com.plaintext.audible.R
import com.plaintext.audible.data.AppDatabase
import com.plaintext.audible.data.ReleaseEntity
import com.plaintext.audible.data.ReleaseRepository
import java.util.concurrent.TimeUnit

class ReleaseCheckWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ensureChannels(appContext)
        val db = AppDatabase.getInstance(appContext)
        val repo = ReleaseRepository(appContext, db)

        val notifyPreorders = repo.getSetting("notify_preorders", "true") == "true"
        val notifyReleaseDay = repo.getSetting("notify_release_day", "true") == "true"
        val notifyAbb = repo.getSetting("notify_abb", "true") == "true"

        return try {
            val summary = repo.syncAllAuthors(checkAbb = true)

            if (notifyPreorders) {
                summary.newPreorders.forEach { rel ->
                    sendReleaseNotification(
                        rel = rel,
                        channelId = CHANNEL_PREORDERS,
                        badge = "[NEW PRE-ORDER]",
                        headline = "${rel.title} (${rel.releaseDateIso})"
                    )
                    repo.markNotifiedPreorder(rel.asin)
                }
            }

            if (notifyReleaseDay) {
                summary.releasedToday.forEach { rel ->
                    sendReleaseNotification(
                        rel = rel,
                        channelId = CHANNEL_RELEASE_DAY,
                        badge = "[OUT TODAY]",
                        headline = "${rel.title} by ${rel.authorName}"
                    )
                    repo.markNotifiedReleaseDay(rel.asin)
                }
            }

            if (notifyAbb) {
                summary.newlyAvailableOnAbb.forEach { rel ->
                    sendAbbNotification(rel)
                    repo.markNotifiedAbb(rel.asin)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun sendReleaseNotification(
        rel: ReleaseEntity,
        channelId: String,
        badge: String,
        headline: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            appContext,
            rel.asin.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val audibleIntent = Intent(Intent.ACTION_VIEW, Uri.parse(rel.productUrl))
        val pendingAudible = PendingIntent.getActivity(
            appContext,
            (rel.asin + "_audible").hashCode(),
            audibleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(appContext, NotificationMuteReceiver::class.java).apply {
            putExtra(NotificationMuteReceiver.EXTRA_ASIN, rel.asin)
            putExtra(NotificationMuteReceiver.EXTRA_NOTIFICATION_ID, rel.asin.hashCode())
        }
        val pendingMute = PendingIntent.getBroadcast(
            appContext,
            (rel.asin + "_mute").hashCode(),
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val seriesLine = rel.seriesTitle?.let { s ->
            val pos = rel.seriesPosition?.let { " #$it" } ?: ""
            "Series: $s$pos • "
        } ?: ""

        val bodyText = "${seriesLine}Author: ${rel.authorName} • Narrator: ${rel.narratorName}"

        val notif = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_pts_book)
            .setContentTitle("$badge $headline")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .addAction(0, "Audible", pendingAudible)
            .addAction(0, "Mute Book", pendingMute)
            .build()

        NotificationManagerCompat.from(appContext).notify(rel.asin.hashCode(), notif)
    }

    private fun sendAbbNotification(rel: ReleaseEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val targetUrl = rel.abbUrl ?: rel.productUrl
        val openAbbIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
        val pendingAbb = PendingIntent.getActivity(
            appContext,
            (rel.asin + "_abb").hashCode(),
            openAbbIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifId = (rel.asin + "_abb_notif").hashCode()
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ABB_AVAILABLE)
            .setSmallIcon(R.drawable.ic_stat_pts_book)
            .setContentTitle("[ON ABB] ${rel.title}")
            .setContentText("By ${rel.authorName} is now indexed on AudioBookBay.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingAbb)
            .setAutoCancel(true)
            .addAction(0, "Open ABB Post", pendingAbb)

        if (!rel.abbMagnetUri.isNullOrBlank()) {
            val magnetIntent = Intent(Intent.ACTION_VIEW, Uri.parse(rel.abbMagnetUri))
            val pendingMagnet = PendingIntent.getActivity(
                appContext,
                (rel.asin + "_magnet").hashCode(),
                magnetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Magnet Link", pendingMagnet)
        }

        NotificationManagerCompat.from(appContext).notify(notifId, builder.build())
    }

    companion object {
        const val WORK_NAME_PERIODIC = "pts_audible_periodic_sync"
        const val WORK_NAME_ONETIME = "pts_audible_immediate_sync"
        const val CHANNEL_PREORDERS = "pts_audible_preorders"
        const val CHANNEL_RELEASE_DAY = "pts_audible_release_day"
        const val CHANNEL_ABB_AVAILABLE = "pts_audible_abb_alerts"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val c1 = NotificationChannel(
                    CHANNEL_PREORDERS,
                    "New Pre-order Announcements",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when a tracked author announces a new upcoming audiobook"
                }
                val c2 = NotificationChannel(
                    CHANNEL_RELEASE_DAY,
                    "Release Day Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts on the exact day a tracked audiobook is released"
                }
                val c3 = NotificationChannel(
                    CHANNEL_ABB_AVAILABLE,
                    "AudioBookBay Availability Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when a tracked audiobook appears on AudioBookBay with a verified magnet"
                }
                mgr.createNotificationChannels(listOf(c1, c2, c3))
            }
        }

        fun schedulePeriodicSync(context: Context, intervalHours: Long, wifiOnly: Boolean) {
            ensureChannels(context)
            val safeHours = intervalHours.coerceIn(1L, 24L)
            val netType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(netType)
                .build()

            val req = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(safeHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun triggerImmediateSync(context: Context) {
            ensureChannels(context)
            val req = OneTimeWorkRequestBuilder<ReleaseCheckWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONETIME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
