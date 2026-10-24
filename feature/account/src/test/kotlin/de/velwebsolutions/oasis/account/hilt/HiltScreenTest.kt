package de.velwebsolutions.oasis.account.hilt

import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltTestApplication
import de.velwebsolutions.oasis.core.auth.Session
import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.testing.StubTransport
import javax.inject.Inject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/**
 * The account screens in the app's Hilt graph over the stubbed API: real view models, real repository, the
 * module's routes as the app calls them. Subclasses are `@HiltAndroidTest` Robolectric tests.
 */
abstract class HiltScreenTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var transport: StubTransport

    @Inject lateinit var session: Session

    val navigator = Navigator()
    val web = mutableListOf<String>()
    private var scenario: ActivityScenario<HiltTestActivity>? = null

    @Before
    fun startGraph() {
        hilt.inject()
        navigator.openWeb = { web += it }
        val app = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, HiltTestActivity::class.java))
    }

    @After
    fun closeActivity() {
        scenario?.close()
    }

    /** The member as `auth/me` would say. */
    fun signIn(me: Me = MIRA) = session.update(me)

    fun stub(method: String, path: String, json: String, status: Int = 200) = transport.add(method, path, status, json)

    fun show(content: @Composable () -> Unit) {
        scenario = ActivityScenario.launch(HiltTestActivity::class.java).onActivity { activity ->
            activity.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { content() } } }
        }
    }

    fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/account-$name.png")

    /** The whole screen with its dialogs and sheets. */
    fun screenShot(name: String) = captureScreenRoboImage("build/outputs/roborazzi/account-$name.png")

    fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    fun waitForTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    fun waitForRequest(method: String, path: String) =
        compose.waitUntil(5_000) { transport.requests.any { it.request.method == method && it.path == path } }

    /** Clicks through the semantics, so a snackbar over the node cannot catch the tap. */
    fun SemanticsNodeInteraction.tap() = performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    /** Types into the field with this label (its content description). */
    fun type(label: String, text: String) = compose.onNodeWithContentDescription(label).performScrollTo().performTextInput(text)

    /** All nodes with this text (dialog buttons repeat the screen's button). */
    fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesText(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().indices.map { onAllNodes(hasText(text))[it] }

    /** Clicks a node that is not inside a scrolling container. */
    fun SemanticsNodeInteraction.press() = performSemanticsAction(SemanticsActions.OnClick)

    companion object {
        val MIRA = Me(id = 5, username = "mira", displayName = "Mira Sol", initials = "MS", email = "mira@demo.oasis.tld")
    }
}
