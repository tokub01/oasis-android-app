package de.velwebsolutions.oasis.network.ui.oases

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.api.MapRegion
import de.velwebsolutions.oasis.network.api.OasisSummary
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.attempt
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.membersLabel
import de.velwebsolutions.oasis.network.ui.retry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** „Karte der Oasen“ (/app/oasen/karte?region=BY): the Bundesländer with their oases, and the oases of the chosen one. */
@HiltViewModel(assistedFactory = OasisMapViewModel.Factory::class)
class OasisMapViewModel @AssistedInject constructor(@Assisted region: String?, private val repository: NetworkRepository) :
    NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(region: String?): OasisMapViewModel
    }

    private val mutableRegions = MutableStateFlow<Loadable<List<MapRegion>>>(Loadable.Idle)

    /** All 16 Bundesländer in tile order. */
    val regions: StateFlow<Loadable<List<MapRegion>>> = mutableRegions.asStateFlow()
    private val mutableChosen = MutableStateFlow(region)

    /** The chosen Bundesland's code. */
    val chosen: StateFlow<String?> = mutableChosen.asStateFlow()
    private val mutableOases = MutableStateFlow<Loadable<List<OasisSummary>>>(Loadable.Idle)

    /** The oases of the chosen Bundesland. */
    val oases: StateFlow<Loadable<List<OasisSummary>>> = mutableOases.asStateFlow()

    suspend fun load() {
        mutableRegions.load { repository.regions() } ?: return
        chosen.value?.let { choose(it) }
    }

    suspend fun loadIfNeeded() {
        if (regions.value is Loadable.Idle) load()
    }

    /** A tile: lists that Bundesland's oases. */
    suspend fun choose(code: String) {
        mutableChosen.value = code
        mutableOases.value = Loadable.Loading
        val result = attempt { repository.regionOases(code) }
        mutableOases.value = result.apiError?.let { Loadable.Failed(it) } ?: Loadable.Loaded(result.getOrThrow())
    }
}

/** The name of Bundesland [code] among [regions]; the code itself when it is unknown. */
fun regionName(regions: List<MapRegion>, code: String): String = regions.firstOrNull { it.code == code }?.name ?: code

/** „Karte der Oasen“ (/app/oasen/karte). */
@Composable
fun OasisMapScreen(
    region: String?,
    viewModel: OasisMapViewModel = hiltViewModel<OasisMapViewModel, OasisMapViewModel.Factory>(
        key = "map-$region",
        creationCallback = { it.create(region) }
    )
) {
    val regions by viewModel.regions.collectAsStateWithLifecycle()
    val chosen by viewModel.chosen.collectAsStateWithLifecycle()
    val oases by viewModel.oases.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    OasisScreen(title = "Karte", error = error?.userMessage, onDismissError = viewModel::dismissError) {
        LoadableView(regions, onRetry = viewModel.retry(viewModel::load)) { tiles ->
            NetworkPage(onRefresh = viewModel::load) {
                block("header") {
                    PageHeader(
                        "Karte der Crowds",
                        eyebrow = "Crowds",
                        lead = "Wo Crowds zu Hause sind – so, wie ihre Gastgeber:innen es angeben. Eine selbst gezeichnete Übersicht nach " +
                            "Bundesländern, ohne Kartendienst und ohne genaue Orte."
                    )
                }
                block("map") {
                    Panel(spacing = 8) {
                        RegionTiles(tiles, chosen) { code -> viewModel.launch { viewModel.choose(code) } }
                        Text(
                            "Schematisch, nicht maßstabsgetreu. Tippe auf ein Bundesland.",
                            style = OasisType.Small,
                            color = OasisColors.Muted,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
                block("list") { RegionOases(chosen?.let { regionName(tiles, it) }, oases) }
                block("hint") {
                    SideCard("Deine Crowd auf der Karte", tone = PanelTone.Dune) {
                        Text(
                            "Gastgeber:innen tragen unter „Bearbeiten“ ein Bundesland und, wenn sie mögen, einen Ort ein. Mehr nicht: " +
                                "keine Adressen, keine Koordinaten.",
                            style = OasisType.Body,
                            color = OasisColors.Ink2
                        )
                    }
                }
            }
        }
    }
}

/** The 5 × 5 grid of tiles: the chosen one in night, one with oases in spring, one without pale. */
@Composable
internal fun RegionTiles(regions: List<MapRegion>, chosen: String?, onChoose: (String) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Bundesländer mit der Zahl ihrer Crowds" }
            .testTag("map.tiles"),
        contentAlignment = Alignment.TopCenter
    ) {
        val gap = 6.dp
        val size: Dp = (min(maxWidth, 520.dp) - gap * 4) / 5
        Box(Modifier.widthIn(max = size * 5 + gap * 4).fillMaxWidth()) {
            regions.forEach { region ->
                Tile(
                    region,
                    region.code == chosen,
                    size,
                    Modifier.offset(x = (size + gap) * region.tile.x, y = (size + gap) * region.tile.y)
                ) {
                    onChoose(region.code)
                }
            }
        }
    }
}

@Composable
private fun Tile(region: MapRegion, active: Boolean, size: Dp, modifier: Modifier, onClick: () -> Unit) {
    val filled = region.oasesCount > 0
    val fill = when {
        active -> OasisColors.Night
        filled -> OasisColors.Spring
        else -> OasisColors.Dune
    }
    val text = when {
        active -> OasisColors.OnNight
        filled -> OasisColors.SunInk
        else -> OasisColors.Ink2
    }
    val shape = RoundedCornerShape(size * 0.19f)
    Column(
        modifier
            .size(size)
            .background(fill, shape)
            .border(2.dp, if (filled || active) Color.Transparent else OasisColors.DuneLine, shape)
            .clickable(onClick = onClick)
            .testTag("map.${region.code}")
            .clearAndSetSemantics {
                contentDescription = region.accessibleName
                role = Role.Button
                selected = active
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(region.code, style = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = OasisType.Body.fontSize), color = text)
        Text("${region.oasesCount}", style = OasisType.BodyBold, color = text)
    }
}

/** The oases of the chosen Bundesland, or the hint to choose one. */
@Composable
private fun RegionOases(name: String?, oases: Loadable<List<OasisSummary>>) {
    val navigator = LocalNavigator.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (name == null) {
            Text("Wähle ein Bundesland, um seine Crowds zu sehen.", style = OasisType.Body, color = OasisColors.Ink2)
            return@Column
        }
        SectionTitle("Crowds in $name")
        when (oases) {
            is Loadable.Failed -> Text(oases.error.userMessage, style = OasisType.Body, color = OasisColors.SunText)

            is Loadable.Loaded -> {
                if (oases.value.isEmpty()) DashedNote("In $name hat noch keine Crowd ihr Zuhause angegeben.")
                oases.value.forEach { oasis ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(OasisColors.Paper, RoundedCornerShape(OasisRadius.S.dp))
                            .clickable(role = Role.Button) { navigator.open(NetworkRoute.Oasis(oasis.slug)) }
                            .padding(16.dp)
                            .testTag("map.oasis.${oasis.slug}"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(oasis.name) }
                                oasis.city?.takeIf { it.isNotBlank() }?.let { city ->
                                    withStyle(SpanStyle(color = OasisColors.Muted)) { append(" · $city") }
                                }
                            },
                            style = OasisType.Body,
                            color = OasisColors.Night
                        )
                        Text(oasis.description, style = OasisType.Small, color = OasisColors.Ink2, maxLines = 2)
                        Text(membersLabel(oasis.membersCount ?: 0), style = OasisType.Small, color = OasisColors.Muted)
                    }
                }
            }

            else -> CircularProgressIndicator(color = OasisColors.Night, modifier = Modifier.padding(8.dp))
        }
    }
}
