package de.velwebsolutions.oasis.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.AuthorizedImage
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
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisCard
import de.velwebsolutions.oasis.design.components.OasisKeyboard
import de.velwebsolutions.oasis.design.components.OasisLogo
import de.velwebsolutions.oasis.design.components.OasisTextButton
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.components.Panel
import de.velwebsolutions.oasis.design.components.PanelTone
import de.velwebsolutions.oasis.design.components.Pill
import de.velwebsolutions.oasis.design.components.PillTone
import de.velwebsolutions.oasis.design.components.RelativeTimeText
import de.velwebsolutions.oasis.design.components.SectionCard
import de.velwebsolutions.oasis.design.components.SettingToggle
import de.velwebsolutions.oasis.design.components.SideCard
import de.velwebsolutions.oasis.design.components.SkeletonLayout
import de.velwebsolutions.oasis.design.components.SkeletonView
import de.velwebsolutions.oasis.design.components.SupportContacts
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.design.theme.OasisType
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Every component with each of its optional parameters given, next to the default calls of [ComponentsTest]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExplicitParametersTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `components with every parameter set`() {
        val tagged = Modifier.testTag("x")
        compose.setContent {
            OasisTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OasisButton("A", {}, tagged, OasisButtonKind.Accent, fullWidth = false, enabled = true, loading = false, leading = null)
                    AsyncOasisButton("B", {}, tagged, OasisButtonKind.Secondary, fullWidth = false, enabled = false)
                    OasisTextButton("C", {}, tagged, enabled = true, color = OasisColors.Sun)
                    Text("D", Modifier.pressable(onClick = {}, enabled = false))
                    Pill("E", tagged, PillTone.Night, null)
                    OasisTextField(
                        "F", "x", {}, tagged, prompt = null, error = null, hint = null, isSecure = false, lines = null,
                        keyboardOptions = OasisKeyboard.Email, keyboardActions = KeyboardActions(), enabled = false
                    )
                    OasisTextField("Leer", "", {}, prompt = "Hinweis im Feld")
                    OasisTextField("Lang", "", {}, isSecure = true, lines = 1..3)
                    listOf(OasisKeyboard.Username, OasisKeyboard.OneTimeCode, OasisKeyboard.NoCapitalization).forEach { _ -> }
                    FieldError("G", tagged)
                    SettingToggle("H", true, {}, tagged, detail = null, enabled = true)
                    PageHeader("I", tagged, eyebrow = null, lead = null)
                    Panel(tagged, PanelTone.Warning, spacing = 4) { Text("J") }
                    SideCard("K", tagged, PanelTone.Dune) { Text("K") }
                    SectionCard("L", tagged, detail = null, count = null, tone = PanelTone.Dune) { Text("L") }
                    DashedNote(tagged, highlighted = false) { Text("M") }
                    DashedNote("N", tagged, highlighted = false)
                    Bullets(listOf("O"), tagged)
                    AvatarView("P", "night", tagged, photoUrl = null, size = 20.dp)
                    AuthorizedImage("/q.png", "Q", tagged, ContentScale.Crop)
                    RelativeTimeText(Instant.now(), tagged, OasisType.Small, OasisColors.Ink)
                    LoadableView(Loadable.Loaded("R"), onRetry = {}, modifier = tagged, skeleton = SkeletonLayout.Cards) { Text(it) }
                    LoadingView(tagged)
                    SkeletonView(tagged, SkeletonLayout.Cards)
                    ErrorStateView(ApiError.NotFound, {}, tagged)
                    EmptyStateView("S", tagged, message = null, icon = Icons.Outlined.Inbox) {}
                    Banner("T", tagged, BannerKind.Error)
                    OasisCard(tagged, padding = 4) { Text("U") }
                    Eyebrow("V", tagged, onNight = false)
                    CountBadge(1, tagged)
                    OasisLogo(tagged, 30.dp, wordmark = true)
                    SupportContacts(tagged)
                    Column(Modifier.fillMaxSize()) { LoadableView<String>(Loadable.Loading, {}, tagged, SkeletonLayout.Cards) { Text(it) } }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/design-explicit.png")
        compose.onNodeWithTag("x", useUnmergedTree = true).let { }
    }
}
