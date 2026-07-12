package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.ui.appLaunchIntent

/** Phone cue when turn-back HR heuristic fires after the morning wake. */
object TurnBackNotifier {
    private const val CHANNEL_ID = "noop_turn_back"
    private const val NOTIF_ID = 4308

    @SuppressLint("MissingPermission")
    fun onSettled(context: Context) {
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val openApp = PendingIntent.getActivity(
                context, 8,
                appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle("Still waking up?")
                .setContentText("Heart rate settled after your alarm — time to get up.")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Turn-back alarm",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Cue if heart rate falls again after your wake alarm."
                },
            )
        }
    }
}
