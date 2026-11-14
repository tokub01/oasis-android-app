package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.Appearance
import de.velwebsolutions.oasis.design.theme.AppearanceChoice
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.ui.oases.OasesScreen
import de.velwebsolutions.oasis.network.ui.oases.OasesViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisDetailScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisMapScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisMapViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Karte der Crowds, the region on the crowd page and in its form, at 360 dp (light and dark). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class OasisMapUiTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()
    private var screen by mutableStateOf<@Composable () -> Unit>({})

    @After
    fun clear() {
        models.clear()
        Appearance.show(AppearanceChoice.Automatic)
    }

    private fun show(content: @Composable () -> Unit) {
        screen = content
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { screen() } } }
    }

    private fun switchTo(content: @Composable () -> Unit) {
        screen = content
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/network-$name.png")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun opened(route: AppRoute) = MainTab.entries.any { route in navigator.stack(it) }

    @Test
    fun theMapListsTheOasesOfATile() {
        val network = StubbedNetwork(
            stub("GET", "network/regions", regionsJson()),
            stub("GET", "network/regions/BY/oases", """{"data":[${regionOasisJson("isar", "München")},${regionOasisJson("alm", null)}]}"""),
            stub("GET", "network/regions/HB/oases", """{"data":[]}"""),
            stub("GET", "network/regions/SN/oases", """{"message":"Server Error"}""", 500)
        )
        val model = models.keep(OasisMapViewModel(null, network.repository))
        show { OasisMapScreen(null, model) }
        waitFor("Wähle ein Bundesland")
        capture("oasis-map")
        compose.onNode(hasContentDescription("Bayern: 3 Crowds")).tap()
        waitFor("Crowds in Bayern")
        waitFor(" · München")
        capture("oasis-map-bayern")
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("map.oasis.isar"))
        compose.onNodeWithTag("map.oasis.isar").tap()
        assertTrue(opened(NetworkRoute.Oasis("isar")))
        compose.onNode(hasContentDescription("Bremen: 0 Crowds")).tap()
        waitFor("In Bremen hat noch keine Crowd ihr Zuhause angegeben.")
        compose.onNode(hasContentDescription("Sachsen: 2 Crowds")).tap()
        waitFor("Server Error")
        Appearance.show(AppearanceChoice.Dark)
        compose.onNode(hasContentDescription("Bayern: 3 Crowds")).tap()
        waitFor("Crowds in Bayern")
        capture("oasis-map-dark")
    }

    @Test
    fun theOasisShowsItsRegionAndTheFormChangesIt() {
        val oasis = """
            {"data":{"oasis":${regionOasisJson("isar", "München")},"url":"http://localhost/oasen/isar","house_rules":[],"hosts":[],
            "is_member":false,"is_host":false,"can_follow":true,"can_post":false}}
        """.trimIndent()
        val network = StubbedNetwork(
            stub("GET", "network/oases/isar", oasis),
            stub("GET", "network/oases/isar/posts", pageJson()),
            stub("GET", "network/oases/isar/collections", """{"data":{"can_create":false,"collections":[]}}"""),
            stub("GET", "network/oases/isar/edit", editFormJson()),
            stub("PUT", "network/oases/isar", """{"message":"x","errors":{"region":["Bitte wähle ein Bundesland aus der Liste."]}}""", 422),
            stub("GET", "network/oases", Fixtures.oases)
        )
        val page = models.keep(OasisViewModel("isar", network.repository))
        show { OasisDetailScreen("isar", page) }
        waitFor("Bayern · München")
        compose.onNodeWithTag("oasis.region").tap()
        assertTrue(opened(NetworkRoute.OasisMap("BY")))

        val form = models.keep(OasisFormViewModel("isar", network.repository))
        switchTo { OasisFormScreen("isar", form) }
        compose.waitUntil(5_000) { form.state.value.options != null }
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("oasisForm.regionFields"))
        capture("oasis-form-region")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("oasisForm.city"))).performTextInput("Nord ")
        compose.onNodeWithTag("oasisForm.region").performClick()
        compose.onNodeWithText("Bremen").performClick()
        compose.waitUntil(5_000) { form.state.value.input.region == "HB" }
        compose.onNodeWithTag("oasisForm.region").performClick()
        compose.onNodeWithText("Keine Region").performClick()
        compose.waitUntil(5_000) { form.state.value.input.region == null }
        compose.onNodeWithTag("oasisForm.city").assertDoesNotExist()
        compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag("oasisForm.save"))
        compose.onNodeWithTag("oasisForm.save").tap()
        waitFor("Bitte wähle ein Bundesland aus der Liste.")

        val overview = models.keep(OasesViewModel(network.repository))
        switchTo { OasesScreen(overview) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("oases.map")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("oases.map").tap()
        assertTrue(opened(NetworkRoute.OasisMap()))

        val reader = StubbedNetwork(stub("GET", "network/oases", Fixtures.oases.replace("\"can_create\": true", "\"can_create\": false")))
        val readOnly = models.keep(OasesViewModel(reader.repository))
        switchTo { OasesScreen(readOnly) }
        compose.waitUntil(5_000) { readOnly.directory.value.valueOrNull?.canCreate == false }
        compose.onNodeWithTag("oases.create").assertDoesNotExist()
        compose.onNodeWithTag("oases.map").assertExists()
    }
}
