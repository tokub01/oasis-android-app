package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.ui.oases.OasisDetailScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormScreen
import de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An oasis whose texts the automated check holds (docs/oasis-auto-moderation.md): the founder, the hosts and staff read
 * the real texts with the „Zurückgehalten“ note on the page and in the form, everyone else the placeholder without it.
 */
@RunWith(RobolectricTestRunner::class)
class OasisHeldUiTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()
    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private val members = """{"data":{"members":[],"hidden_members":0,"own_list_audience":null}}"""
    private val held = """{"data": {"oasis": {"id": 2, "slug": "garten", "name": "Garten", "description": "Gärtnern.", "color": "spring",
        |"visibility": "public", "banner_url": null, "members_count": 4, "role": "host", "held": true}, "url": "http://localhost/oasen/garten",
        |"house_rules": ["Eine"], "hosts": [], "is_member": true, "is_host": true, "can_follow": true, "can_post": true}}
    """.trimMargin()

    @After
    fun clear() = models.clear()

    private fun show(content: @Composable () -> Unit) {
        screen = content
        compose.setContent { OasisTheme { CompositionLocalProvider(LocalNavigator provides navigator) { screen() } } }
    }

    private fun switchTo(content: @Composable () -> Unit) {
        screen = content
        compose.waitForIdle()
    }

    private fun waitFor(text: String) = compose.settle { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun theHostReadsTheNoteOnThePageAndInTheForm() {
        val edit = """{"data":{"oasis":{"id":2,"slug":"garten","name":"Garten","held":true},"house_rules":"Eine","members":[],
            |"options":{"visibilities":[],"colors":[]}}}
        """.trimMargin()
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", held),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", members),
            stub("GET", "network/oases/garten/edit", edit)
        )
        val oasis = models.keep(OasisViewModel("garten", network.repository))
        show { OasisDetailScreen("garten", oasis) }
        waitFor("Zurückgehalten und dem Moderationsteam gemeldet")
        waitFor(
            "Name, Beschreibung und Hausregeln prüft gerade ein Mensch aus dem Moderationsteam. Andere sehen so lange einen Platzhalter."
        )
        compose.onNodeWithTag("oasis.held").assertExists()

        val form = models.keep(OasisFormViewModel("garten", network.repository))
        switchTo { OasisFormScreen("garten", form) }
        compose.settle { form.state.value.edit != null }
        compose.onNodeWithTag("oasis.held").assertExists()
    }

    @Test
    fun everyoneElseReadsThePlaceholderWithoutTheNote() {
        val placeholder = held
            .replace(""""name": "Garten"""", """"name": "Crowd in Prüfung"""")
            .replace(""""house_rules": ["Eine"]""", """"house_rules": []""")
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", placeholder),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", members)
        )
        val oasis = models.keep(OasisViewModel("garten", network.repository))
        show { OasisDetailScreen("garten", oasis) }
        waitFor("Crowd in Prüfung")
        compose.onNodeWithTag("oasis.held").assertDoesNotExist()
    }
}
