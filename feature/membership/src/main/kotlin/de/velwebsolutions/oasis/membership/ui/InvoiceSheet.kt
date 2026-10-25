package de.velwebsolutions.oasis.membership.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.velwebsolutions.oasis.design.components.EmptyStateView
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.ScreenChrome
import java.io.File

/** "Rechnung OAS-2" from `Rechnung-OAS-2.html`. */
fun invoiceTitle(file: File): String = file.nameWithoutExtension.replace("Rechnung-", "Rechnung ")

/**
 * An invoice file shown full screen with "Teilen" (save or send the file) and "Drucken" (print or save as PDF); the
 * HTML document of the API is shown as it is, without scripts.
 */
@Composable
fun InvoiceSheet(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalScreenChrome provides ScreenChrome(onClose = onClose)) {
            InvoiceDocument(file)
        }
    }
}

@Composable
private fun InvoiceDocument(file: File) {
    val context = LocalContext.current
    // The API's HTML invoice, shown as it is, without scripts or file access; other files are only shared.
    val webView = if (file.extension == "html") {
        remember(file) {
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                loadDataWithBaseURL(null, file.readText(), "text/html", "UTF-8", null)
            }
        }
    } else {
        null
    }
    val title = invoiceTitle(file)
    OasisScreen(
        title = title,
        actions = {
            IconButton({ FileSharing.shareFile(context, file, FileSharing.mimeType(file), "Teilen") }, Modifier.testTag("invoice.share")) {
                Icon(Icons.Outlined.Share, contentDescription = "Teilen")
            }
            if (webView != null) {
                IconButton({ InvoicePdf.print(context, webView, file.nameWithoutExtension) }, Modifier.testTag("invoice.print")) {
                    Icon(Icons.Outlined.Print, contentDescription = "Drucken oder als PDF sichern")
                }
            }
        }
    ) {
        if (webView != null) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().testTag("invoice.document"))
        } else {
            EmptyStateView(
                file.name,
                Modifier.testTag("invoice.document"),
                message = "Über „Teilen“ kannst du die Rechnung speichern, drucken oder als PDF sichern.",
                icon = Icons.Outlined.Description
            )
        }
    }
}
