package de.velwebsolutions.oasis.network.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.LocalApiClient
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.Choice
import kotlinx.coroutines.launch

/** The readable width of a page, like the web's column. */
private val PageWidth = 720.dp

/**
 * A scrolling page in the web's layout: sand background, 16 dp gutters, readable width, 20 dp between blocks and pull
 * to refresh when [onRefresh] is set. Blocks are added with [block]; feeds with `feedSection`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    onRefresh: (suspend () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val list = @Composable {
        LazyColumn(
            modifier = modifier.fillMaxSize().background(OasisColors.Sand).testTag("network.page"),
            state = state,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
    if (onRefresh == null) {
        list()
    } else {
        val scope = rememberCoroutineScope()
        var refreshing by remember { mutableStateOf(false) }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    try {
                        onRefresh()
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { list() }
    }
}

/** One block of a [NetworkPage] at the page's readable width. */
fun LazyListScope.block(key: Any? = null, contentType: Any? = null, content: @Composable ColumnScope.() -> Unit) {
    item(key = key, contentType = contentType) {
        Column(Modifier.widthIn(max = PageWidth).fillMaxWidth(), content = content)
    }
}

/** A section heading inside a page ("Tropfen", "Antworten"): Manrope 800 at 21 like the web's `h2`. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, onNight: Boolean = false) {
    Text(
        text,
        style = OasisType.figure(21),
        color = if (onNight) OasisColors.OnNight else OasisColors.Night,
        modifier = modifier.semantics { heading() }
    )
}

/** An underlined link at the end of a side card ("Alle Oasen", "Zur Suche"), like the web's `o-link`. */
@Composable
fun TextLink(title: String, route: AppRoute, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    Text(
        title,
        style = OasisType.SmallBold.copy(textDecoration = TextDecoration.Underline),
        color = OasisColors.SpringText,
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(role = Role.Button) { navigator.open(route) }
            .padding(vertical = 12.dp)
    )
}

/** An underlined inline link in body text that opens a route. */
@Composable
fun UnderlinedLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = OasisType.Body,
    color: Color = OasisColors.Night
) {
    Text(
        text,
        style = style.copy(textDecoration = TextDecoration.Underline),
        color = color,
        modifier = modifier.defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp)
    )
}

/** The look of [OnNightButton]: a light pill with night text, or a light outline. */
enum class OnNightKind { Filled, Outline }

/**
 * Buttons on night surfaces (host card, the end of Durchblättern, "Nur für dich"): a light pill with night text, or a
 * light outline – the web's light buttons on `bg-night`.
 */
@Composable
fun OnNightButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: OnNightKind = OnNightKind.Filled) {
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .background(if (kind == OnNightKind.Filled) OasisColors.OnNight else Color.Transparent, CircleShape)
            .then(if (kind == OnNightKind.Outline) Modifier.border(BorderStroke(1.5.dp, OasisColors.OnNight2), CircleShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = OasisType.Button, color = if (kind == OnNightKind.Filled) OasisColors.Night else OasisColors.OnNight)
    }
}

/** A switch with its label; the whole row toggles (iOS `Toggle`). */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = OasisType.Small,
    tint: Color = OasisColors.Spring,
    enabled: Boolean = true
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = style, color = OasisColors.Ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = tint,
                checkedThumbColor = OasisColors.Paper,
                uncheckedTrackColor = OasisColors.Dune,
                uncheckedBorderColor = OasisColors.DuneLine,
                uncheckedThumbColor = OasisColors.FieldLine
            )
        )
    }
}

/** A menu picker (iOS `Picker` with `.menu` style): the chosen label with a chevron, the choices in a dropdown. */
@Composable
fun ChoiceMenu(label: String, choices: List<Choice>, selection: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 44.dp)
                .clip(RoundedCornerShape(OasisRadius.CHIP.dp))
                .clickable(role = Role.DropdownList, onClickLabel = label) { open = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(choices.firstOrNull { it.value == selection }?.label ?: selection, style = OasisType.Body, color = OasisColors.Night)
            Icon(Icons.Outlined.UnfoldMore, contentDescription = null, tint = OasisColors.Night, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = OasisColors.Paper) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label, style = OasisType.Body, color = OasisColors.Ink) },
                    onClick = {
                        open = false
                        onSelect(choice.value)
                    }
                )
            }
        }
    }
}

/** An inline list of radio choices (iOS `Picker` with `.inline` style). */
@Composable
fun ChoiceList(choices: List<Choice>, selection: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        choices.forEach { choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = choice.value == selection, role = Role.RadioButton) { onSelect(choice.value) }
                    .defaultMinSize(minHeight = 44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = choice.value == selection,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = OasisColors.Night, unselectedColor = OasisColors.FieldLine)
                )
                Spacer(Modifier.width(8.dp))
                Text(choice.label, style = OasisType.Body, color = OasisColors.Ink)
            }
        }
    }
}

/** A box that opens and closes (iOS `DisclosureGroup`): "Frühere Fassungen", "Hausregeln dieser Oase". */
@Composable
fun Disclosure(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    border: Color = OasisColors.PaperLine,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(OasisRadius.S.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clickable(role = Role.Button, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Zuklappen" else "Aufklappen",
                tint = OasisColors.Night
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** A destructive question before an action (iOS `confirmationDialog`), with "Abbrechen". */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    destructive: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OasisColors.Paper,
        title = { Text(title, style = OasisType.heading(), color = OasisColors.Night) },
        text = message?.let { { Text(it, style = OasisType.Body, color = OasisColors.Ink2) } },
        confirmButton = {
            Text(
                confirmLabel,
                style = OasisType.BodyBold,
                color = if (destructive) OasisColors.SunText else OasisColors.Night,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button) {
                        onDismiss()
                        onConfirm()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("dialog.confirm")
            )
        },
        dismissButton = {
            Text(
                "Abbrechen",
                style = OasisType.BodyBold,
                color = OasisColors.Night,
                modifier = Modifier.clip(
                    CircleShape
                ).clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    )
}

/** A paper card with the web's large radius (`rounded-[24px]`), optionally outlined. */
@Composable
fun PaperBox(
    modifier: Modifier = Modifier,
    padding: Int = 20,
    radius: Int = OasisRadius.M,
    border: Color? = null,
    spacing: Int = 12,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
        content = content
    )
}

/** A row of buttons that wraps on narrow screens (iOS `FlowLayout`). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ButtonFlow(modifier: Modifier = Modifier, spacing: Int = 8, content: @Composable RowScope.() -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
        content = { content() }
    )
}

/** Dims content while it is disabled. */
fun Modifier.dimmed(disabled: Boolean): Modifier = alpha(if (disabled) 0.45f else 1f)

/** Shares a web address with the system sheet (iOS `ShareLink`). */
fun share(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Copies text (invitation links). */
fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The server root of the running app (for mentions, topics and the tone rules link). */
@Composable
fun rememberBaseUrl(): okhttp3.HttpUrl? = LocalApiClient.current?.baseUrl
