package de.velwebsolutions.oasis.network.ui.oases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velwebsolutions.oasis.design.components.AuthorizedImage
import de.velwebsolutions.oasis.design.components.AvatarTint
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.OasisDirectory
import de.velwebsolutions.oasis.network.api.OasisSummary
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.membersLabel

/** "Oasen" (/app/oasen): the member's oases and all others as cards; "Oase eröffnen" for members who may write. */
@Composable
fun OasesScreen(viewModel: OasesViewModel = hiltViewModel<OasesViewModel>()) {
    val directory by viewModel.directory.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(title = "Oasen", error = error?.userMessage, onDismissError = viewModel::dismissError) {
        LoadableView(directory, onRetry = viewModel::load) { list ->
            NetworkPage(onRefresh = viewModel::load) { oasesContent(list) }
        }
    }
}

/** The overview's page. */
fun LazyListScope.oasesContent(list: OasisDirectory) {
    block("header") {
        val navigator = LocalNavigator.current
        Column(
            Modifier.fillMaxWidth().background(OasisColors.Night, RoundedCornerShape(OasisRadius.L.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Eyebrow("Oasen", onNight = true)
            Text(
                "Räume für das, was dich interessiert",
                style = OasisType.display(26),
                color = OasisColors.OnNight,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "Themenräume mit Gastgeber:innen und eigenen Hausregeln. Einer Oase folgst du wie einem Menschen – dann kannst du dort auch schreiben.",
                style = OasisType.Body,
                color = OasisColors.OnNight2
            )
            if (list.canCreate) {
                OasisButton(
                    "Oase eröffnen",
                    { navigator.open(NetworkRoute.CreateOasis) },
                    kind = OasisButtonKind.Accent,
                    fullWidth = false,
                    modifier = Modifier.testTag("oases.create")
                )
            }
        }
    }
    section("mine", "Deine Oasen", list.mine, "Du folgst noch keiner Oase.")
    section("others", "Weitere Oasen", list.others, "Noch keine weiteren Oasen.")
}

private fun LazyListScope.section(key: String, title: String, oases: List<OasisSummary>, empty: String) {
    block("$key.title") { SectionTitle(title) }
    if (oases.isEmpty()) block("$key.empty") { DashedNote(empty) }
    oases.forEach { oasis -> block("$key.${oasis.id}") { OasisSummaryCard(oasis) } }
}

/** An oasis card of the overview: banner strip or colour surface, name, description and members. */
@Composable
fun OasisSummaryCard(oasis: OasisSummary) {
    val navigator = LocalNavigator.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OasisRadius.M.dp))
            .background(OasisColors.Paper)
            .pressable({ navigator.open(NetworkRoute.Oasis(oasis.slug)) })
            .testTag("oasis.card.${oasis.slug}")
    ) {
        OasisBanner(oasis, height = 80.dp)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(oasis.name, style = OasisType.CardTitle, color = OasisColors.Night)
            Text(oasis.description, style = OasisType.Small, color = OasisColors.Ink2, maxLines = 3)
            Text(
                oasis.membersCount?.let(::membersLabel).orEmpty() + if (oasis.role == "host") " · du bist Gastgeber:in" else "",
                style = OasisType.Caption,
                color = OasisColors.Muted
            )
        }
    }
}

/** The banner of an oasis, or its colour surface with the web's soft circle. */
@Composable
fun OasisBanner(oasis: OasisSummary, modifier: Modifier = Modifier, height: Dp = 128.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .background(AvatarTint.surface(oasis.color))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.BottomEnd
    ) {
        val banner = oasis.bannerUrl
        if (banner != null) {
            AuthorizedImage(
                banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(height)
            )
        } else {
            Box(
                Modifier
                    .offset(x = height * 0.3f, y = height * 0.5f)
                    .requiredSize(height * 1.4f)
                    .background(OasisColors.Paper.copy(alpha = 0.25f), CircleShape)
            )
        }
    }
}
