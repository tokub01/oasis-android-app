package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.network.api.Idea
import de.velwebsolutions.oasis.network.api.IdeaInput
import de.velwebsolutions.oasis.network.api.WeeklyLetter
import de.velwebsolutions.oasis.network.api.WeeklyLetterSummary
import de.velwebsolutions.oasis.network.ui.PostAction
import de.velwebsolutions.oasis.network.ui.feeds.IdeaForm
import de.velwebsolutions.oasis.network.ui.feeds.IdeasViewModel
import de.velwebsolutions.oasis.network.ui.feeds.WeeklyLetterViewModel
import de.velwebsolutions.oasis.network.ui.feeds.letterDay
import de.velwebsolutions.oasis.network.ui.feeds.letterWeek
import de.velwebsolutions.oasis.network.ui.feeds.newMembersText
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * The Crowd-Wochenbrief (docs/mildcrowd-notifications.md) and „Ideen für Mildcrowd“ (docs/mildcrowd-admin.md): what the view models
 * send and how the answers change what is shown.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LettersAndIdeasTest(private val answersAtOnce: Boolean) {
    companion object {
        /** Each test runs with answers that suspend and with answers on the calling thread. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "immediate={0}")
        fun modes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule val main = MainDispatcherRule()

    private val models = ViewModelKeeper()

    @Before
    fun answerMode() {
        immediate = answersAtOnce
    }

    @After
    fun clear() {
        immediate = false
        models.clear()
    }

    @Test
    fun theNewestLetterThenAnEarlierOne() {
        val network = StubbedNetwork(
            stub("GET", "network/weekly-letters/latest", """{"data":${letterJson(5)}}"""),
            stub("GET", "network/weekly-letters", lettersJson()),
            stub("GET", "network/weekly-letters/4", """{"data":${letterJson(4, oases = "[]")}}"""),
            stub("POST", "network/posts/3/reaction", """{"message":"Reagiert."}"""),
            stub("POST", "network/posts/3/bookmark", """{"message":"x"}""", 500),
            stub("POST", "network/users/2/mute", """{"message":"Stumm."}""")
        )
        val model = models.keep(WeeklyLetterViewModel(null, network.repository))
        runBlocking {
            model.loadIfNeeded()
            model.loadIfNeeded()
            assertEquals(1, network.transport.requests.count { it.path == "network/weekly-letters/latest" })
            assertEquals(listOf("garten"), model.letter.value.valueOrNull?.oases?.map { it.oasis.slug })
            assertEquals(listOf(5, 4), model.earlier.value.map { it.id })
            val post = model.letter.value.valueOrNull!!.oases.first().posts.first()
            model.perform(PostAction.React, post)
            assertEquals(true, model.letter.value.valueOrNull!!.oases.first().posts.first().viewer?.reacted)
            assertEquals("Reagiert.", model.status.value)
            model.perform(PostAction.Bookmark, post)
            assertEquals("rolled back", false, model.letter.value.valueOrNull!!.oases.first().posts.first().viewer?.bookmarked)
            assertTrue(model.error.value != null)
            model.perform(PostAction.Mute, post)
            assertEquals("Stumm.", model.status.value)
            model.show(4)
            assertTrue(model.letter.value.valueOrNull!!.oases.isEmpty())
            model.perform(PostAction.Mute, post)
        }
    }

    @Test
    fun noLetterYetAndALetterThatCannotLoad() {
        val network = StubbedNetwork(
            stub("GET", "network/weekly-letters/latest", """{"data":null}"""),
            stub("GET", "network/weekly-letters", """{"message":"x"}""", 500),
            stub("GET", "network/weekly-letters/9", """{"message":"Nicht gefunden."}""", 404)
        )
        val model = models.keep(WeeklyLetterViewModel(null, network.repository))
        val missing = models.keep(WeeklyLetterViewModel(9, network.repository))
        runBlocking {
            model.load()
            assertEquals(Loadable.Loaded(null), model.letter.value)
            assertTrue(model.earlier.value.isEmpty())
            model.perform(PostAction.React, decodePost(postJson(3)))
            missing.load()
            assertTrue(missing.letter.value is Loadable.Failed)
        }
        assertEquals("26. Oktober 2026", letterDay("2026-10-26", "d. MMMM yyyy"))
        assertEquals("bald", letterDay("bald", "d. MMMM"))
        assertEquals("Woche vom 26. Oktober bis 1. November 2026", letterWeek("2026-10-26", "2026-11-01"))
        assertEquals("Ein neues Mitglied diese Woche.", newMembersText(1))
        assertEquals("3 neue Mitglieder diese Woche.", newMembersText(3))
        val letter = WeeklyLetter(1, "2026-10-26", "2026-11-01")
        assertTrue(letter.oases.isEmpty() && letter.summary.isEmpty())
        val entry = WeeklyLetter.Entry(de.velwebsolutions.oasis.network.api.NamedOasis("garten", "Stadtgarten"))
        assertTrue(entry.posts.isEmpty() && entry.newMembers == 0 && entry.meetups.isEmpty() && entry.collections.isEmpty())
        assertNull(WeeklyLetter.LetterMeetup(1, "Treffen", Instant.EPOCH).place)
        assertEquals("", WeeklyLetterSummary(1, "a", "b").summary)
        assertNull(de.velwebsolutions.oasis.network.api.LatestWeeklyLetter().data)
        listOf(
            WeeklyLetter.LetterCollection.serializer(),
            WeeklyLetter.LetterMeetup.serializer(),
            WeeklyLetter.Entry.serializer()
        ).forEach { assertTrue(it.descriptor.serialName.isNotEmpty()) }
        assertEquals("Rezepte", WeeklyLetter.LetterCollection(4, "Rezepte").name)
        assertTrue(de.velwebsolutions.oasis.network.ui.feeds.IdeaList().let { it.ideas.isEmpty() && it.nextPage == null })
    }

    @Test
    fun ideasLoadSortFilterAndPageOn() {
        val network = StubbedNetwork(
            stub("GET", "ideas", ideasPageJson(1, 2, ideaJson(7), ideaJson(8, mine = true))),
            stub("GET", "ideas/3", """{"data":${ideaJson(3)}}""")
        )
        val model = models.keep(IdeasViewModel(3, network.repository))
        runBlocking {
            model.loadIfNeeded()
            model.loadIfNeeded()
            assertEquals(listOf(7, 8), model.ideas.value.valueOrNull?.ideas?.map { it.id })
            assertEquals(3, model.focused.value?.id)
            assertEquals(2, model.ideas.value.valueOrNull?.nextPage)
            network.add("GET", "ideas", json = ideasPageJson(2, 2, ideaJson(8), ideaJson(9)))
            model.loadMore()
            assertEquals("sort=popular&page=2", network.last("ideas").request.url.query)
            assertEquals(listOf(7, 8, 9), model.ideas.value.valueOrNull?.ideas?.map { it.id })
            assertNull(model.ideas.value.valueOrNull?.nextPage)
            model.loadMore()
            model.choose("new")
            model.filter("planned")
            assertEquals("sort=new&status=planned&page=1", network.last("ideas").request.url.query)
            assertEquals("the idea of the link loads once", 1, network.transport.requests.count { it.path == "ideas/3" })
            assertEquals("new", model.sort.value)
            assertEquals("planned", model.statusFilter.value)
        }
        val failing = StubbedNetwork(stub("GET", "ideas", """{"message":"x"}""", 500))
        val broken = models.keep(IdeasViewModel(null, failing.repository))
        runBlocking {
            broken.load()
            assertTrue(broken.ideas.value is Loadable.Failed)
        }
        val paging = StubbedNetwork(stub("GET", "ideas", ideasPageJson(1, 3, ideaJson(7))), stub("GET", "ideas/3", "{}", 404))
        val more = models.keep(IdeasViewModel(3, paging.repository))
        runBlocking {
            more.load()
            assertNull("a held or deleted idea of the link is not shown", more.focused.value)
            paging.add("GET", "ideas", 500, """{"message":"x"}""")
            more.loadMore()
            assertTrue(more.error.value != null)
            assertEquals(2, more.ideas.value.valueOrNull?.nextPage)
        }
    }

    @Test
    fun anIdeaIsSuggestedVotedAndDeleted() {
        val network = StubbedNetwork(
            stub("GET", "ideas", ideasPageJson(1, 1, ideaJson(7), ideaJson(8, mine = true))),
            stub("GET", "ideas/8", """{"data":${ideaJson(8, mine = true)}}"""),
            stub("POST", "ideas", """{"message":"x","errors":{"title":["Bitte mindestens 3 Zeichen."]}}""", 422),
            stub("POST", "ideas/7/vote", """{"message":"Danke für deine Stimme.","data":${ideaJson(7, voted = true, votes = 4)}}"""),
            stub("POST", "ideas/8/vote", """{"message":"Danke.","data":${ideaJson(8, mine = true, voted = true)}}"""),
            stub("DELETE", "ideas/7/vote", """{"message":"x","errors":{"vote":["Diese Idee ist abgeschlossen."]}}""", 422),
            stub("DELETE", "ideas/8", """{"message":"Deine Idee ist gelöscht."}"""),
            stub("DELETE", "ideas/7", """{"message":"x"}""", 403)
        )
        val unfocused = models.keep(IdeasViewModel(null, network.repository))
        runBlocking {
            unfocused.loadMore()
            unfocused.toggleVote(decodeIdea(ideaJson(7)))
            assertTrue("nothing shown yet, nothing replaced", unfocused.ideas.value is Loadable.Idle)
            unfocused.load()
            unfocused.toggleVote(decodeIdea(ideaJson(7)))
            unfocused.delete(decodeIdea(ideaJson(8, mine = true)))
            assertNull(unfocused.focused.value)
        }
        val model = models.keep(IdeasViewModel(8, network.repository))
        runBlocking {
            model.suggest()
            model.load()
            model.openForm()
            model.editForm { it.copy(title = " Ab ") }
            model.suggest()
            assertEquals("Bitte mindestens 3 Zeichen.", model.form.value?.error?.message("title"))
            assertEquals("""{"title":"Ab"}""", network.last("ideas").body)
            network.add("POST", "ideas", 201, """{"message":"Danke für deine Idee!","data":${ideaJson(10, mine = true)}}""")
            model.suggest()
            assertNull(model.form.value)
            assertEquals("Danke für deine Idee!", model.status.value)
            model.closeForm()
            model.editForm { it.copy(title = "x") }
            assertNull(model.form.value)

            val first = model.ideas.value.valueOrNull!!.ideas.first()
            model.toggleVote(first)
            assertEquals(4, model.ideas.value.valueOrNull!!.ideas.first().votes)
            model.toggleVote(model.ideas.value.valueOrNull!!.ideas.first())
            assertTrue(model.error.value != null)
            model.toggleVote(model.focused.value!!)
            assertTrue(model.focused.value!!.voted)

            model.delete(first)
            assertTrue(model.error.value != null)
            model.delete(model.focused.value!!)
            assertNull(model.focused.value)
            assertEquals("Deine Idee ist gelöscht.", model.status.value)
        }
        val idea = decodeIdea(ideaJson(1))
        assertFalse(idea.isUnderway)
        assertTrue(idea.copy(status = "done").isUnderway)
        assertEquals(listOf("open", "planned", "in_progress", "done", "declined"), Idea.statuses.map { it.first })
        val plain = Idea(1, "Titel", createdAt = Instant.EPOCH)
        assertTrue(plain.description == null && plain.votes == 0 && !plain.voted && plain.author == null)
        assertEquals("Offen", plain.statusLabel)
        assertEquals("", IdeaInput().title)
        assertEquals("", IdeaForm().title)
    }
}

fun letterJson(id: Int, oases: String? = null): String {
    val entries = oases ?: """
        [{"oasis":{"slug":"garten","name":"Stadtgarten"},"posts":[${postJson(3)}],"new_members":2,
        "meetups":[{"id":5,"title":"Gießen und Kuchen","starts_at":"2026-11-07T13:00:00Z","place":"Hinterhof"}],
        "collections":[{"id":4,"name":"Rezepte"}]}]
    """.trimIndent()
    return """{"id":$id,"week_start":"2026-10-26","week_end":"2026-11-01","summary":"1 Crowd · 1 Post · 1 Treffen","oases":$entries}"""
}

fun lettersJson() = """
    {"data":[{"id":5,"week_start":"2026-10-26","week_end":"2026-11-01","summary":"1 Crowd · 1 Post"},
    {"id":4,"week_start":"2026-10-19","week_end":"2026-10-25","summary":"2 Crowds"}]}
""".trimIndent()

fun ideaJson(
    id: Int,
    mine: Boolean = false,
    voted: Boolean = false,
    votes: Int = 3,
    status: String = "open",
    label: String = "Offen",
    reply: String = "null",
    hidden: Boolean = false,
    canVote: Boolean = true
) = """
    {"id":$id,"title":"Idee $id","description":"Was wäre, wenn …","status":"$status","status_label":"$label","status_reply":$reply,
    "status_changed_at":null,"votes":$votes,"voted":$voted,"mine":$mine,"hidden":$hidden,"can_vote":$canVote,"can_delete":$mine,
    "author":{"id":2,"username":"jonas","display_name":"Jonas Weber","initials":"JW"},"created_at":"2026-10-20T10:00:00.000000Z",
    "report_url":"http://localhost/app/ideen/$id"}
""".trimIndent()

fun ideasPageJson(current: Int, last: Int, vararg ideas: String) =
    """{"data":[${ideas.joinToString(",")}],"meta":{"current_page":$current,"last_page":$last,"per_page":20}}"""

fun decodeIdea(json: String): Idea = de.velwebsolutions.oasis.core.network.OasisJson.snake.decodeFromString(Idea.serializer(), json)
