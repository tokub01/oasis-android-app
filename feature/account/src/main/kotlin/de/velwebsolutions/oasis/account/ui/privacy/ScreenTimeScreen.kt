package de.velwebsolutions.oasis.account.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.account.ui.components.AccountPage
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.auth.SessionState
import de.velwebsolutions.oasis.core.screentime.ScreenTime
import de.velwebsolutions.oasis.core.screentime.ScreenTimeWeek
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.SettingToggle
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * „Deine Zeit in Mildcrowd“: the signed-in account's last 7 days as counted on this device, and the switch that lets the
 * device count at all (off by default, kept only on the device).
 */
@HiltViewModel
class ScreenTimeViewModel @Inject constructor(private val screenTime: ScreenTime, private val session: Session) : ViewModel() {
    private val mutableWeek = MutableStateFlow<ScreenTimeWeek?>(null)
    val week: StateFlow<ScreenTimeWeek?> = mutableWeek.asStateFlow()

    private val mutableEnabled = MutableStateFlow(screenTime.enabled)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** The week as it stands, the seconds of this very visit included. */
    fun refresh() {
        screenTime.save()
        mutableEnabled.value = screenTime.enabled
        mutableWeek.value = (session.state.value as? SessionState.SignedIn)?.me?.id?.let(screenTime::week)
    }

    /** The switch: counting starts or stops at once, on this device only. */
    fun setEnabled(on: Boolean) {
        screenTime.enabled = on
        mutableEnabled.value = on
    }
}

/** „Deine Zeit in Mildcrowd“ (/app/privatsphaere/zeit, docs/mildcrowd-privacy.md „Bildschirmzeit“). */
@Composable
internal fun ScreenTimeScreen(viewModel: ScreenTimeViewModel = hiltViewModel()) {
    val week by viewModel.week.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    OasisScreen(title = "Deine Zeit") { ScreenTimeContent(week, enabled, viewModel::setEnabled) }
}

/** The page without view model (Robolectric tests). */
@Composable
internal fun ScreenTimeContent(week: ScreenTimeWeek?, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    AccountPage {
        PageHeader(
            "Deine Zeit in Mildcrowd",
            eyebrow = "Nur für dich",
            lead = "Wie viele Minuten du an den letzten sieben Tagen in Mildcrowd warst – gezählt nur, wenn du das hier einschaltest, " +
                "und nur, solange Mildcrowd vorne ist und du etwas tust."
        )
        Column(
            Modifier.fillMaxWidth().background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp)).padding(20.dp)
        ) {
            SettingToggle(
                "Zeit zählen",
                enabled,
                onEnabled,
                Modifier.testTag("screenTime.toggle"),
                detail = "Standard: aus. Der Schalter gilt nur für dieses Gerät und wird nirgendwohin übertragen."
            )
        }
        if (enabled) {
            week?.let { WeekCard(it) }
        } else {
            Text(
                "Das Zählen ist aus. Schalte es ein, wenn du sehen möchtest, wie viel Zeit du hier verbringst.",
                style = OasisType.Body,
                color = OasisColors.Ink2,
                modifier = Modifier.testTag("screenTime.off")
            )
        }
        Text(
            "Gezählt wird nur auf diesem Gerät, in dieser App, und nur, solange der Schalter an ist. Mildcrowd bekommt davon nichts. " +
                "Im Browser und auf anderen Geräten zählt jedes Gerät für sich.",
            style = OasisType.Body,
            color = OasisColors.Ink,
            modifier = Modifier
                .fillMaxWidth()
                .background(OasisColors.Dune, RoundedCornerShape(OasisRadius.S.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp)
        )
    }
}

/** „Diese Woche“ with „Zusammen …“ and one bar per day, scaled to the longest day. */
@Composable
private fun WeekCard(week: ScreenTimeWeek) {
    val longest = maxOf(1L, week.days.maxOf { it.minutes })
    Column(
        Modifier
            .fillMaxWidth()
            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.M.dp))
            .padding(20.dp)
            .testTag("screenTime.week"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Diese Woche", style = OasisType.heading(20), color = OasisColors.Night, modifier = Modifier.weight(1f))
            Text(
                "Zusammen ${ScreenTime.format(week.total)}",
                style = OasisType.BodyBold,
                color = OasisColors.Night,
                modifier = Modifier.testTag("screenTime.total")
            )
        }
        week.days.forEach { day ->
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${day.label}: ${ScreenTime.format(day.minutes)}" },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(day.label, style = OasisType.BodyBold, color = OasisColors.Night, modifier = Modifier.width(56.dp))
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(day.minutes.toFloat() / longest)
                            .height(12.dp)
                            .background(OasisColors.Spring, CircleShape)
                    )
                }
                Text(
                    ScreenTime.format(day.minutes),
                    style = OasisType.Body,
                    color = OasisColors.Ink2,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(104.dp)
                )
            }
        }
    }
}
