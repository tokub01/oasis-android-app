package de.velwebsolutions.oasis.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType

/**
 * Where a screen is shown, set by the app shell: the root of a tab (the web header with logo, search, bell and "Mehr"
 * replaces the title bar, like the iOS tab roots), pushed on a stack (back arrow and title), or the first screen of a
 * sheet (close icon, like a Material full-screen dialog). Android patterns throughout: Material top app bar, system and
 * predictive back, snackbars for confirmations.
 */
@Immutable
data class ScreenChrome(
    /** The header of a tab root; receives the screen's own actions. Null for pushed screens and sheets. */
    val rootHeader: (@Composable (actions: @Composable RowScope.() -> Unit) -> Unit)? = null,
    /** Back one screen (pushed screens); null at a root. */
    val onBack: (() -> Unit)? = null,
    /** Closes the sheet; set on the first screen of a sheet. */
    val onClose: (() -> Unit)? = null,
    /** Shown below the bar of every screen in a tab (the trial countdown). */
    val banner: (@Composable () -> Unit)? = null
)

/** The chrome of the current screen. */
val LocalScreenChrome = staticCompositionLocalOf { ScreenChrome() }

/**
 * The frame of every screen: sand background, the bar the [ScreenChrome] asks for, the trial banner, and the status
 * toast for the screen's confirmation and error. [title] is the screen's name (shown in the bar of pushed screens and
 * read by TalkBack); [actions] are its toolbar buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OasisScreen(
    title: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    message: String? = null,
    error: String? = null,
    onDismissMessage: () -> Unit = {},
    onDismissError: () -> Unit = {},
    /** Replaces the close icon of a sheet when the screen closes itself (e.g. the composer asks before discarding). */
    closesItself: Boolean = false,
    /** The leading bar button of a screen that closes itself (the composer's close icon, which asks before discarding). */
    navigationAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val chrome = LocalScreenChrome.current
    Scaffold(
        modifier = modifier.testTag("screen"),
        containerColor = OasisColors.Sand,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                val header = chrome.rootHeader
                val bare = header == null && title == null && chrome.onBack == null && chrome.onClose == null && navigationAction == null
                if (bare) {
                    // A screen without title or way back (sign-in): no empty bar, only the status bar's room.
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                } else if (header != null) {
                    header(actions)
                } else {
                    TopAppBar(
                        title = {
                            if (title != null) Text(title, style = OasisType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            val onBack = chrome.onBack
                            val onClose = chrome.onClose
                            when {
                                onBack != null -> IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück", tint = OasisColors.Night)
                                }

                                onClose != null && !closesItself -> IconButton(onClick = onClose) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Schließen", tint = OasisColors.Night)
                                }

                                navigationAction != null -> navigationAction()
                            }
                        },
                        actions = actions,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = OasisColors.Sand,
                            scrolledContainerColor = OasisColors.Sand,
                            titleContentColor = OasisColors.Night,
                            actionIconContentColor = OasisColors.Night,
                            navigationIconContentColor = OasisColors.Night
                        ),
                        windowInsets = if (chrome.onClose != null) WindowInsets(0) else TopAppBarDefaults.windowInsets
                    )
                }
                chrome.banner?.invoke()
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            content()
            StatusToast(message, error, onDismissMessage, onDismissError)
        }
    }
}
