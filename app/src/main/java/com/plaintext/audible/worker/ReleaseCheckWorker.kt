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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.plaintext.audible.MainActivity
import com.plaintext.audible.R
import com.plaintext.audible.data.ReleaseEntity
import com.plaintext.audible.data.ReleaseRepository
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class ReleaseCheckWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ensureChannels(appContext)
        val repo = ReleaseRepository(appContext)
        val prefs = repo.getPrefs()

        return try {
            val batch = repo.syncCatalog(forceRefresh = true, checkAbb = prefs.checkAbbDaily)

            for (rel in batch.sevenDayOut) {
                postReleaseNotification(
                    rel = rel,
                    channelId = CHANNEL_UPCOMING,
                    badge = "[T-7 DAYS]",
                    headline = "${rel.title} (${rel.releaseDateIso})"
                )
                repo.markNotified(rel.asin, sevenDay = true, releaseDay = rel.notifiedReleaseDay)
            }

            for (rel in batch.releaseDay) {
                postReleaseNotification(
                    rel = rel,
                    channelId = CHANNEL_RELEASE_DAY,
                    badge = "[OUT TODAY]",
                    headline = "${rel.title} — ${rel.authors}"
                )
                repo.markNotified(rel.asin, sevenDay = true, releaseDay = true)
            }

            for (rel in batch.narratorRevealed) {
                postReleaseNotification(
                    rel = rel,
                    channelId = CHANNEL_UPCOMING,
                    badge = "[NARRATOR ANNOUNCED]",
                    headline = "${rel.title} narrated by ${rel.narrators}"
                )
            }

            for (rel in batch.abbNewlyAvailable) {
                postAbbNotification(rel)
                repo.markAbbNotified(rel.asin)
            }

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun postReleaseNotification(
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

        val notifId = rel.asin.hashCode()
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            appContext,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteBookIntent = Intent(appContext, NotificationMuteReceiver::class.java).apply {
            action = NotificationMuteReceiver.ACTION_MUTE_BOOK
            putExtra(NotificationMuteReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(NotificationMuteReceiver.EXTRA_ASIN, rel.asin)
            putExtra(NotificationMuteReceiver.EXTRA_BOOK_TITLE, rel.title)
        }
        val pendingMuteBook = PendingIntent.getBroadcast(
            appContext,
            (rel.asin + "_mute_book").hashCode(),
            muteBookIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val seriesLine = if (rel.seriesTitle.isNotBlank()) {
            val seqStr = rel.seriesSequence?.let { " #$it" }.orEmpty()
            "Series: ${rel.seriesTitle}$seqStr • "
        } else ""
        val body = "${seriesLine}Author: ${rel.authors} • Narrator: ${rel.narrators}"

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_pts_book)
            .setContentTitle("$badge $headline")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .addAction(0, "Mute Book", pendingMuteBook)

        if (rel.seriesTitle.isNotBlank()) {
            val muteSeriesIntent = Intent(appContext, NotificationMuteReceiver::class.java).apply {
                action = NotificationMuteReceiver.ACTION_MUTE_SERIES
                putExtra(NotificationMuteReceiver.EXTRA_NOTIFICATION_ID, notifId)
                putExtra(NotificationMuteReceiver.EXTRA_SERIES_KEY, rel.seriesTitle)
            }
            val pendingMuteSeries = PendingIntent.getBroadcast(
                appContext,
                (rel.asin + "_mute_series").hashCode(),
                muteSeriesIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Mute Series", pendingMuteSeries)
        }

        NotificationManagerCompat.from(appContext).notify(notifId, builder.build())
    }

    private fun postAbbNotification(rel: ReleaseEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notifId = (rel.asin + "_abb").hashCode()
        val targetUrl = rel.abbPostUrl.ifBlank { "https://www.audible.com/pd/${rel.asin}" }
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
        val pendingView = PendingIntent.getActivity(
            appContext,
            notifId,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ABB)
            .setSmallIcon(R.drawable.ic_stat_pts_book)
            .setContentTitle("[ON ABB] ${rel.title}")
            .setContentText("By ${rel.authors} is now available on AudioBookBay.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingView)
            .setAutoCancel(true)
            .addAction(0, "Open ABB Post", pendingView)

        if (rel.abbMagnetUri.isNotBlank()) {
            val magnetIntent = Intent(Intent.ACTION_VIEW, Uri.parse(rel.abbMagnetUri))
            val pendingMagnet = PendingIntent.getActivity(
                appContext,
                (rel.asin + "_abb_mag").hashCode(),
                magnetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Magnet", pendingMagnet)
        }

        NotificationManagerCompat.from(appContext).notify(notifId, builder.build())
    }

    companion object {
        private const val UNIQUE_DAILY_WORK = "pts_audible_daily_check"
        private const val UNIQUE_IMMEDIATE_WORK = "pts_audible_immediate_check"
        const val CHANNEL_UPCOMING = "pts_channel_upcoming"
        const val CHANNEL_RELEASE_DAY = "pts_channel_release_day"
        const val CHANNEL_ABB = "pts_channel_abb"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val c1 = NotificationChannel(
                    CHANNEL_UPCOMING,
                    "Upcoming Releases & Narrator Updates",
                    NotificationManager.IMPORTANCE_HIGH
                )
                val c2 = NotificationChannel(
                    CHANNEL_RELEASE_DAY,
                    "Release Day Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                val c3 = NotificationChannel(
                    CHANNEL_ABB,
                    "AudioBookBay Availability Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannels(listOf(c1, c2, c3))
            }
        }

        fun scheduleDailyCheck(context: Context, targetHour: Int = 9, targetMinute: Int = 0) {
            ensureChannels(context)
            val now = LocalDateTime.now()
            var nextRun = now.withHour(targetHour.coerceIn(0, 23))
                .withMinute(targetMinute.coerceIn(0, 59))
                .withSecond(0)
            if (!nextRun.isAfter(now)) {
                nextRun = nextRun.plusDays(1)
            }
            val initialDelayMinutes = Duration.between(now, nextRun).toMinutes().coerceAtLeast(1L)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_DAILY_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun runImmediateCheck(context: Context) {
            ensureChannels(context)
            val req = OneTimeWorkRequestBuilder<ReleaseCheckWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
