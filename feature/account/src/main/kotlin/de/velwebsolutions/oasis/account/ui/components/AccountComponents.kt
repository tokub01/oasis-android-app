package de.velwebsolutions.oasis.account.ui.components

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.account.catching
import de.velwebsolutions.oasis.core.format.GermanTime
import de.velwebsolutions.oasis.core.network.asApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisLogo
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.launch

/** The long German date formats of the account pages (the short ones are `GermanTime.day` and `.dayAndTime`). */
object GermanDate {
    /** „22. Oktober 2026, 14:05 Uhr“. */
    fun long(instant: Instant): String = GermanTime.format(instant, "d. MMMM yyyy, HH:mm") + " Uhr"

    /** „22. Oktober 2026“. */
    fun dayLong(instant: Instant): String = GermanTime.format(instant, "d. MMMM yyyy")
}

/** A page of the website (`/agb`, `/hilfe` …) on the server the app talks to. */
@Composable
fun webUrl(path: String): String = LocalApiClient.current?.absoluteUrl("/$path")?.toString() ?: "https://get-oasis.com/$path"

/** The scrolling page of an account screen: 20 padding, at most 720 wide. */
@Composable
fun AccountPage(maxWidth: Int = 720, spacing: Int = 20, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier.widthIn(max = maxWidth.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
            content = content
        )
    }
}

/** A card like `x-oasis.auth-card`: logo, title and subtitle above the form. */
@Composable
fun AuthCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(OasisRadius.L.dp)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .background(OasisColors.Paper, shape)
                .border(1.dp, OasisColors.PaperLine, shape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OasisLogo(Modifier.padding(top = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = OasisType.display(34), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
                Text(subtitle, style = OasisType.Body, color = OasisColors.Ink2)
            }
            content()
        }
    }
}

/** An underlined link in Nachtwasser (NavigationLink texts on iOS), at least 44 high. */
@Composable
fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, bold: Boolean = true) {
    Box(
        modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            style = (if (bold) OasisType.SmallBold else OasisType.Small).copy(textDecoration = TextDecoration.Underline),
            color = OasisColors.Night
        )
    }
}

/**
 * Radio choices as pills, one per line (the web's labelled radios): privacy audiences, report categories, the
 * welcome tour's choices and vote options.
 */
@Composable
fun <T> FlowChoices(options: List<Pair<T, String>>, selected: T?, tag: String, enabled: Boolean = true, choose: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val isSelected = id == selected
            val shape = RoundedCornerShape(OasisRadius.TILE.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(if (isSelected) OasisColors.Night else OasisColors.Field, shape)
                    .border(1.dp, if (isSelected) OasisColors.Night else OasisColors.DuneLine, shape)
                    .clickable(enabled = enabled, role = Role.RadioButton) { choose(id) }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("$tag.$id"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isSelected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) OasisColors.Sun else OasisColors.FieldLine
                )
                Text(
                    label,
                    style = OasisType.Small,
                    color = if (isSelected) OasisColors.OnNight else OasisColors.Ink,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The choices of a privacy setting, a report category or a step of the welcome tour. */
@Composable
fun FlowChoices(
    choices: List<de.velwebsolutions.oasis.account.api.PrivacyChoice>,
    selected: String?,
    tag: String,
    enabled: Boolean = true,
    choose: (String) -> Unit
) = FlowChoices(choices.map { it.value to it.label }, selected, tag, enabled, choose)

/** A row with text on the left and a small action on the right, separated by a line (device and safety lists). */
@Composable
fun ListRow(leading: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = OasisColors.DuneLine)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { leading() }
            trailing()
        }
    }
}

/** Muted text for empty lists. */
@Composable
fun EmptyLine(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = OasisColors.DuneLine)
        Text(text, style = OasisType.Small, color = OasisColors.Muted)
    }
}

/** A QR code for the authenticator app (the web shows Fortify's SVG; the content is the same otpauth URL). */
@Composable
fun QrCodeView(content: String, modifier: Modifier = Modifier) {
    val code = remember(content) { QrCode.encode(content) } ?: return
    Box(
        modifier
            .background(Color.White, RoundedCornerShape(OasisRadius.XS.dp))
            .padding(16.dp)
            .semantics { contentDescription = "QR-Code für die Authenticator-App" }
            .testTag("settings.qrCode")
    ) {
        Canvas(Modifier.size(192.dp)) {
            // Two light modules of quiet zone inside the white card.
            val cell = size.minDimension / (code.size + 4)
            for (y in 0 until code.size) {
                for (x in 0 until code.size) {
                    if (code[x, y]) drawRect(Color.Black, Offset((x + 2) * cell, (y + 2) * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** „Sind Sie sicher?“ as a dialog: the question, the destructive action and „Abbrechen“. */
@Composable
fun ConfirmDialog(question: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(question, style = OasisType.Body, color = OasisColors.Ink) },
        confirmButton = {
            OasisTextButton(confirm, {
                onDismiss()
                onConfirm()
            }, color = OasisColors.SunText)
        },
        dismissButton = { OasisTextButton("Abbrechen", onDismiss) },
        containerColor = OasisColors.Paper
    )
}

/** Writes [bytes] to the file the member picked; false when the storage refuses it. */
internal fun writeFile(resolver: ContentResolver, uri: Uri, bytes: ByteArray): Boolean {
    val stream = runCatching { resolver.openOutputStream(uri) }.getOrNull() ?: return false
    return try {
        stream.write(bytes)
        true
    } catch (_: IOException) {
        false
    } finally {
        stream.close()
    }
}

/**
 * „Daten herunterladen“ (the web's direct download of `oasis-daten.zip`): fetches the ZIP, then lets the member save it
 * where they like (the system's file picker). Used in Einstellungen and on the lock screen.
 */
@Composable
fun DataExportButton(download: suspend () -> ByteArray, failed: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ByteArray?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        // Nothing to save when the picker was cancelled, or when it answers a recreated screen that lost the file.
        val bytes = file
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (!writeFile(context.contentResolver, uri, bytes)) failed("Die Datei konnte nicht gespeichert werden.")
        }
    }
    if (file != null) {
        OasisButton("ZIP-Datei sichern", {
            save.launch("oasis-daten.zip")
        }, fullWidth = false, modifier = Modifier.testTag("settings.exportShare"))
    } else {
        AsyncOasisButton(
            "Daten herunterladen",
            {
                catching { download() }
                    .onSuccess { file = it }
                    .onFailure { error -> failed(error.asApiError().userMessage) }
            },
            kind = OasisButtonKind.Secondary,
            fullWidth = false,
            modifier = Modifier.testTag("settings.export")
        )
    }
}

/** A small heading inside a page (`Font.Oasis.heading(19)`). */
@Composable
fun SubHeading(text: String, size: Int = 19) {
    Text(text, style = OasisType.heading(size), color = OasisColors.Night, modifier = Modifier.semantics { heading() })
}
