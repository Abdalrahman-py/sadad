package com.sadad.app.feature.updates

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
import androidx.core.content.ContextCompat
import com.sadad.app.MainActivity
import com.sadad.app.R

/**
 * The daily check's way of reaching someone who never opens the app: one notification per release,
 * which opens the update screen. Tapping it drops the user on «تحديث» with the release notes, not
 * on a browser download page.
 */
internal object UpdateNotifications {
  const val CHANNEL_ID = "sadad_updates"

  /** The notification's data, so a cold start can route straight to the update screen. */
  const val URI = "sadad://update"

  private const val ID = 2001

  fun post(context: Context, manifest: UpdateManifest) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    )
    if (
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    )
        return
    val intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse(URI))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending =
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(R.string.update_notification_title, manifest.versionName)
            )
            .setContentText(context.getString(R.string.update_notification_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
    manager.notify(ID, notification)
  }

  fun cancel(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(ID)
  }
}
