package com.plaintext.audible.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.plaintext.audible.data.ReleaseEntity
import com.plaintext.audible.data.ReleaseRepository
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class ReleaseCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = ReleaseRepository(applicationContext)
        val releases = repo.syncCatalog()
        val domain = repo.getPrefs().marketplace.domain
        val today = LocalDate.now()

        ensureChannel()

        for (rel in releases) {
            val relDate = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull() ?: continue
            val daysUntil = ChronoUnit.DAYS.between(today, relDate)

            if (daysUntil == 7L && !rel.notifiedSevenDay) {
                sendReleaseNotification(rel, domain, isReleaseDay = false)
                repo.dao.updateNotificationFlags(rel.asin, sevenDay = true, releaseDay = rel.notifiedReleaseDay)
            } else if (daysUntil == 0L && !rel.notifiedReleaseDay) {
                sendReleaseNotification(rel, domain, isReleaseDay = true)
                repo.dao.updateNotificationFlags(rel.asin, sevenDay = rel.notifiedSevenDay, releaseDay = true)
            }
        }
        return Result.success()
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audible Release Alerts (T-7d & T-0d)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications 1 week before and on release day for tracked audiobooks"
        }
        nm.createNotificationChannel(channel)
    }

    private fun sendReleaseNotification(rel: ReleaseEntity, domain: String, isReleaseDay: Boolean) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = rel.asin.hashCode()

        val tag = if (isReleaseDay) "[OUT TODAY]" else "[T-7 DAYS]"
        val title = "$tag ${rel.title}"
        val seriesPart = if (rel.seriesTitle.isNotBlank()) " (${rel.seriesTitle})" else ""
        val body = "By ${rel.authors} • Narrated by ${rel.narrators}$seriesPart"

        val query = URLEncoder.encode("${rel.title} ${rel.authors.split(",").firstOrNull().orEmpty()}", "UTF-8")
        val searchUrl = "https://www.$domain/search?keywords=$query&ipRedirectOverride=true&overrideBaseCountry=true"
        val audibleIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
        val contentPending = PendingIntent.getActivity(
            applicationContext,
            notifId,
            audibleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteBookIntent = Intent(applicationContext, NotificationMuteReceiver::class.java).apply {
            action = NotificationMuteReceiver.ACTION_MUTE_BOOK
            putExtra(NotificationMuteReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(NotificationMuteReceiver.EXTRA_ASIN, rel.asin)
            putExtra(NotificationMuteReceiver.EXTRA_BOOK_TITLE, rel.title)
        }
        val muteBookPending = PendingIntent.getBroadcast(
            applicationContext,
            notifId + 1,
            muteBookIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .addAction(0, "[ Mute Book ]", muteBookPending)

        if (rel.seriesTitle.isNotBlank()) {
            val muteSeriesIntent = Intent(applicationContext, NotificationMuteReceiver::class.java).apply {
                action = NotificationMuteReceiver.ACTION_MUTE_SERIES
                putExtra(NotificationMuteReceiver.EXTRA_NOTIFICATION_ID, notifId)
                putExtra(NotificationMuteReceiver.EXTRA_SERIES_KEY, rel.seriesTitle)
            }
            val muteSeriesPending = PendingIntent.getBroadcast(
                applicationContext,
                notifId + 2,
                muteSeriesIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "[ Mute Series ]", muteSeriesPending)
        }

        nm.notify(notifId, builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "pts_audible_releases"
        private const val WORK_NAME = "pts_audible_daily_check"

        fun scheduleDailyCheck(context: Context, targetHour: Int, targetMinute: Int) {
            val now = LocalDateTime.now()
            var nextRun = now.withHour(targetHour).withMinute(targetMinute).withSecond(0)
            if (!nextRun.isAfter(now)) {
                nextRun = nextRun.plusDays(1)
            }
            val initialDelayMinutes = Duration.between(now, nextRun).toMinutes().coerceAtLeast(1)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }
}
