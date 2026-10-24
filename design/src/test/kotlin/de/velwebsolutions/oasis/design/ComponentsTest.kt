package de.velwebsolutions.oasis.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.AuthorizedImage
import de.velwebsolutions.oasis.design.components.AvatarTint
import de.velwebsolutions.oasis.design.components.AvatarView
import de.velwebsolutions.oasis.design.components.Banner
import de.velwebsolutions.oasis.design.components.BannerKind
import de.velwebsolutions.oasis.design.components.Bullets
import de.velwebsolutions.oasis.design.components.CountBadge
import de.velwebsolutions.oasis.design.components.DashedNote
import de.velwebsolutions.oasis.design.components.EmptyStateView
import de.velwebsolutions.oasis.design.components.ErrorStateView
import de.velwebsolutions.oasis.design.components.Eyebrow
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.LoadingView
import de.velwebsolutions.oasis.design.components.LocalScreenChrome
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCard
import de.velwebsolutions.oasis.design.components.OasisLogo
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.RelativeTimeText
import de.velwebsolutions.oasis.design.components.ScreenChrome
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SettingToggle
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.SkeletonLayout
import de.velwebsolutions.oasis.design.components.SkeletonView
import de.velwebsolutions.oasis.design.components.SupportContacts
import de.velwebsolutions.oasis.design.components.oasisScreenBackground
import de.velwebsolutions.oasis.design.components.openWebPage
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisMotion
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.design.theme.OasisType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        OasisTheme { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
    }

    @Test
    fun `buttons in every kind run their action`() {
        val taps = mutableListOf<String>()
        show {
            OasisButtonKind.entries.forEach { kind -> OasisButton(kind.name, onClick = { taps += kind.name }, kind = kind) }
            OasisButton("Schmal", onClick = {}, fullWidth = false, leading = { Text("+") })
            OasisButton("Aus", onClick = { taps += "Aus" }, enabled = false)
            OasisButton("Lädt", onClick = { taps += "Lädt" }, loading = true)
            OasisTextButton("Fertig", onClick = { taps += "Fertig" })
            OasisTextButton("Gesperrt", onClick = { taps += "Gesperrt" }, enabled = false)
            AsyncOasisButton("Später", onClick = { taps += "Später" })
            Text("Karte", Modifier.pressable(onClick = { taps += "Karte" }))
        }
        OasisButtonKind.entries.forEach { compose.onNodeWithText(it.name).performClick() }
        listOf("Aus", "Lädt", "Gesperrt", "Fertig", "Später", "Karte").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Später").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Primary", "Accent", "Secondary", "Destructive", "Fertig", "Später", "Karte", "Später"), taps)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/design-buttons.png")
    }

    @Test
    fun `surfaces, pills and layout`() {
        show {
            OasisLogo()
            OasisLogo(wordmark = false)
            PageHeader("Mitgliedschaft", eyebrow = "Konto", lead = "Verwalte deinen Beitrag.")
            PageHeader("Ohne alles")
            PillTone.entries.forEach { Pill(it.name, tone = it) }
            Pill("Mit Symbol", icon = Icons.Outlined.Star)
            PanelTone.entries.forEach { tone -> Panel(tone = tone) { Text("Panel $tone") } }
            SideCard("Seite") { Text("Text") }
            SideCard("Nacht", tone = PanelTone.Night) { Text("Text") }
            SectionCard("Abschnitt", detail = "Erklärung", count = 3) { Text("Inhalt") }
            SectionCard("Nacht", tone = PanelTone.Night, detail = "Erklärung") { Text("Inhalt") }
            DashedNote("Noch keine Tropfen.")
            DashedNote("Du bist durch.", highlighted = true)
            Bullets(listOf("Eins", "Zwei"))
            OasisCard { Text("Karte") }
            Eyebrow("Quelle")
            Eyebrow("Nacht", onNight = true)
            CountBadge(0)
            CountBadge(5)
            CountBadge(120)
            SupportContacts()
            Banner("Fehler")
            Banner("Info", kind = BannerKind.Info)
            Banner("Geschafft", kind = BannerKind.Success)
            EmptyStateView("Leer", message = "Nichts da.") { Text("Aktion") }
            EmptyStateView("Ohne Text")
        }
        compose.onNodeWithText("99+").assertExists()
        compose.onNodeWithContentDescription("5 neu").assertExists()
        compose.onNodeWithText("QUELLE").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/design-surfaces.png")
    }

    @Test
    fun `forms keep their values and show errors and hints`() {
        var text by mutableStateOf("")
        var on by mutableStateOf(false)
        show {
            OasisTextField("Name", text, { text = it }, prompt = "Dein Name", hint = "So sehen dich andere.")
            OasisTextField("Passwort", "geheim", {}, isSecure = true, error = "Zu kurz.")
            OasisTextField("Beschreibung", "", {}, lines = 2..4)
            FieldError("Pflichtfeld")
            SettingToggle("Hinweise", on, { on = it }, detail = "Per E-Mail")
            SettingToggle("Ohne Detail", false, {}, enabled = false)
        }
        compose.onNodeWithContentDescription("Name").performTextInput("Mira")
        compose.onNodeWithContentDescription("Hinweise").performClick()
        assertEquals("Mira", text)
        assertEquals(true, on)
        compose.onNodeWithText("Zu kurz.").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/design-forms.png")
    }

    @Test
    fun `loading states, avatars and time`() {
        var retries = 0
        show {
            LoadableView<String>(Loadable.Loaded("Inhalt"), onRetry = {}) { Text(it) }
            LoadableView<String>(Loadable.Failed(ApiError.NotFound), onRetry = { retries++ }) { Text(it) }
            Column(Modifier.fillMaxSize()) {
                LoadableView<String>(Loadable.Loading, onRetry = {}, skeleton = SkeletonLayout.Rows) { Text(it) }
            }
            LoadableView<String>(Loadable.Idle, onRetry = {}) { Text(it) }
            SkeletonView()
            LoadingView(label = "Oasis startet …")
            listOf("sun", "spring", "night", "dune").forEach { AvatarView("MS", it) }
            AvatarView("MS", "sun", photoUrl = "/bild.png", size = 64.dp)
            AuthorizedImage("https://example.org/bild.png", contentDescription = "Bild")
            RelativeTimeText(Instant.now().minusSeconds(120))
            ErrorStateView(ApiError.Transport("offline"), onRetry = { retries++ })
        }
        compose.onNodeWithText("Inhalt").assertExists()
        compose.onNodeWithText("vor 2 Min.").assertExists()
        compose.onNodeWithText("Das gibt es nicht (mehr) oder es ist nicht für dich sichtbar.").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/design-states.png")
        assertEquals(OasisColors.Dune, AvatarTint.surface("dune"))
        assertEquals(OasisColors.Sun, AvatarTint.surface("sun"))
        assertEquals(OasisColors.Night, AvatarTint.foreground("dune"))
        assertEquals(OasisColors.OnNight, AvatarTint.foreground("night"))
        assertEquals(OasisColors.SunInk, AvatarTint.foreground("sun"))
        OasisMotion.spec<Float>()
    }

    @Test
    fun `screens frame their content for each place in the app`() {
        var backs = 0
        var closes = 0
        var message by mutableStateOf<String?>("Gespeichert.")
        var error by mutableStateOf<String?>("Keine Verbindung.")
        compose.setContent {
            OasisTheme {
                Column {
                    CompositionLocalProvider(
                        LocalScreenChrome provides ScreenChrome(onBack = {
                            backs++
                        }, banner = { Text("Test läuft") })
                    ) {
                        OasisScreen("Gepusht", Modifier.weight(1f), message = message, error = error, onDismissMessage = {
                            message = null
                        }, onDismissError = {
                            error =
                                null
                        }) {
                            Text("Inhalt")
                        }
                    }
                    CompositionLocalProvider(LocalScreenChrome provides ScreenChrome(onClose = { closes++ })) {
                        OasisScreen("Sheet", Modifier.weight(1f)) { Text("Sheet-Inhalt") }
                    }
                    CompositionLocalProvider(LocalScreenChrome provides ScreenChrome(onClose = { closes++ })) {
                        OasisScreen("Schließt selbst", Modifier.weight(1f), closesItself = true, navigationAction = {
                            Text("Eigenes X")
                        }) { Text("C") }
                    }
                    CompositionLocalProvider(
                        LocalScreenChrome provides ScreenChrome(rootHeader = { actions ->
                            Row {
                                Text("Kopf")
                                actions()
                            }
                        })
                    ) {
                        OasisScreen("Wurzel", Modifier.weight(1f), actions = { Text("Aktion") }) { Text("D") }
                    }
                    OasisScreen(null, Modifier.weight(1f)) { Text("Ohne Leiste") }
                }
            }
        }
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onAllNodesWithContentDescription("Schließen")[0].assertExists()
        compose.onNodeWithText("Eigenes X").assertIsDisplayed()
        compose.onNodeWithText("Kopf").assertIsDisplayed()
        compose.onNodeWithText("Test läuft").assertIsDisplayed()
        compose.onNodeWithTag("status.error").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/design-screens.png")
        assertEquals(1, backs)
        compose.mainClock.advanceTimeBy(9_000)
        compose.waitForIdle()
        assertEquals(null, error)
        assertEquals(null, message)
    }

    @Test
    fun `web pages open in a custom tab, other links in their app`() {
        val app = RuntimeEnvironment.getApplication()
        openWebPage(app, "https://get-oasis.com/impressum")
        openWebPage(app, "tel:116111")
        val started = generateSequence { shadowOf(app).nextStartedActivity }.map { it.dataString }.toList()
        assertEquals(true, "tel:116111" in started)
    }

    @Test
    fun `the logo, a dashed note and big figures are drawn`() {
        compose.setContent {
            OasisTheme {
                Column {
                    OasisLogo(size = 48.dp)
                    DashedNote("Ende", highlighted = true)
                    Text("42", style = OasisType.figure())
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/design-logo.png")
        compose.onNodeWithContentDescription("Oasis").assertIsDisplayed()
    }

    @Test(expected = IllegalStateException::class)
    fun `screens need the app's navigator`() {
        compose.setContent { LocalNavigator.current }
    }

    @Test
    fun `images without an API client use the address as given, and pressing shrinks`() {
        compose.setContent {
            OasisTheme {
                CompositionLocalProvider(
                    de.velwebsolutions.oasis.design.components.LocalApiClient provides
                        de.velwebsolutions.oasis.testing.StubTransport().client
                ) {
                    Column(Modifier.oasisScreenBackground()) {
                        AuthorizedImage("/a.png", contentDescription = "A")
                        AuthorizedImage("ftp:x", contentDescription = "B")
                        OasisButton("Drück mich", onClick = {})
                    }
                }
            }
        }
        compose.onNodeWithText("Drück mich").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithText("Drück mich").performTouchInput { up() }
    }

    @Test
    fun `a running async button ignores more taps`() {
        var taps = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        show {
            AsyncOasisButton("Langsam", onClick = {
                taps++
                gate.await()
            })
        }
        compose.onNodeWithText("Langsam").performClick()
        compose.onNodeWithText("Langsam").performClick()
        gate.complete(Unit)
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun `plain http pages also open in a custom tab`() {
        openWebPage(RuntimeEnvironment.getApplication(), "http://localhost/hilfe")
    }

    @Test
    fun `bars without a title and sheets that close themselves`() {
        compose.setContent {
            OasisTheme {
                Column {
                    CompositionLocalProvider(LocalScreenChrome provides ScreenChrome(onBack = {})) {
                        OasisScreen(null, Modifier.weight(1f)) { Text("1") }
                    }
                    CompositionLocalProvider(LocalScreenChrome provides ScreenChrome(onClose = {})) {
                        OasisScreen(null, Modifier.weight(1f), closesItself = true) { Text("2") }
                    }
                    OasisScreen(null, Modifier.weight(1f), navigationAction = { Text("X") }) { Text("3") }
                }
            }
        }
        compose.onNodeWithText("X").assertExists()
    }
}
