package de.velwebsolutions.oasis.membership

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.membership.api.MembershipFormat
import de.velwebsolutions.oasis.membership.ui.CancelScreen
import de.velwebsolutions.oasis.membership.ui.InvoiceScreen
import de.velwebsolutions.oasis.membership.ui.OverviewScreen
import de.velwebsolutions.oasis.membership.ui.PlayCheckoutScreen
import de.velwebsolutions.oasis.membership.ui.PortalScreen
import de.velwebsolutions.oasis.membership.ui.TrialBannerView
import de.velwebsolutions.oasis.membership.ui.WithdrawScreen

/** Entry point of the Membership module: the screen for each [MembershipRoute] (mapped in the app). */
@Composable
fun MembershipScreen(route: MembershipRoute) {
    when (route) {
        MembershipRoute.Overview -> OverviewScreen()

        MembershipRoute.Portal -> PortalScreen()

        is MembershipRoute.Invoice -> InvoiceScreen(route.paymentId)

        MembershipRoute.Cancel -> CancelScreen()

        MembershipRoute.Withdraw -> WithdrawScreen()

        // MembershipRoute.Checkout, the last route (an `else` instead of a check that could never fail).
        else -> PlayCheckoutScreen()
    }
}

/** The trial countdown under the bar of every screen in a tab; gone for members and once the trial is over. */
@Composable
fun TrialBanner(me: Me) {
    val daysLeft = remember(me.trialEndsAt, me.membershipTier) { MembershipFormat.trialBannerDaysLeft(me.trialEndsAt, me.membershipTier) }
    TrialBannerView(daysLeft)
}
