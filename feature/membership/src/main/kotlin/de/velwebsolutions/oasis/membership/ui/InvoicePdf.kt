package de.velwebsolutions.oasis.membership.ui

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView

/**
 * Turns the invoice HTML of the API into a PDF on the device – the web's "über die Druckfunktion deines Browsers
 * speichern": the system print dialog of the shown document, whose "Als PDF speichern" writes the PDF (iOS renders it
 * with WebKit instead).
 */
object InvoicePdf {
    fun print(context: Context, webView: WebView, name: String) {
        val printManager: PrintManager = context.getSystemService(PrintManager::class.java)
        val attributes = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
        printManager.print(name, webView.createPrintDocumentAdapter(name), attributes)
    }
}
