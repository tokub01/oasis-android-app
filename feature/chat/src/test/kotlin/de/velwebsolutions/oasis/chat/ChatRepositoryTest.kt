package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ChatSettings
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.api.ConversationPage
import de.velwebsolutions.oasis.chat.api.CreatedDeviceLink
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.api.DeviceUnlockRow
import de.velwebsolutions.oasis.chat.api.MessagePage
import de.velwebsolutions.oasis.chat.api.MessageRecord
import de.velwebsolutions.oasis.chat.api.OptionalIdentity
import de.velwebsolutions.oasis.chat.api.OptionalLinkApproval
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.api.SafetyLists
import de.velwebsolutions.oasis.chat.api.UnreadCount
import de.velwebsolutions.oasis.chat.data.DefaultChatRepository
import de.velwebsolutions.oasis.chat.data.SettingValue
import de.velwebsolutions.oasis.chatcrypto.KeyGrant
import de.velwebsolutions.oasis.chatcrypto.LockedIdentity
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.PublicKeyBundle
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answers recorded from the local Laravel API (`/api/v1/chat/…`, demo member jonas, 2026-10-22, the same as iOS'
 * ChatDecodingTests) decode into the app models, the recorded envelope/grant pass the crypto core's structural checks,
 * and request bodies carry the keys the server validates (explicit nulls included).
 */
class ChatRepositoryTest {
    private fun repository(vararg stubs: StubTransport.Stub) = StubTransport(*stubs).let { it to DefaultChatRepository(it.client) }

    @Test
    fun decodesIdentityConversationsAndMessages() = runTest {
        val (_, api) = repository(
            StubTransport.Stub("GET", "chat/identity", json = IDENTITY),
            StubTransport.Stub("GET", "chat/conversations", json = CONVERSATION_PAGE),
            StubTransport.Stub("GET", "chat/conversations/2", json = CONVERSATION),
            StubTransport.Stub("GET", "chat/conversations/2/messages", json = MESSAGES),
            StubTransport.Stub("GET", "chat/conversations/2/public-keys", json = PUBLIC_KEYS),
            StubTransport.Stub("GET", "chat/conversations/2/key-grants", json = GRANTS),
            StubTransport.Stub("GET", "chat/settings", json = SETTINGS)
        )
        val identity = checkNotNull(api.identity())
        assertEquals(268_435_456L, identity.lockedIdentity?.kdf?.memlimit)
        testCrypto.verifyPublicKeys(identity.publicKeys)

        val page = api.conversations()
        assertEquals(1, page.currentPage)
        assertTrue(page.data.isNotEmpty())
        val conversation = api.conversation("2")
        assertEquals("1", conversation.createdBy)
        assertEquals(listOf("mira", "jonas"), conversation.members.map { it.username })
        assertEquals("Mira Sol", conversation.displayTitle("2"))

        val messages = api.messages("2")
        val envelope = messages.data.first().envelope
        assertEquals("2", envelope.conversationId)
        assertTrue(envelope.expiresAt.orEmpty().endsWith("Z"))
        assertNotNull(de.velwebsolutions.oasis.chatcrypto.ChatEncoding.parseEnvelopeExpiry(envelope.expiresAt))

        api.publicKeys("2").forEach { testCrypto.verifyPublicKeys(it.publicKeys) }
        val grant = api.keyGrants("2").first()
        assertEquals("2", grant.recipientId)
        assertEquals("1", grant.granterId)

        assertEquals(listOf("members", "contacts", "following", "nobody"), api.settings().choices.map { it.first })
    }

    @Test
    fun sendsExplicitNullsAndNumericIdsWhereTheServerExpectsThem() = runTest {
        val (transport, api) = repository(
            StubTransport.Stub("PUT", "chat/identity", json = IDENTITY),
            StubTransport.Stub("POST", "chat/conversations", status = 201, json = CONVERSATION),
            StubTransport.Stub("POST", "chat/blocks", json = """{"blocked":true}"""),
            StubTransport.Stub("PATCH", "chat/settings", json = SETTINGS),
            StubTransport.Stub("PATCH", "chat/conversations/2", json = CONVERSATION)
        )
        api.saveIdentity(null, PublicKeyBundle("e", "s", "b"))
        val identityBody = checkNotNull(transport.lastBody("chat/identity")).jsonObject
        assertEquals("a chat without passphrase sends locked_identity: null", JsonNull, identityBody["locked_identity"])
        assertEquals("s", identityBody.getValue("public_keys").jsonObject.getValue("signing").jsonPrimitive.content)

        api.createConversation("direct", null, listOf("2"), 7)
        val created = checkNotNull(transport.lastBody("chat/conversations")).jsonObject
        assertEquals(JsonNull, created["title"])
        assertEquals(2L, created.getValue("member_ids").jsonArray.single().jsonPrimitive.long)

        api.block("5")
        assertEquals(5L, checkNotNull(transport.lastBody("chat/blocks")).jsonObject.getValue("user_id").jsonPrimitive.long)

        api.updateSetting("friend_requests", SettingValue.Flag(false))
        assertEquals("false", checkNotNull(transport.lastBody("chat/settings")).jsonObject.getValue("value").jsonPrimitive.content)

        api.updateConversation("2", setTitle = true, title = null)
        val update = checkNotNull(transport.lastBody("chat/conversations/2")).jsonObject
        assertEquals("clearing the group name sends title: null", JsonNull, update["title"])
        assertTrue("an unchanged lifetime is not sent", "lifetime_days" !in update)
    }

    @Test
    fun mapsServerRefusalsToApiErrors() = runTest {
        val (_, api) = repository(
            StubTransport.Stub(
                "POST",
                "chat/conversations",
                status = 403,
                json = """{"message":"Mira nimmt gerade nur Nachrichten von bestimmten Personen an."}"""
            )
        )
        val error = assertThrows(ApiError.Forbidden::class.java) {
            kotlinx.coroutines.runBlocking { api.createConversation("direct", null, listOf("1"), 7) }
        }
        // The neutral line of ChatReach is shown as it is (never naming the condition).
        assertEquals(
            "Mira nimmt gerade nur Nachrichten von bestimmten Personen an.",
            de.velwebsolutions.oasis.chat.core.describeChatError(error)
        )
    }

    @Test
    fun everyEndpointSendsWhatTheServerValidates() = runTest {
        val conversation = CONVERSATION
        val (transport, api) = repository(
            StubTransport.Stub("GET", "chat/device-unlocks", json = """{"data":[$DEVICE_UNLOCK]}"""),
            StubTransport.Stub("POST", "chat/device-unlocks", status = 201, json = """{"data":$DEVICE_UNLOCK}"""),
            StubTransport.Stub("DELETE", "chat/device-unlocks/7", json = "{}"),
            StubTransport.Stub(
                "POST",
                "chat/device-links",
                status = 201,
                json = """{"data":{"id":"8","expires_at":"2026-10-22T12:10:00Z"}}"""
            ),
            StubTransport.Stub("GET", "chat/device-links/8", json = """{"data":{"locked_identity":$LOCKED,"sealed_key":"a2V5"}}"""),
            StubTransport.Stub(
                "GET",
                "chat/device-links",
                json = """{"data":[{"id":8,"public_key":"cGs","label":"Laptop","created_at":null}]}"""
            ),
            StubTransport.Stub("POST", "chat/device-links/8/approve", json = "{}"),
            StubTransport.Stub("DELETE", "chat/device-links/8", json = "{}"),
            StubTransport.Stub("GET", "chat/users", json = PUBLIC_KEYS),
            StubTransport.Stub("PATCH", "chat/settings", json = SETTINGS),
            StubTransport.Stub(
                "GET",
                "chat/safety",
                json = """{"data":{"lists":{"insult":["x"]},"tone":{"always":["digga"],"address":[]}}}"""
            ),
            StubTransport.Stub("PATCH", "chat/conversations/2", json = conversation),
            StubTransport.Stub("POST", "chat/conversations/2/members", json = conversation),
            StubTransport.Stub("DELETE", "chat/conversations/2/members/5", json = conversation),
            StubTransport.Stub("POST", "chat/conversations/2/key-grants", json = "{}"),
            StubTransport.Stub("POST", "chat/conversations/2/rotate", json = conversation),
            StubTransport.Stub("POST", "chat/conversations/2/messages", status = 201, json = "{}"),
            StubTransport.Stub("POST", "chat/conversations/2/read", json = """{"count":3}"""),
            StubTransport.Stub("POST", "chat/conversations/2/accept", json = "{}"),
            StubTransport.Stub("PUT", "chat/conversations/2/mute", json = conversation),
            StubTransport.Stub("DELETE", "chat/conversations/2/mute", json = conversation),
            StubTransport.Stub("POST", "chat/conversations/2/leave", json = "{}"),
            StubTransport.Stub("POST", "chat/conversations/2/reports", status = 201, json = "{}"),
            StubTransport.Stub("GET", "chat/identity", json = """{"data":null}"""),
            StubTransport.Stub("POST", "chat/conversations", status = 201, json = conversation)
        )
        assertNull("not set up yet", api.identity())
        val locked = Json.decodeFromString(LockedIdentity.serializer(), LOCKED)
        val row = api.deviceUnlocks().single()
        assertEquals("app-abc", row.credentialId)
        api.saveDeviceUnlock("app-abc", "salt", "Pixel", locked)
        assertEquals("Pixel", body(transport, "chat/device-unlocks").getValue("label").jsonPrimitive.content)
        api.deleteDeviceUnlock("7")
        assertEquals("8", api.requestDeviceLink("cGs", "Laptop").id)
        assertEquals("a2V5", api.deviceLink("8")?.sealedKey)
        assertEquals("Laptop", api.deviceLinks().single().label)
        api.approveDeviceLink("8", locked, "a2V5")
        assertEquals("a2V5", body(transport, "chat/device-links/8/approve").getValue("sealed_key").jsonPrimitive.content)
        api.rejectDeviceLink("8")
        assertEquals(2, api.findUser("mira").size)
        api.updateSetting("chat_invites_audience", SettingValue.Text("contacts"))
        assertEquals("contacts", body(transport, "chat/settings").getValue("value").jsonPrimitive.content)
        assertEquals(listOf("digga"), api.safety().tone.always)

        api.updateConversation("2", lifetimeDays = 30)
        val lifetime = body(transport, "chat/conversations/2")
        assertEquals(30, lifetime.getValue("lifetime_days").jsonPrimitive.int)
        assertTrue("an unchanged title is not sent", "title" !in lifetime)
        api.updateConversation("2", setTitle = true, title = "Wandergruppe")
        assertEquals("Wandergruppe", body(transport, "chat/conversations/2").getValue("title").jsonPrimitive.content)
        api.addMember("2", "5")
        assertEquals(5L, body(transport, "chat/conversations/2/members").getValue("user_id").jsonPrimitive.long)
        api.addMember("2", "kein-id")
        assertEquals(
            "an id the server would never send counts as 0",
            0L,
            body(transport, "chat/conversations/2/members").getValue("user_id").jsonPrimitive.long
        )
        api.removeMember("2", "5")
        val grant = KeyGrant(1, "2", 1, "2", "1", "dw", "cw")
        api.storeKeyGrants("2", 1, listOf(grant))
        assertEquals(1, body(transport, "chat/conversations/2/key-grants").getValue("grants").jsonArray.size)
        api.activateKey("2", 1)
        val envelope = MessageEnvelope(1, "2", 1, "1", "bg", "Yw", "cw", "2100-01-01T00:00:00Z")
        api.sendMessage("2", envelope)
        assertEquals(
            "2100-01-01T00:00:00Z",
            body(transport, "chat/conversations/2/messages").getValue("envelope").jsonObject.getValue("expires_at").jsonPrimitive.content
        )
        assertEquals(3, api.markRead("2"))
        api.accept("2")
        api.mute("2", "8h")
        assertEquals("8h", body(transport, "chat/conversations/2/mute").getValue("duration").jsonPrimitive.content)
        api.unmute("2")
        api.leave("2")
        api.report("2", envelope, "Hallo", "Spam")
        assertEquals("Spam", body(transport, "chat/conversations/2/reports").getValue("reason").jsonPrimitive.content)

        api.createConversation("group", "Wandergruppe", listOf("2", "kein-id"), 7)
        val created = body(transport, "chat/conversations")
        assertEquals("Wandergruppe", created.getValue("title").jsonPrimitive.content)
        assertEquals("ids that are no numbers are left out", 1, created.getValue("member_ids").jsonArray.size)
    }

    private fun body(transport: StubTransport, path: String) = checkNotNull(transport.lastBody(path)).jsonObject

    @Test
    fun namesTitlesAndChoicesFollowTheWeb() {
        assertEquals("@mira", ConversationMember("1", "mira").name)
        assertEquals("@mira", ConversationMember("1", "mira", "").name)
        assertEquals("Mira Sol", ConversationMember("1", "mira", "Mira Sol").name)
        val chat = Json {
            ignoreUnknownKeys = true
        }.decodeFromString(ChatConversation.serializer(), CONVERSATION.substringAfter("\"data\":").dropLast(1))
        assertEquals("Wandergruppe", chat.copy(title = "Wandergruppe").displayTitle("1"))
        assertEquals("Jonas Weber", chat.copy(title = "").displayTitle("1"))
        assertEquals("Nur du", chat.copy(members = chat.members.take(1)).displayTitle("1"))
        val settings = ChatSettings("members", true, mapOf("zzz" to "Neu", "nobody" to "Niemand", "aaa" to "Auch neu"))
        assertEquals(listOf("nobody", "aaa", "zzz"), settings.choices.map { it.first })
    }

    @Test
    fun everyModelSurvivesTheWire() {
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, text: String) {
            val value = json.decodeFromString(serializer, text)
            assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
        }
        roundTrip(OptionalIdentity.serializer(), IDENTITY)
        roundTrip(ConversationPage.serializer(), CONVERSATION_PAGE.replace("http:\\/\\/", "http://"))
        roundTrip(MessagePage.serializer(), MESSAGES)
        roundTrip(DeviceUnlockRow.serializer(), DEVICE_UNLOCK)
        roundTrip(DeviceLinkRequest.serializer(), """{"id":"8","public_key":"cGs","label":"Laptop"}""")
        roundTrip(CreatedDeviceLink.serializer(), """{"id":"8","expires_at":"2026-10-22T12:10:00Z"}""")
        roundTrip(OptionalLinkApproval.serializer(), """{"data":{"locked_identity":$LOCKED,"sealed_key":"a2V5"}}""")
        roundTrip(ChatSettings.serializer(), SETTINGS)
        roundTrip(SafetyLists.serializer(), "{}")
        roundTrip(UnreadCount.serializer(), "{}")
        roundTrip(PublicUser.serializer(), """{"id":1,"username":"mira"}""")
        // The defaults the server may leave out.
        assertEquals(1, ConversationPage(emptyList()).lastPage)
        assertNull(MessagePage(emptyList()).nextBeforeId)
        assertTrue(ChatSettings("members", true).choices.isEmpty())
        assertTrue(SafetyLists().tone.always.isEmpty())
        assertEquals(0, UnreadCount().count)
        assertNull(OptionalIdentity().data)
        assertNull(OptionalLinkApproval().data)
        assertNull(PublicUser("1", "mira").keyChangedAt)
        assertEquals("member", ConversationMember("1", "mira").role)
        assertEquals("8", CreatedDeviceLink("8").id)
        val envelope = MessageEnvelope(1, "2", 1, "1", "bg", "Yw", "cw")
        assertEquals(envelope, MessagePage(listOf(MessageRecord(envelope))).data.single().envelope)
        val chat =
            ChatConversation(
                "7",
                "direct",
                createdBy = "1",
                lifetimeDays = 7,
                keyVersion = 1,
                rotationRequired = false,
                members = emptyList(),
                serverTime = ""
            )
        assertNull(chat.title ?: chat.unread ?: chat.request ?: chat.muted ?: chat.mutedUntil)
    }

    private companion object {
        const val LOCKED =
            """{"version":1,"kdf":{"alg":"argon2id13","opslimit":1,"memlimit":8192,"salt":"c2FsdA"},"nonce":"bm9uY2U","ciphertext":"Y2lwaGVy","publicKeys":{"encryption":"ZQ","signing":"cw"}}"""

        const val DEVICE_UNLOCK =
            """{"id":"7","credential_id":"app-abc","prf_salt":"salt","label":"Pixel","locked_identity":$LOCKED,"created_at":"2026-10-22T12:00:00Z"}"""

        const val IDENTITY =
            """{"data":{"user_id":"2","locked_identity":{"kdf":{"alg":"argon2id13","salt":"sDJE2q_xmba5mzxpv-GTuQ","memlimit":268435456,"opslimit":3},"nonce":"LuwkEjRjJjVhmd5KgfvsGehq_Isl0xNp","version":1,"ciphertext":"TeAExBzy29BEv9tMfKtbeqYKHWZiQRKRjV9rncZPgoc-bgLxg8jY3Bgzaues3lvc6OLeDObCI4BohwRKE-ACo9P_8kdVscRPvdHSboAOfAJ-p_y_zfhyil1ignyrKa6QsQX5ZXV8yFpwjCV_38JJ0Q","publicKeys":{"signing":"QKOAQ4XwTxWRFBGoLRgAYKFn0EfCK3V9a8-JF0C0UEw","encryption":"i9pWFUXpoIhiNfGdKu-nbenDKJzcx54TspVcAaizBjo"}},"public_keys":{"binding":"efJNppi_e3Y5iAxZpAGTc6YsJugO8_mZBMxQ_4ZIHQMcfXT4lR6PrwaFUTTnDWX2LLlMqi2u5Yn1DRyb8dzdBg","signing":"QKOAQ4XwTxWRFBGoLRgAYKFn0EfCK3V9a8-JF0C0UEw","encryption":"i9pWFUXpoIhiNfGdKu-nbenDKJzcx54TspVcAaizBjo"},"key_changed_at":null}}"""

        const val CONVERSATION_PAGE =
            """{"current_page":1,"data":[{"id":"3","type":"direct","title":null,"created_by":"1","lifetime_days":7,"key_version":1,"rotation_required":false,"members":[{"user_id":"1","username":"mira","display_name":"Mira Sol","role":"owner","joined_at":"2026-10-22T16:39:10.000000Z"},{"user_id":"2","username":"jonas","display_name":"Jonas Weber","role":"member","joined_at":"2026-10-22T16:39:10.000000Z"}],"server_time":"2026-10-22T16:41:07Z","unread":false,"request":false},{"id":"1","type":"direct","title":null,"created_by":"1","lifetime_days":7,"key_version":1,"rotation_required":false,"members":[{"user_id":"1","username":"mira","display_name":"Mira Sol","role":"owner","joined_at":"2026-10-22T16:30:26.000000Z"},{"user_id":"2","username":"jonas","display_name":"Jonas Weber","role":"member","joined_at":"2026-10-22T16:30:25.000000Z"}],"server_time":"2026-10-22T16:41:07Z","unread":true,"request":false}],"first_page_url":"http:\/\/localhost\/api\/v1\/chat\/conversations?page=1","from":1,"last_page":1,"next_page_url":null,"path":"http:\/\/localhost\/api\/v1\/chat\/conversations","per_page":30,"prev_page_url":null,"to":2,"total":2}"""

        const val CONVERSATION =
            """{"data":{"id":"2","type":"direct","title":null,"created_by":"1","lifetime_days":7,"key_version":1,"rotation_required":false,"members":[{"user_id":"1","username":"mira","display_name":"Mira Sol","role":"owner","joined_at":"2026-10-22T16:33:38.000000Z"},{"user_id":"2","username":"jonas","display_name":"Jonas Weber","role":"member","joined_at":"2026-10-22T16:33:38.000000Z"}],"server_time":"2026-10-22T16:41:07Z","request":false}}"""

        const val MESSAGES =
            """{"data":[{"id":"4","envelope":{"v":1,"nonce":"_tO6QLph3zK4ddnCvwP1oLybBnoGATRI","sender_id":"1","signature":"nOSiMkgNSTX0PqEaMXJyG2dKEv6lwW8kOY4QZ8XNKdXIAqwj8v17zkd9wQzy3lF3m0VCtucAflhd6M53UcmiAg","ciphertext":"bnpNZGLdwbmIPj-ujTxx1cnV6IRvzwm5mMjQ2C2Vk93zqS9cwAz42lG0dYc","expires_at":"2026-10-29T16:35:05Z","key_version":1,"conversation_id":"2"},"created_at":"2026-10-22T16:35:05.000000Z","expires_at":"2026-10-29T16:35:05.000000Z"}],"next_before_id":null}"""

        const val PUBLIC_KEYS =
            """{"data":[{"id":"1","username":"mira","display_name":"Mira Sol","public_keys":{"binding":"mh0bNWWHdZzTG4cIX9hM8z7LXNARMWWBTPaecgr11VrRwWJcQwPEzKpwiSOM674ZAqqFvfqH8vzIThmE9HanBA","signing":"g2MT9ydjvaQV6NP2z2rPSO8t2crUr2HgLaxYx3TsAxM","encryption":"TxQVYsJR9y6l-V1KaWgJ4UH8Rbi0RHm_v8sPWH4NNEw"},"key_changed_at":null},{"id":"2","username":"jonas","display_name":"Jonas Weber","public_keys":{"binding":"efJNppi_e3Y5iAxZpAGTc6YsJugO8_mZBMxQ_4ZIHQMcfXT4lR6PrwaFUTTnDWX2LLlMqi2u5Yn1DRyb8dzdBg","signing":"QKOAQ4XwTxWRFBGoLRgAYKFn0EfCK3V9a8-JF0C0UEw","encryption":"i9pWFUXpoIhiNfGdKu-nbenDKJzcx54TspVcAaizBjo"},"key_changed_at":null}]}"""

        const val GRANTS =
            """{"data":[{"v":1,"signature":"79ZlYu51TN5ApUiIY7r356TePmCf7h5lRlsF4RJpmdRgrnKejDIZyUKa5U4y45aGBeqpKXt6wFt9P_1M2s3aBA","granter_id":"1","key_version":1,"wrapped_key":"LKaqfUjenpVefY3jPYrRC1miZdyx6LJoUAhG2hgzXFen4tCSKE5ZP7dkBR1Ppe5x0nuZcnolK4Vhdxoen0Yf9nazFNoDDuNx1JY_XekfSdU","recipient_id":"2","conversation_id":"2"}]}"""

        const val SETTINGS =
            """{"chat_invites_audience":"members","friend_requests":true,"choices":{"members":"Alle Mitglieder","contacts":"Freund:innen und Menschen, denen du folgst","following":"Menschen, denen du folgst","nobody":"Niemand"}}"""
    }
}
