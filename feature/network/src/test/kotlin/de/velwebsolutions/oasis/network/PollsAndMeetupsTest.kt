package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.api.Meetup
import de.velwebsolutions.oasis.network.api.MeetupInput
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisMeetups
import de.velwebsolutions.oasis.network.api.OasisWelcome
import de.velwebsolutions.oasis.network.api.PollDraft
import de.velwebsolutions.oasis.network.api.Post
import de.velwebsolutions.oasis.network.ui.PostListModel
import de.velwebsolutions.oasis.network.ui.components.pollFooter
import de.velwebsolutions.oasis.network.ui.components.votes
import de.velwebsolutions.oasis.network.ui.feeds.SourceViewModel
import de.velwebsolutions.oasis.network.ui.oases.MeetupForm
import de.velwebsolutions.oasis.network.ui.oases.OasisViewModel
import de.velwebsolutions.oasis.network.ui.oases.meetupCounts
import de.velwebsolutions.oasis.network.ui.oases.meetupWhen
import de.velwebsolutions.oasis.network.ui.oases.meetupsIndex
import de.velwebsolutions.oasis.network.ui.posts.PostDetailViewModel
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
 * Polls in Tropfen, meetups of an oasis and Willkommenspaten (docs/oasis-network.md): what the view models send, how
 * the answers replace what is shown, and the texts.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class PollsAndMeetupsTest(private val answersAtOnce: Boolean) {
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
    fun refusedSettingsChangeNothing() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = true, pate = false)),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", """{"data":{"members":[]}}"""),
            stub("PUT", "network/oases/garten/meetup-settings", """{"message":"Server Error"}""", status = 500),
            stub("PUT", "network/oases/garten/welcome", """{"message":"Server Error"}""", status = 500)
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        runBlocking {
            model.load()
            assertNull("meetups that cannot load are not shown", model.meetups.value)
            model.setMembersCreateMeetups(false)
            model.setWelcomeVolunteer(false)
            assertEquals(false, model.detail.value.valueOrNull?.welcome?.volunteer)
            assertTrue(model.error.value != null)
            model.loadIfNeeded()
        }
    }

    @Test
    fun theModelsHaveTheirDefaults() {
        val meetup = Meetup(1, Meetup.OasisRef("garten", "Stadtgarten"), "Treffen", Instant.EPOCH)
        assertEquals(Meetup.Counts(), meetup.counts)
        assertTrue(meetup.people.isEmpty() && !meetup.cancelled && !meetup.canEdit)
        assertTrue(OasisMeetups().upcoming.isEmpty())
        assertEquals("", MeetupInput().title)
        assertNull(OasisWelcome().pate)
        assertEquals(0, Post.Poll("Wann?", listOf("a", "b")).voters)
        assertEquals("yes", Meetup.Person("yes", decodePost(postJson(1)).author).answer)
    }

    @Test
    fun aVoteReplacesTheTropfenAndTheTextsSayWhatHolds() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten/posts", pageJson(pollPostJson(3), postJson(4))),
            stub(
                "POST",
                "network/posts/3/poll/vote",
                """{"message":"Abgestimmt.","data":${pollPostJson(3, mine = "[1]", counts = "[0,1]")}}"""
            ),
            stub("POST", "network/posts/3/poll/close", """{"message":"x","errors":{"poll":["Diese Umfrage ist beendet."]}}""", status = 422)
        )
        val list = PostListModel<NoContext>(network.repository) { _, cursor -> oasisPosts("garten", cursor) }
        runBlocking {
            list.reload()
            list.vote(list.posts.first(), 1)
            assertEquals("""{"options":[1]}""", network.last("network/posts/3/poll/vote").body.orEmpty())
            assertEquals(listOf(1), list.posts.first().poll?.myOptions)
            assertEquals("Abgestimmt.", list.state.value.status)
            list.vote(list.posts.last(), 0)
            list.closePoll(list.posts.first())
            assertEquals("Diese Umfrage ist beendet.", (list.state.value.actionError as ApiError.Validation).fields["poll"]?.first())
        }
        val poll = decodePost(pollPostJson(3, mine = "[0]")).poll!!
        assertEquals(emptyList<Int>(), poll.choosing(0))
        assertEquals(listOf(1), poll.choosing(1))
        assertEquals(listOf(0, 2), poll.copy(multiple = true).choosing(2))
        assertEquals("1 Stimme", votes(1))
        assertEquals("2 Stimmen", votes(2))
        assertEquals(
            "1 Person hat abgestimmt · Mehrere Antworten möglich · Beendet · Wer abgestimmt hat, sieht niemand.",
            pollFooter(poll.copy(voters = 1, multiple = true, closed = true))
        )
        assertEquals(
            "3 Personen haben abgestimmt · Endet Mi., 4. Nov., 18:00 Uhr · Wer abgestimmt hat, sieht niemand.",
            pollFooter(poll.copy(voters = 3, endsAt = Instant.parse("2026-11-04T17:00:00Z")))
        )
        assertEquals("0 Personen haben abgestimmt · Ohne Ende · Wer abgestimmt hat, sieht niemand.", pollFooter(poll.copy(voters = 0)))
    }

    @Test
    fun theTropfenPageVotesAndEndsItsPoll() {
        val network = StubbedNetwork(
            stub("GET", "network/posts/3", """{"data":{"post":${pollPostJson(3)}}}"""),
            stub(
                "POST",
                "network/posts/3/poll/close",
                """{"message":"Die Umfrage ist beendet.","data":${pollPostJson(3, closed = true)}}"""
            ),
            stub("POST", "network/posts/3/poll/vote", """{"message":"Server Error"}""", status = 500),
            stub("POST", "network/posts/9/poll/vote", """{"message":"Abgestimmt.","data":${pollPostJson(9, "[0]")}}""")
        )
        val model = models.keep(PostDetailViewModel(3, network.repository))
        runBlocking {
            model.vote(decodePost(pollPostJson(3)), 0)
            model.closePoll(decodePost(pollPostJson(3)))
            assertEquals("Die Umfrage ist beendet.", model.status.value)
            model.load()
            model.closePoll(decodePost(pollPostJson(3)))
            assertTrue(model.detail.value.valueOrNull?.post?.poll?.closed == true)
            model.vote(decodePost(pollPostJson(3)), 0)
            assertTrue(model.error.value != null)
            model.vote(decodePost(postJson(4)), 0)
            model.vote(decodePost(pollPostJson(9)), 0)
            assertEquals("Abgestimmt.", model.status.value)
        }
    }

    @Test
    fun theComposerSendsAPollOnlyInANewOasisTropfen() {
        val network = StubbedNetwork(
            stub("GET", "network/composer", Fixtures.composer),
            stub("POST", "network/posts", """{"message":"Dein Tropfen ist da.","suspended":false,"data":${postJson(77)}}""", 201)
        )
        val composer = models.keep(network.composer("garten"))
        assertTrue(composer.state.value.canPoll)
        assertFalse(models.keep(network.composer()).state.value.canPoll)
        assertFalse(models.keep(network.composer("garten", replyTo = 3)).state.value.canPoll)
        val carry = models.keep(network.composer("garten", carried = 3))
        assertFalse(carry.state.value.canPoll)
        // A poll left over in a carry is not sent.
        carry.showPoll(PollDraft(question = "Wann?"))
        carry.updateBody("Weiter.")
        runBlocking { carry.publish() }
        assertFalse("poll[question]" in network.last("network/posts").body.orEmpty())
        composer.editPoll { it.copy(question = "nichts") }
        assertNull(composer.state.value.poll)
        composer.showPoll(PollDraft())
        composer.editPoll { it.copy(question = "Wann?", options = listOf("Sa", "So"), multiple = true, duration = "3d") }
        composer.updateBody("Wir planen.")
        runBlocking { composer.publish() }
        val body = network.last("network/posts").body.orEmpty()
        listOf("poll[question]", "Wann?", "poll[options][]", "poll[multiple]", "poll[duration]", "3d").forEach {
            assertTrue(it, it in body)
        }
        assertNull("the poll went with the Tropfen", composer.state.value.poll)
        composer.showPoll(PollDraft())
        composer.editPoll { it.copy(question = "Ohne Ende?") }
        composer.updateBody("Noch einer.")
        runBlocking { composer.publish() }
        assertFalse("poll[duration]" in network.last("network/posts").body.orEmpty())
    }

    @Test
    fun membersSeeMeetupsAnswerAndCreateThem() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = true, pate = true)),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("GET", "network/oases/garten/members", """{"data":{"members":[]}}"""),
            stub("GET", "network/oases/garten/meetups", meetupsJson()),
            stub("POST", "network/meetups/5/answer", """{"message":"Deine Antwort: Ich komme","data":${meetupJson(5, answer = "yes")}}"""),
            stub(
                "DELETE",
                "network/meetups/6",
                """{"message":"Das Treffen ist abgesagt.","data":${meetupJson(6, cancelled = true, past = true)}}"""
            ),
            stub(
                "POST",
                "network/oases/garten/meetups",
                """{"message":"x","errors":{"title":["Bitte gib einen Titel an."]}}""",
                status = 422
            ),
            stub("PUT", "network/meetups/5", """{"message":"Das Treffen ist geändert.","data":${meetupJson(5)}}"""),
            stub("PUT", "network/oases/garten/meetup-settings", """{"data":{"members_create_meetups":true}}"""),
            stub("PUT", "network/oases/garten/welcome", """{"data":{"volunteer":true}}""")
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        runBlocking {
            model.load()
            val meetups = model.meetups.value!!
            assertEquals(listOf(5), meetups.upcoming.map { it.id })
            model.answerMeetup(meetups.upcoming.first(), "yes")
            assertEquals("""{"answer":"yes"}""", network.last("network/meetups/5/answer").body.orEmpty())
            assertEquals("yes", model.meetups.value!!.upcoming.first().myAnswer)
            model.cancelMeetup(meetups.past.first())
            assertTrue(model.meetups.value!!.past.first().cancelled)
            model.answerMeetup(decodeMeetup(meetupJson(99)), "no")
            model.loadIfNeeded()

            model.saveMeetup()
            model.openMeetupForm(null, Instant.parse("2026-11-03T10:00:00Z"))
            assertEquals(Instant.parse("2026-11-04T17:00:00Z"), model.meetupForm.value?.startsAt)
            model.editMeetupForm { it.copy(title = " ") }
            model.saveMeetup()
            assertEquals("Bitte gib einen Titel an.", model.meetupForm.value?.error?.message("title"))
            model.closeMeetupForm()
            model.editMeetupForm { it.copy(title = "nichts") }
            assertNull(model.meetupForm.value)

            model.openMeetupForm(model.meetups.value!!.upcoming.first())
            model.editMeetupForm { it.copy(place = " Hinterhof ") }
            model.saveMeetup()
            assertTrue(""""place":"Hinterhof"""" in network.last("network/meetups/5").body.orEmpty())
            assertEquals("Das Treffen ist geändert.", model.status.value)
            assertNull(model.meetupForm.value)

            model.setMembersCreateMeetups(true)
            assertTrue(model.meetups.value!!.membersCreateMeetups)
            model.setWelcomeVolunteer(true)
            assertEquals(true, model.detail.value.valueOrNull?.welcome?.volunteer)
            assertEquals("header, pate, rules, composer entry", 4, meetupsIndex(model.detail.value.valueOrNull!!))
        }
    }

    @Test
    fun othersSeeNoMeetupsAndFailuresChangeNothing() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/garten", oasisJson(member = false, pate = false)),
            stub("GET", "network/oases/garten/posts", pageJson()),
            stub("POST", "network/meetups/5/answer", """{"message":"Deine Antwort: Nein","data":${meetupJson(5, answer = "no")}}"""),
            stub("PUT", "network/oases/garten/meetup-settings", """{"data":{"members_create_meetups":true}}"""),
            stub("PUT", "network/oases/garten/welcome", """{"data":{"volunteer":true}}"""),
            stub("GET", "network/meetups/upcoming", """{"data":[${meetupJson(5)}]}""")
        )
        val model = models.keep(OasisViewModel("garten", network.repository))
        runBlocking {
            model.setWelcomeVolunteer(true)
            model.load()
            assertNull(model.meetups.value)
            // Without meetups or a welcome shown, the answers change nothing on the page.
            model.answerMeetup(decodeMeetup(meetupJson(5)), "no")
            assertNull(model.meetups.value)
            model.setMembersCreateMeetups(true)
            model.setWelcomeVolunteer(true)
            assertNull(model.detail.value.valueOrNull?.welcome)
            model.loadIfNeeded()
            assertEquals(2, meetupsIndex(model.detail.value.valueOrNull!!))
        }
        val source = models.keep(SourceViewModel(network.repository, network.session))
        runBlocking { source.loadMeetups() }
        assertEquals(listOf(5), source.meetups.value.map { it.id })
        val failing = models.keep(SourceViewModel(StubbedNetwork().repository, network.session))
        runBlocking { failing.loadMeetups() }
        assertTrue(failing.meetups.value.isEmpty())

        assertEquals("Mittwoch, 4. November 2026, 18:00 Uhr", meetupWhen(Instant.parse("2026-11-04T17:00:00Z")))
        assertEquals("1 kommt · 2 vielleicht · 0 nicht", meetupCounts(Meetup.Counts(1, 2, 0)))
        assertEquals("2 kommen · 0 vielleicht · 1 nicht", meetupCounts(Meetup.Counts(2, 0, 1)))
        assertEquals("Vielleicht", Meetup.answerLabel("maybe"))
        assertEquals("anders", Meetup.answerLabel("anders"))
        val form = MeetupForm.of(decodeMeetup(meetupJson(5)))
        assertEquals(5, form.editing)
        assertEquals("", MeetupForm.of(decodeMeetup(meetupJson(5)).copy(place = null, link = null, description = null)).input.place)
    }
}

/** A Tropfen of an oasis with a poll of two answers. */
fun pollPostJson(id: Int, mine: String = "[]", counts: String = "[1,0]", closed: Boolean = false): String =
    postJson(id).trimEnd().removeSuffix("}") +
        """
        ,"poll":{"question":"Wann wandern wir?","options":["Samstag","Sonntag"],"counts":$counts,"voters":1,
        "multiple":false,"ends_at":null,"closed":$closed,"my_options":$mine,"can_vote":true,"can_close":true}}
        """.trimIndent()

fun meetupJson(id: Int, answer: String? = null, cancelled: Boolean = false, past: Boolean = false): String {
    val mine = answer?.let { "\"$it\"" } ?: "null"
    return """
        {"id":$id,"oasis":{"slug":"garten","name":"Stadtgarten"},"title":"Gießen und Kuchen",
        "starts_at":"2026-11-07T13:00:00Z","place":"Hinterhof","link":"https://example.org/raum",
        "description":"Bringt Gießkannen mit.","cancelled":$cancelled,"past":$past,
        "counts":{"yes":2,"maybe":1,"no":0},"my_answer":$mine,
        "people":[{"answer":"maybe","member":{"id":3,"username":"aylin","display_name":"Aylin Demir","initials":"AD"}},
        {"answer":"yes","member":{"id":2,"username":"jonas","display_name":"Jonas Weber","initials":"JW"}}],
        "can_answer":${!cancelled && !past},"can_edit":${!past}}
    """.trimIndent()
}

fun meetupsJson() = """
    {"data":{"can_create":true,"members_create_meetups":false,
    "upcoming":[${meetupJson(5)}],"past":[${meetupJson(6, past = true)}]}}
""".trimIndent()

fun decodeMeetup(json: String): Meetup = de.velwebsolutions.oasis.core.network.OasisJson.snake.decodeFromString(Meetup.serializer(), json)

fun oasisJson(member: Boolean, pate: Boolean): String {
    val pateJson = if (pate) """{"id":3,"username":"aylin","display_name":"Aylin Demir","initials":"AD"}""" else "null"
    val volunteers = """[{"id":2,"username":"jonas","display_name":"Jonas Weber","initials":"JW"}]"""
    val welcome = if (member) ""","welcome":{"pate":$pateJson,"volunteer":false,"volunteers":$volunteers}""" else ""
    return """
        {"data":{"oasis":{"id":2,"slug":"garten","name":"Stadtgarten","description":"Gärtnern","color":"spring",
        "visibility":"public","members_count":4},"url":"http://localhost/oasen/garten","house_rules":[],"hosts":[],
        "is_member":$member,"is_host":$member,"can_follow":${!member},"can_post":$member,"members_create_meetups":false$welcome}}
    """.trimIndent()
}
