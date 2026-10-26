package de.velwebsolutions.oasis.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.velwebsolutions.oasis.design.components.OasisLogo
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType

/**
 * Oasis is members-only (the web's EnsurePaidAccess): without a paid or gifted membership the app shows "Mitglied
 * werden" (a Google Play subscription), the membership overview with voucher redemption, the settings and sign-out.
 * Back in the foreground it asks the server again, so a membership bought on the website opens the app at once.
 */
@Composable
fun PaywallScreen(viewModel: RootViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshMe() }
    StandaloneStack(MembershipRoute.Checkout, ScreenChrome(rootHeader = { actions -> PaywallHeader(viewModel, actions) }))
}

/** The logo and the "Mehr" menu with the pages a member without membership may open. */
@Composable
private fun PaywallHeader(viewModel: RootViewModel, actions: @Composable RowScope.() -> Unit) {
    val navigator = LocalNavigator.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OasisLogo()
        Spacer(Modifier.weight(1f))
        actions()
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("paywall.menu")) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Mehr", tint = OasisColors.Night)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = OasisColors.Paper) {
            DropdownMenuItem(
                text = { Text("Mitgliedschaft & Gutschein", style = OasisType.Body) },
                leadingIcon = { Icon(Icons.Outlined.CardGiftcard, contentDescription = null) },
                onClick = {
                    open = false
                    navigator.open(MembershipRoute.Overview)
                }
            )
            DropdownMenuItem(
                text = { Text("Einstellungen", style = OasisType.Body) },
                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                onClick = {
                    open = false
                    navigator.open(AccountRoute.Settings)
                }
            )
            DropdownMenuItem(
                text = { Text("Abmelden", style = OasisType.Body) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                onClick = {
                    open = false
                    viewModel.signOut()
                }
            )
        }
    }
}
