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

/** The notification channel of a push: the member silences hints and chat messages separately in Android's settings. */
enum class PushChannel(val id: String, val title: String, val description: String) {
    Hints("hints", "Hinweise", "Neue Hinweise aus Oasis – nie mit dem Text eines Tropfens."),
    Chat("chat", "Chat-Nachrichten", "Neue Chat-Nachrichten – nie mit dem Text einer Nachricht.")
}

/** A push hint as the notification shows it: the generic line, or the named text once the preview was fetched. */
data class PushContent(val title: String, val body: String, val url: String?, val tag: String? = null) {
    /** The same push with the named text of the Oasis server (PushPreviewController), if there is one. */
    fun with(preview: PushPreview?): PushContent = if (preview == null) this else copy(title = preview.title, body = preview.body)

    /** Chat messages (`tag` "oasis-chat", or a link into the chat) go to their own channel, everything else to Hinweise. */
    val channel: PushChannel
        get() = if (tag == CHAT_TAG || url?.let { it.substringAfter("://").substringAfter('/').startsWith("app/nachrichten") } == true) {
            PushChannel.Chat
        } else {
            PushChannel.Hints
        }

    companion object {
        /** The web push `tag` of chat messages (NotifyChatRecipients); hints use "oasis-hinweise". */
        const val CHAT_TAG = "oasis-chat"

        /**
         * Reads a push's data (`title`, `body`, `url`, `tag`, `preview`) – the keys Laravel's push payload uses – with the
         * notification part as fallback.
         */
        fun from(data: Map<String, String>, notificationTitle: String?, notificationBody: String?): PushContent = PushContent(
            title = data["title"] ?: notificationTitle ?: "Oasis",
            body = data["body"] ?: notificationBody.orEmpty(),
            url = data["url"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("oasis://") },
            tag = data["tag"]?.takeIf { it.isNotBlank() }
        )
    }
}

/**
 * The notification channels and the notifications of push hints. A tap opens the app with the hint's web URL in
 * [EXTRA_URL], which the app (`AppHost`) hands to the navigator. Notifications of one kind are grouped (the web push `tag`,
 * iOS: `thread-id`).
 */
object PushNotifications {
    /** The intent extra the app reads (`AppHost.EXTRA_URL`). */
    const val EXTRA_URL = "de.velwebsolutions.oasis.url"

    /** Creates the channels "Hinweise" and "Chat-Nachrichten" once (no-op afterwards). */
    fun ensureChannel(context: Context) {
        val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
        PushChannel.entries.forEach { kind ->
            if (manager.getNotificationChannel(kind.id) == null) {
                val channel = NotificationChannel(kind.id, kind.title, NotificationManager.IMPORTANCE_DEFAULT)
                channel.description = kind.description
                manager.createNotificationChannel(channel)
            }
        }
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
        val channel = content.channel
        val notification = Notification.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_oasis)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(Notification.BigTextStyle().bigText(content.body))
            .setAutoCancel(true)
            .setCategory(if (channel == PushChannel.Chat) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_SOCIAL)
            .apply { content.tag?.let(::setGroup) }
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
