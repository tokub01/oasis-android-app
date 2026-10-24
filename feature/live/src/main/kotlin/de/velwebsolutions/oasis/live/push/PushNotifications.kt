package de.velwebsolutions.oasis.live.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import de.velwebsolutions.oasis.core.model.PushPreview
import de.velwebsolutions.oasis.live.R

/** A push hint as the notification shows it: the generic line, or the named text once the preview was fetched. */
data class PushContent(val title: String, val body: String, val url: String?) {
    /** The same push with the named text of the Oasis server (PushPreviewController), if there is one. */
    fun with(preview: PushPreview?): PushContent = if (preview == null) this else copy(title = preview.title, body = preview.body)

    companion object {
        /**
         * Reads a push's data (`title`, `body`, `url`, `preview`) – the keys Laravel's push payload uses – with the
         * notification part as fallback.
         */
        fun from(data: Map<String, String>, notificationTitle: String?, notificationBody: String?): PushContent = PushContent(
            title = data["title"] ?: notificationTitle ?: "Oasis",
            body = data["body"] ?: notificationBody.orEmpty(),
            url = data["url"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("oasis://") }
        )
    }
}

/**
 * The notification channel and the notifications of push hints. A tap opens the app with the hint's web URL in
 * [EXTRA_URL], which MainActivity hands to the navigator.
 */
object PushNotifications {
    const val CHANNEL_ID = "hints"

    /** The intent extra MainActivity reads (`MainActivity.EXTRA_URL`). */
    const val EXTRA_URL = "de.velwebsolutions.oasis.url"

    /** Creates the channel "Hinweise" once (no-op afterwards). */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Hinweise", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Neue Hinweise und Chat-Nachrichten aus Oasis – nie mit dem Text eines Tropfens oder einer Nachricht."
        }
        manager.createNotificationChannel(channel)
    }

    /** The intent a tap starts: the app's launch intent with the URL of the target. */
    fun openIntent(context: Context, url: String?): Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (url != null) putExtra(EXTRA_URL, url)
    }

    /** Shows a push hint; does nothing while notifications are not allowed. */
    fun show(context: Context, content: PushContent, id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val tap = openIntent(context, content.url)?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_oasis)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(Notification.BigTextStyle().bigText(content.body))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_SOCIAL)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }
}
