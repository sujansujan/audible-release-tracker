package com.plaintext.audible.worker

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        val magnetUri = intent.getStringExtra(EXTRA_MAGNET_URI).orEmpty()

        if (action == ACTION_COPY_MAGNET && magnetUri.startsWith("magnet:")) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ABB Magnet URI", magnetUri))
            runCatching {
                val magnetIntent = Intent(Intent.ACTION_VIEW, Uri.parse(magnetUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(magnetIntent)
            }
            if (notifId != -1) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notifId)
            }
            return
        }

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
        const val ACTION_COPY_MAGNET = "com.plaintext.audible.ACTION_COPY_MAGNET"
        const val EXTRA_NOTIFICATION_ID = "extra_notif_id"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_SERIES_KEY = "extra_series_key"
        const val EXTRA_MAGNET_URI = "extra_magnet_uri"
    }
}
