package com.plaintext.audible.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.plaintext.audible.data.ReleaseRepository

class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED
        ) {
            val prefs = ReleaseRepository(context).getPrefs()
            ReleaseCheckWorker.scheduleDailyCheck(
                context = context,
                targetHour = prefs.notifyHour,
                targetMinute = prefs.notifyMinute
            )
        }
    }
}
