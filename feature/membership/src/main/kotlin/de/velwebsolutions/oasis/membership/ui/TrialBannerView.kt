package de.velwebsolutions.oasis.membership.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.membership.api.MembershipFormat

/**
 * The trial countdown of the web's app layout ("Noch 20 Tage in deinem Test. Der Test endet von selbst."); nothing
 * when [daysLeft] is null.
 */
@Composable
fun TrialBannerView(daysLeft: Int?) {
    if (daysLeft == null) return
    Text(
        "Noch ${MembershipFormat.days(daysLeft)} in deinem Test. Der Test endet von selbst.",
        style = OasisType.Small,
        color = OasisColors.OnNight,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(OasisColors.Night)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("membership.trialBanner")
    )
}
