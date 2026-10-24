package de.velwebsolutions.oasis.membership.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.velwebsolutions.oasis.membership.R
import java.io.File

/** The provider of the membership's files (its own class, so it does not clash with other modules' providers). */
class MembershipFileProvider : FileProvider(R.xml.membership_files)

/** The share sheet for files and texts of the membership screens. */
object FileSharing {
    private fun authority(context: Context) = "${context.packageName}.membership.files"

    /** Hands [file] to the share sheet ("Teilen": save, print, send). */
    fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(send, title))
    }

    /** Hands a text (a voucher code) to the share sheet. */
    fun shareText(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(send, title))
    }

    /** The MIME type of an invoice or export file. */
    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "html" -> "text/html"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No app to share with; nothing to do.
        }
    }
}
