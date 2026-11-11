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
        val pendingResult = goAsync()
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notifId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = ReleaseRepository(context.applicationContext)
                when (intent.action) {
                    ACTION_MUTE_BOOK -> {
                        val asin = intent.getStringExtra(EXTRA_ASIN).orEmpty()
                        val title = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
                        if (asin.isNotBlank()) {
                            repo.muteBook(asin, title)
                        }
                    }
                    ACTION_MUTE_SERIES -> {
                        val seriesKey = intent.getStringExtra(EXTRA_SERIES_KEY).orEmpty()
                        if (seriesKey.isNotBlank()) {
                            repo.muteSeries(seriesKey, seriesKey)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MUTE_BOOK = "com.plaintext.audible.ACTION_MUTE_BOOK"
        const val ACTION_MUTE_SERIES = "com.plaintext.audible.ACTION_MUTE_SERIES"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_SERIES_KEY = "extra_series_key"
    }
}
