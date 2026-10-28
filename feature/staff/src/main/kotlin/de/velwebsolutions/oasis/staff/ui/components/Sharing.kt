package de.velwebsolutions.oasis.staff.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.velwebsolutions.oasis.staff.R
import java.io.File

/** The provider of the staff files (its own class, so it does not clash with other modules' providers). */
class StaffFileProvider : FileProvider(R.xml.staff_files)

/** The share sheet for the evidence archive and the drafted breach report. */
object Sharing {
    /** Hands [file] to the share sheet with read access for the chosen app. */
    fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.staff.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The ClipData carries the read grant to the share sheet's preview and the chosen app on every Android version.
        send.clipData = ClipData.newRawUri(file.name, uri)
        start(context, Intent.createChooser(send, title))
    }

    /**
     * Opens [file] read-only in another app (ACTION_VIEW with its MIME type) – reported files are never shown inside
     * Oasis. False when no app on the device opens this type.
     */
    fun openFile(context: Context, file: File, mimeType: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.staff.files", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Hands a text to the share sheet. */
    fun shareText(context: Context, text: String, title: String) {
        start(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No app to share with; nothing to do.
        }
    }
}
