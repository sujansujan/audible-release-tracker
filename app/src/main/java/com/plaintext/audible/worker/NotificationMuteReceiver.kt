package com.plaintext.audible.worker

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.plaintext.audible.data.ReleaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationMuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val asin = intent.getStringExtra(EXTRA_ASIN).orEmpty()
        val bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        val seriesKey = intent.getStringExtra(EXTRA_SERIES_KEY).orEmpty()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = ReleaseRepository(context)
                when (action) {
                    ACTION_MUTE_BOOK -> if (asin.isNotBlank()) {
                        repo.muteBook(asin, bookTitle.ifBlank { asin })
                    }
                    ACTION_MUTE_SERIES -> if (seriesKey.isNotBlank()) {
                        repo.muteSeries(seriesKey, seriesKey)
                    }
                }
                if (notifId != -1) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notifId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MUTE_BOOK = "com.plaintext.audible.ACTION_MUTE_BOOK"
        const val ACTION_MUTE_SERIES = "com.plaintext.audible.ACTION_MUTE_SERIES"
        const val EXTRA_NOTIFICATION_ID = "extra_notif_id"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_SERIES_KEY = "extra_series_key"
    }
}
