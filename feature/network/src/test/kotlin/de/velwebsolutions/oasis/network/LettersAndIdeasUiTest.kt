package de.velwebsolutions.oasis.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
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
import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.theme.OasisTheme
import de.velwebsolutions.oasis.network.ui.feeds.IdeasScreen
import de.velwebsolutions.oasis.network.ui.feeds.IdeasViewModel
import de.velwebsolutions.oasis.network.ui.feeds.WeeklyLetterScreen
import de.velwebsolutions.oasis.network.ui.feeds.WeeklyLetterViewModel
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** „Dein Oasen-Wochenbrief“ and „Ideen für Oasis“ at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class LettersAndIdeasUiTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private val navigator = Navigator()
    private val models = ViewModelKeeper()
    private var screen by mutableStateOf<@Composable () -> Unit>({})

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

    /** A tap through the semantics: nodes inside one tall list item may lie outside the screen. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun capture(name: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/network-$name.png")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun opened(route: AppRoute) = MainTab.entries.any { route in navigator.stack(it) }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("network.page").performScrollToNode(hasTestTag(tag))

    @Test
    fun theLetterShowsItsOasesAndTheEarlierOnes() {
        val network = StubbedNetwork(
            stub("GET", "network/weekly-letters/latest", """{"data":${letterJson(5, oases = twoOases())}}"""),
            stub("GET", "network/weekly-letters", lettersJson()),
            stub("GET", "network/weekly-letters/4", """{"data":${letterJson(4, oases = "[]")}}"""),
            stub("POST", "network/posts/3/reaction", """{"message":"Reagiert."}""")
        )
        val model = models.keep(WeeklyLetterViewModel(null, network.repository))
        show { WeeklyLetterScreen(null, model) }
        waitFor("Woche vom 26. Oktober bis 1. November 2026")
        capture("weekly-letter")
        waitFor("2 neue Mitglieder diese Woche.")
        compose.onNodeWithText("Stadtgarten").tap()
        assertTrue(opened(NetworkRoute.Oasis("garten")))
        scrollTo("letter.meetup.5")
        compose.onNodeWithTag("letter.meetup.5").tap()
        assertTrue(opened(NetworkRoute.Oasis("garten", meetups = true)))
        compose.onNodeWithTag("letter.collection.4").tap()
        assertTrue(opened(NetworkRoute.Oasis("garten", collection = 4)))
        compose.waitUntil(5_000) { model.earlier.value.size == 2 }
        compose.waitForIdle()
        scrollTo("letter.4")
        capture("weekly-letter-earlier")
        compose.onNodeWithText("Wochenbrief per E-Mail einstellen").tap()
        assertTrue(opened(LiveRoute.Settings))
        compose.onNodeWithTag("letter.4").tap()
        waitFor("Was in diesem Wochenbrief stand, ist nicht mehr da.")

        val none = StubbedNetwork(
            stub("GET", "network/weekly-letters/latest", """{"data":null}"""),
            stub("GET", "network/weekly-letters", """{"data":[]}""")
        )
        val empty = models.keep(WeeklyLetterViewModel(null, none.repository))
        switchTo { WeeklyLetterScreen(null, empty) }
        waitFor("Noch kein Wochenbrief.")
        waitFor("Noch keine.")
    }

    @Test
    fun ideasAreSuggestedVotedReportedAndDeleted() {
        val network = StubbedNetwork(
            stub(
                "GET",
                "ideas",
                ideasPageJson(
                    1,
                    2,
                    ideaJson(
                        7,
                        status = "planned",
                        label = "Geplant",
                        reply = "\"Kommt im Winter.\"",
                        vote = """{"id":1,"title":"Neue Farben?"}"""
                    ),
                    ideaJson(8, mine = true, voted = true),
                    ideaJson(9, mine = true, hidden = true).replace("\"can_delete\":true", "\"can_delete\":false"),
                    ideaJson(
                        11,
                        status = "done",
                        label = "Umgesetzt",
                        canVote = false
                    ).replace("\"description\":\"Was wäre, wenn …\"", "\"description\":null")
                )
            ),
            stub("GET", "ideas/3", """{"data":${ideaJson(3)}}"""),
            stub("POST", "ideas", """{"message":"x","errors":{"title":["Bitte mindestens 3 Zeichen."]}}""", 422),
            stub("POST", "ideas/7/vote", """{"message":"Danke für deine Stimme.","data":${ideaJson(7, voted = true, votes = 4)}}"""),
            stub("DELETE", "ideas/8", """{"message":"Deine Idee ist gelöscht."}""")
        )
        val model = models.keep(IdeasViewModel(3, network.repository))
        show { IdeasScreen(3, model) }
        waitFor("Ideen für Oasis")
        waitFor("Idee 7")
        capture("ideas")
        compose.onNodeWithTag("ideas.suggest").tap()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ideas.title"))).performTextInput("Ab")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ideas.description"))).performTextInput("Kurz")
        compose.onNodeWithTag("ideas.submit").tap()
        waitFor("Bitte mindestens 3 Zeichen.")
        capture("ideas-form")
        network.add("POST", "ideas", 500, """{"message":"Server Error"}""")
        compose.onNodeWithTag("ideas.submit").tap()
        compose.waitUntil(5_000) { model.form.value?.error?.fieldMessages?.isEmpty() == true }
        compose.onNodeWithTag("ideas.cancel").tap()

        scrollTo("idea.7")
        compose.onNodeWithTag("idea.7.voteLink").tap()
        assertTrue(opened(AccountRoute.Votes))
        compose.onNodeWithTag("idea.7.vote").tap()
        compose.waitUntil(5_000) { model.ideas.value.valueOrNull?.ideas?.first()?.voted == true }
        compose.onNodeWithTag("idea.3.report").tap()
        assertTrue(opened(AccountRoute.Report("http://localhost/app/ideen/3")))
        scrollTo("idea.9")
        waitFor("Ausgeblendet nach einer Meldung.")
        scrollTo("idea.8")
        compose.onNodeWithTag("idea.8.delete").tap()
        compose.onNodeWithText("Deine Idee löschen?").assertExists()
        compose.onNodeWithTag("dialog.cancel").performClick()
        compose.onNodeWithTag("idea.8.delete").tap()
        compose.onNodeWithTag("dialog.confirm").performClick()
        compose.waitUntil(5_000) { model.status.value == "Deine Idee ist gelöscht." }

        scrollTo("ideas.sort")
        compose.onNodeWithTag("ideas.sort").performClick()
        compose.onNodeWithText("Neu").performClick()
        compose.waitUntil(5_000) { network.transport.requests.any { it.request.url.query?.contains("sort=new") == true } }
        compose.onNodeWithTag("ideas.status").performClick()
        compose.onNodeWithText("In Arbeit").performClick()
        compose.waitUntil(5_000) { network.transport.requests.any { it.request.url.query?.contains("status=in_progress") == true } }
        scrollTo("ideas.more")
        compose.onNodeWithTag("ideas.more").tap()
        compose.waitUntil(5_000) { network.transport.requests.any { it.request.url.query?.contains("page=2") == true } }

        val empty = StubbedNetwork(stub("GET", "ideas", ideasPageJson(1, 1)))
        val none = models.keep(IdeasViewModel(null, empty.repository))
        switchTo { IdeasScreen(null, none) }
        waitFor("Hier ist noch keine Idee. Mach den Anfang!")
    }
}

/** The garden of [letterJson] and an oasis with nothing but one Tropfen. */
fun twoOases(): String = letterJson(0).substringAfter("\"oases\":").removeSuffix("}").removeSuffix("]") +
    """,{"oasis":{"slug":"wald","name":"Waldgruppe"},"posts":[${postJson(6)}],"new_members":0,"meetups":[],"collections":[]}]"""
