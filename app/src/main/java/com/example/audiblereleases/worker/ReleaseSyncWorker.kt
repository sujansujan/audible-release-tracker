package com.example.audiblereleases.worker

import android.app.*
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import com.example.audiblereleases.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.example.audiblereleases.data.*

class ReleaseSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val db = AppDatabase.get(applicationContext); val repository = AudibleRepository(); val items = (repository.fetchComingSoon() + repository.fetchNewReleases()).distinctBy { it.asin }; val oldAsins = db.releases().observeAll().first().map { it.asin }.toSet(); db.releases().upsertAll(items)
        val follows = db.follows().observeAll().first()
        val authors = follows.filter { it.kind == "author" }.map { it.value.lowercase() }; val series = follows.filter { it.kind == "series" }.map { it.value.lowercase() }
        val today = LocalDate.now(); val formatter = DateTimeFormatter.ofPattern("MM-dd-yy")
        val matches = items.filter { item -> !oldAsins.contains(item.asin) && (authors.any { a -> item.author.lowercase().contains(a) } || series.any { s -> item.series.lowercase().contains(s) }) && runCatching { !LocalDate.parse(item.releaseDate, formatter).isAfter(today) }.getOrDefault(false) }
        if (matches.isNotEmpty()) notify(matches.size, matches.first().title)
        Result.success()
    }.getOrElse { Result.retry() }

    private fun notify(count: Int, firstTitle: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("releases", "Release alerts", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(1001, NotificationCompat.Builder(applicationContext, "releases").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("New Audible releases").setContentText(if (count == 1) firstTitle else "$count new releases from your follows").setAutoCancel(true).build())
    }
}
