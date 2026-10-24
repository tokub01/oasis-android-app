package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.network.api.ImageUpload
import de.velwebsolutions.oasis.network.api.OasisInput
import de.velwebsolutions.oasis.network.api.PostDraft
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The network resources as the Laravel API sends them, and the requests the repository makes. */
class NetworkRepositoryTest {
    @Test
    fun `decodes the Quelle with context, conversation and viewer state`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/source", Fixtures.source))
        val page = network.repository.source(view = null, cursor = null)
        val first = page.data.first()
        assertEquals("lukas", first.author.username)
        assertEquals("rad-werkstatt", first.oasis?.slug)
        assertTrue(first.images.first().url.endsWith("/api/v1/network/posts/12/images/4"))
        assertEquals("jonas", first.conversation?.replies?.first()?.author?.username)
        assertEquals(true, first.viewer?.canReply)
        assertNull(page.nextCursor)
        assertEquals(3, page.context?.week?.karawane)
        assertEquals("host", page.context?.oases?.first()?.role)
        assertEquals(true, page.context?.canPost)
        assertEquals("Wohin mit einem Überschuss?", page.context?.openVote?.title)
    }

    @Test
    fun `decodes every other screen`() = runTest {
        val network = StubbedNetwork(
            stub("GET", "network/swipe", Fixtures.swipe),
            stub("GET", "network/oases", Fixtures.oases),
            stub("GET", "network/statistics", Fixtures.statistics),
            stub("GET", "network/invitations", Fixtures.invitations),
            stub("GET", "network/profile", Fixtures.ownProfile),
            stub("GET", "network/profiles/jonas", Fixtures.profile),
            stub("GET", "network/composer", Fixtures.composer),
            stub("GET", "network/search", Fixtures.search),
            stub("GET", "network/friends", Fixtures.friends),
            stub("GET", "network/posts/12", Fixtures.post),
            stub("GET", "network/discover", Fixtures.discover)
        )
        val repository = network.repository
        assertEquals("oasen", repository.swipe().chapters.first().key)
        assertEquals(4, repository.oases().options.colors.size)
        assertEquals(12, repository.statistics().weekly.size)
        assertEquals(3, repository.invitations().perMonth)
        val own = repository.ownProfile()
        assertEquals("Sonne", own.colors.first().label)
        assertNull(own.bannerUrl)
        val profile = repository.profile("jonas")
        assertEquals("jonas", profile.member.username)
        assertTrue(profile.bannerUrl!!.endsWith("/banner/Abc123Abc123Abc123Abc123Abc123Ab.webp"))
        assertEquals("Jonas Weber nimmt gerade nur Nachrichten von bestimmten Personen an.", profile.chatHint)
        assertFalse(profile.canWrite)
        assertTrue(profile.oases!!.any { it.role == "host" })
        assertEquals(listOf("post", "question"), repository.composer(null, null, null).kinds.map { it.value })
        assertEquals(true, repository.search("ga", null).context?.searching)
        assertTrue(repository.friends().acceptsRequests)
        assertEquals(12, repository.post(12).post.id)
        val carried = repository.discover(null).data.first().carried
        assertEquals("jonas", carried?.post?.author?.username)
        assertFalse(repository.discover(null).context?.oases.isNullOrEmpty())
    }

    @Test
    fun `sends the Tropfen as multipart with images and descriptions`() = runTest {
        val network =
            StubbedNetwork(
                stub("POST", "network/posts", """{"message":"Dein Tropfen ist da.","suspended":false,"data":${postJson(99)}}""", 201)
            )
        val image = ImageUpload(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0), "bild-1.jpg", "image/jpeg")
        val result = network.repository.publish(
            PostDraft(
                body = "Hallo Garten",
                oasisSlug = "stadtgarten",
                toneConfirmed = true,
                images = listOf(image),
                alts = listOf("Ein Beet")
            )
        )
        assertEquals("Dein Tropfen ist da.", result.message)
        assertEquals(99, result.data.id)
        val request = network.last("network/posts")
        assertTrue(request.request.body!!.contentType().toString().startsWith("multipart/form-data"))
        val body = request.body!!
        assertTrue(body.contains(Regex("name=\"oasis\"[\\s\\S]*?\r\n\r\nstadtgarten")))
        assertTrue(body.contains(Regex("name=\"alts\\[]\"[\\s\\S]*?\r\n\r\nEin Beet")))
        assertTrue(body.contains(Regex("name=\"tone_confirmed\"[\\s\\S]*?\r\n\r\n1")))
        assertTrue(body.contains("name=\"images[]\"; filename=\"bild-1.jpg\""))
        assertFalse(body.contains("name=\"reply_to\""))
    }

    @Test
    fun `pages with the cursor and passes the view of the Quelle`() = runTest {
        val network = StubbedNetwork(stub("GET", "network/source", pageJson(postJson(1))))
        network.repository.source(view = "alle", cursor = "abc")
        val url = network.last("network/source").request.url
        assertEquals("alle", url.queryParameter("view"))
        assertEquals("abc", url.queryParameter("cursor"))
        network.repository.source(view = null, cursor = null)
        assertNull(network.last("network/source").request.url.query)
    }

    @Test
    fun `writes with the right methods, paths and snake case bodies`() = runTest {
        val network = StubbedNetwork(
            stub("DELETE", "network/oases/lesekreis", """{"message":"Die Oase ist geschlossen."}"""),
            stub(
                "PUT",
                "network/profile",
                """{"message":"Gespeichert.","data":${Fixtures.ownProfile.substringAfter("{\"data\": ").dropLast(1)}}"""
            ),
            stub("POST", "network/users/5/mute", """{"message":"Stumm."}"""),
            stub("DELETE", "network/posts/3/bookmark", """{"message":"Entfernt."}"""),
            stub("PUT", "network/oases/korn-licht", """{"data":{"slug":"korn-licht"}}""")
        )
        val repository = network.repository
        assertEquals("Die Oase ist geschlossen.", repository.closeOasis("lese/kreis", confirm = true))
        assertEquals(true, network.transport.lastBody("network/oases/lesekreis")!!.jsonObject["confirm"]!!.jsonPrimitive.boolean)

        val saved = repository.updateOwnProfile("Bio", "Leipzig", "spring", toneConfirmed = true)
        assertEquals("Gespeichert.", saved.message)
        val profileBody = network.transport.lastBody("network/profile")!!.jsonObject
        assertEquals("spring", profileBody["avatar_color"]!!.jsonPrimitive.content)
        assertEquals(true, profileBody["tone_confirmed"]!!.jsonPrimitive.boolean)

        assertEquals("Stumm.", repository.mute(5, on = true))
        assertEquals("Entfernt.", repository.bookmark(3, on = false))
        assertEquals("korn-licht", repository.updateOasis("korn-licht", OasisInput(name = "Korn", houseRules = "Freundlich")))
        assertEquals(
            "Freundlich",
            network.transport.lastBody("network/oases/korn-licht")!!.jsonObject["house_rules"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `maps a refused Tropfen to field errors`() = runTest {
        val network = StubbedNetwork(
            stub("POST", "network/posts", """{"message":"Zu lang.","errors":{"body":["Dein Tropfen ist zu lang."]}}""", 422)
        )
        try {
            network.repository.publish(PostDraft(body = "x"))
            fail("422 must throw")
        } catch (error: ApiError) {
            assertEquals("Dein Tropfen ist zu lang.", error.message("body"))
        }
    }
}
