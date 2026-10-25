package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.chat.api.ChatConversation
import de.velwebsolutions.oasis.chat.api.ConversationMember
import de.velwebsolutions.oasis.chat.api.DeviceLinkRequest
import de.velwebsolutions.oasis.chat.api.PublicUser
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMessageItem
import de.velwebsolutions.oasis.chat.core.MemoryDeviceSecretStore
import de.velwebsolutions.oasis.chat.ui.ChatSettingsState
import de.velwebsolutions.oasis.chat.ui.ChatSettingsViewModel
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsState
import de.velwebsolutions.oasis.chat.ui.ConversationDetailsViewModel
import de.velwebsolutions.oasis.chat.ui.ConversationKind
import de.velwebsolutions.oasis.chat.ui.ConversationListViewModel
import de.velwebsolutions.oasis.chat.ui.NewConversationViewModel
import de.velwebsolutions.oasis.chat.ui.ReportModel
import de.velwebsolutions.oasis.chatcrypto.MessageEnvelope
import de.velwebsolutions.oasis.chatcrypto.createLinkKeyPair
import de.velwebsolutions.oasis.core.realtime.RealtimeEvent
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The view models of the list, a new chat, the details, the settings and the report dialog in every branch. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreensViewModelsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val support = ChatTestSupport()
    private val server get() = support.server
    private val oops = "Da ist etwas schiefgegangen. Versuch es gleich noch einmal."

    private fun failing(key: String, status: Int = 500, json: String = """{"message":"kaputt"}""") {
        server.failures[key] = status to json
    }

    private suspend fun chat(owner: ChatContext, vararg others: Int, type: String = "direct", title: String? = null) =
        owner.repository.createConversation(type, title, others.map(Int::toString), 7).id

    // region List

    @Test
    fun theListPagesSortsRequestsAndShowsMeta() = runTest {
        val mira = support.member(1)
        support.member(2)
        support.member(3)
        val direct = chat(mira, 2)
        chat(mira, 2, 3, type = "group")
        val list = ConversationListViewModel(mira, FakeRealtime())
        assertEquals("1", list.myId)
        assertTrue(list.canParticipate)
        list.loadMore()
        failing("GET conversations")
        list.load()
        assertEquals(oops, list.state.value.error)
        list.load()
        val (group, one) = list.state.value.conversations
        assertEquals("Gruppe · 3 Menschen · löscht nach 7 Tagen · wartet auf neuen Schlüssel", list.meta(group))
        assertEquals("Zu zweit · löscht nach 7 Tagen · wartet auf neuen Schlüssel", list.meta(one))
        assertEquals("Jonas", list.title(one))
        assertEquals(direct, one.id)

        // Page 2 and a request; a conversation listed twice is shown once.
        val page = """{"current_page":1,"last_page":2,"data":[]}"""
        failing("GET conversations", 200, page)
        list.load()
        assertEquals(2, list.state.value.nextPage)
        val jonasView = support.member(2)
        val asRequest = ConversationListViewModel(jonasView, FakeRealtime())
        asRequest.load()
        assertEquals(2, asRequest.state.value.requests.size)
        val paged = ConversationListViewModel(mira, FakeRealtime())
        failing("GET conversations", 200, page)
        paged.load()
        failing("GET conversations")
        paged.loadMore()
        assertEquals(oops, paged.state.value.error)
        paged.loadMore()
        paged.loadMore()
        // A second page that repeats chats already shown adds nothing.
        server.lastPage = 2
        paged.load()
        paged.loadMore()
        assertEquals(2, paged.state.value.conversations.size)
        // Once the owner distributed the key, the meta line no longer waits for one.
        ConversationViewModelTest.open(mira, direct)
        paged.load()
        assertEquals("Zu zweit · löscht nach 7 Tagen", paged.meta(paged.state.value.conversations.first { it.id == direct }))
        paged.lock()
        assertFalse(mira.vault.unlocked)
    }

    @Test
    fun theListLetsOtherDevicesIn() = runTest {
        val mira = support.member(1)
        val list = ConversationListViewModel(mira, FakeRealtime())
        failing("GET device-links")
        list.refreshLinkRequests()
        val laptop = support.context(1).devices.requestLink()
        list.refreshLinkRequests()
        list.refreshLinkRequests()
        val entry = list.state.value.linkRequests.single()
        assertEquals(laptop.check, entry.check)
        failing("POST device-links/${laptop.id}/approve")
        list.approve(entry.request)
        assertEquals(oops, list.state.value.actionError)
        list.dismissActionError()
        list.refreshLinkRequests()
        list.approve(list.state.value.linkRequests.single().request)
        assertEquals("„Android (Oasis-App)“ ist freigegeben.", list.state.value.status)
        list.dismissStatus()

        val second = support.context(1).devices.requestLink()
        val third = support.context(1).devices.requestLink()
        list.refreshLinkRequests()
        list.approve(list.state.value.linkRequests.single { it.request.id == third.id }.request)
        val request = list.state.value.linkRequests.single { it.request.id == second.id }.request
        failing("DELETE device-links/${second.id}")
        list.reject(request)
        assertEquals(oops, list.state.value.actionError)
        support.context(1).devices.requestLink()
        list.refreshLinkRequests()
        list.reject(request)
        assertEquals("Anfrage abgelehnt.", list.state.value.status)
        assertEquals(1, list.state.value.linkRequests.size)
        mira.vault.lock()
        list.refreshLinkRequests()
        list.reject(DeviceLinkRequest("1", "k", "x"))
    }

    @Test
    fun theListFollowsNewActivityAndPollsWhileOffline() = runBlocking {
        val mira = support.member(1)
        chat(mira)
        val live = FakeRealtime(connected = false)
        val list = ConversationListViewModel(mira, live)
        val updates = launch(Dispatchers.Main) { list.liveUpdates() }
        val scheduler = mainRule.dispatcher.scheduler
        scheduler.runCurrent()
        live.events.emit(RealtimeEvent("InboxActivity", buildJsonObject { put("kind", "hint") }))
        live.events.emit(RealtimeEvent("InboxActivity", buildJsonObject { put("kind", "chat") }))
        settle { list.state.value.loaded }
        assertTrue(list.state.value.loaded)
        repeat(5) {
            scheduler.advanceTimeBy(4_001)
            scheduler.runCurrent()
            settle()
        }
        live.isConnected.value = true
        repeat(5) {
            scheduler.advanceTimeBy(4_001)
            scheduler.runCurrent()
            settle()
        }
        updates.cancel()
        assertEquals(1, list.state.value.conversations.size)
    }

    private suspend fun settle(done: () -> Boolean = { true }) = settle(mainRule.dispatcher.scheduler, server, done)

    // endregion

    // region New chat

    @Test
    fun aNewChatChecksPeopleKindAndTitle() = runTest {
        val mira = support.member(1)
        support.member(2)
        support.member(3)
        val new = NewConversationViewModel(null, mira)
        assertTrue(new.canParticipate)
        new.applyPreselection()
        new.addFromInput()
        assertNull(new.create())
        assertEquals("Füge mindestens eine Person hinzu.", new.state.value.error)
        new.setUsername("niemand")
        new.addFromInput()
        assertEquals("@niemand gibt es nicht oder hat den Chat noch nicht eingerichtet.", new.state.value.error)
        new.setUsername("mira")
        new.addFromInput()
        assertEquals("Du bist automatisch dabei.", new.state.value.error)
        new.setUsername("jonas")
        new.addFromInput()
        assertEquals("@jonas hinzugefügt.", new.state.value.status)
        new.dismissStatus()
        new.setUsername("aylin")
        new.addFromInput()
        assertEquals("Ein Chat zu zweit hat genau eine andere Person. Wähle „Gruppe“ für mehr.", new.state.value.error)
        new.setKind(ConversationKind.Group)
        new.addFromInput()
        new.setUsername("jonas")
        new.addFromInput()
        assertEquals(listOf("jonas", "aylin"), new.state.value.selected.map { it.username })
        new.setKind(ConversationKind.Direct)
        assertNull(new.create())
        assertEquals("Ein Chat zu zweit hat genau eine andere Person.", new.state.value.error)
        new.setKind(ConversationKind.Group)
        new.setTitle("  Wandergruppe ")
        new.setLifetimeDays(30)
        failing("POST conversations", 403, """{"message":"Aylin nimmt gerade keine Nachrichten an."}""")
        assertNull(new.create())
        assertEquals("Aylin nimmt gerade keine Nachrichten an.", new.state.value.error)
        val id = checkNotNull(new.create())
        assertEquals("Wandergruppe", server.conversations.getValue(id.toInt()).title)
        assertEquals(30, server.conversations.getValue(id.toInt()).lifetimeDays)
        new.setTitle("   ")
        assertEquals(null, server.conversations.getValue(checkNotNull(new.create()).toInt()).title)
        new.remove(PublicUser("3", "aylin"))
        new.remove(PublicUser("9", "unbekannt"))
        assertEquals(listOf("jonas"), new.state.value.selected.map { it.username })
        new.setKind(ConversationKind.Direct)
        new.setTitle("Wird ignoriert")
        assertNull(server.conversations.getValue(checkNotNull(new.create()).toInt()).title)

        failing("GET users")
        new.setKind(ConversationKind.Group)
        new.setUsername("aylin")
        new.addFromInput()
        assertEquals(oops, new.state.value.error)
    }

    @Test
    fun aGroupHasAtMostFiftyPeopleAndAPreselectionIsAddedOnce() = runTest {
        val mira = support.member(1)
        support.member(2)
        val everyone = support.context(1, vault = mira.vault, repository = { real ->
            object : de.velwebsolutions.oasis.chat.data.ChatRepository by real {
                override suspend fun findUser(username: String) = listOf(PublicUser(username.removePrefix("u"), username))
            }
        })
        val full = NewConversationViewModel(null, everyone)
        full.setKind(ConversationKind.Group)
        repeat(NewConversationViewModel.MAX_GROUP_OTHERS + 1) {
            full.setUsername("u${it + 10}")
            full.addFromInput()
        }
        assertEquals(NewConversationViewModel.MAX_GROUP_OTHERS, full.state.value.selected.size)
        assertEquals("Eine Gruppe hat höchstens 50 Menschen.", full.state.value.error)
        val preselected = NewConversationViewModel("jonas", mira)
        preselected.applyPreselection()
        preselected.applyPreselection()
        assertEquals(1, preselected.state.value.selected.size)
        val notAgain = NewConversationViewModel("jonas", mira)
        notAgain.setUsername("jonas")
        notAgain.addFromInput()
        notAgain.applyPreselection()
        assertEquals(1, notAgain.state.value.selected.size)
    }

    // endregion

    // region Details

    private fun conversation(muted: Boolean? = null, mutedUntil: String? = null, type: String = "direct", createdBy: String = "1") =
        ChatConversation("7", type, null, createdBy, 7, 1, false, emptyList(), "", muted = muted, mutedUntil = mutedUntil)

    @Test
    fun detailsStateDescribesMuteAndRoles() {
        assertFalse(ConversationDetailsState().isGroup)
        assertFalse(ConversationDetailsState().isMuted)
        assertTrue(ConversationDetailsState(conversation()).canLeave)
        assertFalse(ConversationDetailsState(conversation(type = "group"), isOwner = true).canLeave)
        assertTrue(ConversationDetailsState(conversation(type = "group")).canLeave)
        assertEquals(
            "Du bekommst Mitteilungen zu neuen Nachrichten, so wie unter Hinweise einstellen festgelegt.",
            ConversationDetailsState(conversation(muted = false)).muteState
        )
        assertEquals("Dauerhaft stumm.", ConversationDetailsState(conversation(muted = true)).muteState)
        assertEquals(
            "Stumm bis Fr., 24. Oktober um 07:53 Uhr.",
            ConversationDetailsState(conversation(muted = true, mutedUntil = "2025-10-24T05:53:00Z")).muteState
        )
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertEquals("always", ConversationDetailsViewModel.muteDuration(null, now))
        assertEquals("8h", ConversationDetailsViewModel.muteDuration("2026-10-22T20:00:00Z", now))
        assertEquals("1w", ConversationDetailsViewModel.muteDuration("2026-10-29T12:00:00Z", now))
        assertEquals("1w", ConversationDetailsViewModel.muteDuration("2026-10-29T12:00:00Z"))
        assertEquals(
            "Mira Sol (@mira) – du · hat den Chat gestartet",
            ConversationDetailsViewModel.label(ConversationMember("1", "mira", "Mira Sol", "owner"), true)
        )
        assertEquals("@jonas", ConversationDetailsViewModel.label(ConversationMember("2", "jonas"), false))
        assertEquals("@jonas", ConversationDetailsViewModel.label(ConversationMember("2", "jonas", ""), false))
    }

    @Test
    fun theDetailsMuteRenameAddRemoveAndLeave() = runTest {
        val mira = support.member(1)
        support.member(3)
        val id = chat(mira, 2, type = "group", title = "Garten")
        val details = ConversationDetailsViewModel(id, mira)
        failing("GET conversations/$id")
        details.load()
        assertEquals(oops, details.state.value.loadFailed)
        val aylinKeys = testCrypto.verifyPublicKeys(testCrypto.exportPublicKeys(testCrypto.createIdentity()))
        mira.vault.rememberPublicKeys("1", aylinKeys)
        server.keyChangedAt[2] = "2026-10-18T12:00:00Z"
        details.load()
        assertNull(details.state.value.loadFailed)
        assertEquals("Garten", details.state.value.title)
        val rows = details.state.value.members
        assertNull(rows.first { it.isMe }.safetyNumber)
        assertEquals("Keine gültigen Schlüssel", rows.first { !it.isMe }.safetyNumber)
        assertEquals("Neue Schlüssel – Nummer erneut vergleichen.", rows.first { !it.isMe }.warning)
        assertTrue(rows.first { !it.isMe }.removable)

        details.setMuteDuration("1w")
        details.mute()
        assertEquals("Der Chat ist stummgeschaltet.", details.state.value.status)
        assertEquals("1w", details.state.value.muteDuration)
        details.dismissStatus()
        details.setMuteDuration("nie")
        details.mute()
        assertEquals("Bitte wähle, wie lange der Chat stumm sein soll.", details.state.value.error)
        details.dismissError()
        details.unmute()
        details.setLifetimeDays(1)
        failing("PATCH conversations/$id", 404, """{"message":"Not found"}""")
        details.saveLifetime()
        details.setTitle("  ")
        failing("PATCH conversations/$id", 200, """{"data":${conversationJson(id)}}""")
        details.saveTitle()
        details.setTitle("Neu")
        failing("PATCH conversations/$id", 200, """{"data":${conversationJson(id)}}""")
        details.saveTitle()

        details.addMember()
        details.setUsername("niemand")
        details.addMember()
        assertEquals("@niemand gibt es nicht oder hat den Chat noch nicht eingerichtet.", details.state.value.addError)
        details.setUsername("aylin")
        failing("POST conversations/$id/members", 200, """{"data":${conversationJson(id, members = "1,2,3")}}""")
        details.addMember()
        assertEquals("@aylin hinzugefügt. Der Chat bekommt einen neuen Schlüssel.", details.state.value.status)
        details.setUsername("aylin")
        failing("POST conversations/$id/members")
        details.addMember()
        assertEquals(oops, details.state.value.addError)
        failing("DELETE conversations/$id/members/3", 200, """{"data":${conversationJson(id)}}""")
        details.remove(details.state.value.members.first { it.username == "aylin" })
        assertEquals("@aylin entfernt. Der Chat bekommt einen neuen Schlüssel.", details.state.value.status)

        val jonas = support.member(2)
        val asMember = ConversationDetailsViewModel(id, jonas)
        asMember.load()
        assertFalse(asMember.state.value.members.first { it.username == "mira" }.removable)
        failing("POST conversations/$id/leave")
        assertFalse(asMember.leave())
        assertTrue(asMember.leave())
        assertEquals("Du hast den Chat verlassen.", jonas.vault.announcement.value)
    }

    private fun conversationJson(id: String, members: String = "1,2") = buildJsonObject {
        put("id", id)
        put("type", "group")
        put("title", "Garten")
        put("created_by", "1")
        put("lifetime_days", 7)
        put("key_version", 1)
        put("rotation_required", false)
        put("server_time", server.serverTime)
        put("muted", true)
        put("muted_until", "2026-10-22T20:00:00Z")
        put(
            "members",
            kotlinx.serialization.json.JsonArray(
                members.split(",").map { member ->
                    buildJsonObject {
                        put("user_id", member)
                        put("username", server.users.getValue(member.toInt()))
                        put("role", if (member == "1") "owner" else "member")
                    }
                }
            )
        )
    }

    // endregion

    // region Settings

    @Test
    fun settingsStateNamesTheBackupAndWhenToConfirm() {
        assertEquals("Reserve-Passphrase festlegen (optional)", ChatSettingsState().backupTitle)
        assertEquals("Chat-Passphrase ändern", ChatSettingsState(hasPassphrase = true).backupTitle)
        assertFalse(ChatSettingsState(hasPassphrase = true).canRemovePassphrase)
        assertFalse(ChatSettingsState().thisDeviceIsSetUp)
        assertTrue(ChatSettingsState(ownDeviceId = "1").thisDeviceIsSetUp)
        val row = de.velwebsolutions.oasis.chat.api.DeviceUnlockRow(
            "1",
            "app-x",
            "Pixel",
            testCrypto.lockForTest(),
            "2026-10-22T12:00:00Z"
        )
        assertTrue(ChatSettingsState(hasPassphrase = true, devices = listOf(row)).canRemovePassphrase)
        assertFalse(ChatSettingsState(devices = listOf(row)).canRemovePassphrase)
        assertTrue(ChatSettingsState(devices = listOf(row)).removalNeedsConfirmation)
        assertFalse(ChatSettingsState(devices = listOf(row, row)).removalNeedsConfirmation)
        assertFalse(ChatSettingsState(hasPassphrase = true).removalNeedsConfirmation)
        assertFalse(ChatSettingsState().removalNeedsConfirmation)
    }

    @Test
    fun theSettingsSaveChoicesDevicesAndThePassphrase() = runTest {
        val secrets = MemoryDeviceSecretStore(available = true)
        val mira = support.member(1, secrets = secrets)
        val settings = ChatSettingsViewModel(mira)
        settings.setAudience("contacts")
        failing("GET settings")
        failing("GET device-unlocks")
        settings.load()
        assertNull(settings.state.value.settings)
        assertEquals(oops, settings.state.value.error)
        settings.dismissError()
        settings.load()
        settings.setAudience("contacts")
        assertEquals("Gespeichert.", settings.state.value.status)
        settings.dismissStatus()
        val saved = settings.state.value.settings
        failing("PATCH settings")
        settings.setFriendRequests(true)
        assertEquals("a refused change goes back", saved, settings.state.value.settings)
        server.onRequest = { if (it == "PATCH settings") runBlocking { settings.setFriendRequests(false) } }
        settings.setFriendRequests(false)
        server.onRequest = {}

        secrets.denies = true
        settings.addThisDevice()
        assertTrue(settings.state.value.error.orEmpty().startsWith("Das hat nicht geklappt."))
        secrets.denies = false
        failing("POST device-unlocks")
        settings.addThisDevice()
        assertEquals(oops, settings.state.value.error)
        settings.addThisDevice()
        assertTrue(settings.state.value.thisDeviceIsSetUp)
        val row = settings.state.value.devices.single()
        failing("DELETE device-unlocks/${row.id}")
        settings.remove(row)
        assertEquals(oops, settings.state.value.error)

        settings.saveBackup()
        assertEquals("Die Passphrase braucht mindestens 12 Zeichen.", settings.state.value.backupError)
        settings.setPassphrase("Palme Regen Kompass Kaffee")
        settings.setConfirmation("Palme Regen Kompass Kaffee")
        failing("PUT identity")
        settings.saveBackup()
        assertEquals(oops, settings.state.value.backupError)
        settings.saveBackup()
        assertEquals("Deine Chat-Passphrase ist gespeichert.", settings.state.value.status)
        failing("PUT identity")
        settings.removePassphrase()
        assertEquals(oops, settings.state.value.error)
        settings.removePassphrase()
        assertFalse(settings.state.value.hasPassphrase)
        assertTrue(settings.state.value.removalNeedsConfirmation)
        settings.remove(row)
        assertEquals("„Android (Oasis-App)“ entfernt.", settings.state.value.status)

        mira.vault.lock()
        settings.setPassphrase("Palme Regen Kompass Kaffee")
        settings.setConfirmation("Palme Regen Kompass Kaffee")
        settings.saveBackup()
        val openWithoutCopy = support.context(3)
        openWithoutCopy.vault.unlock(testCrypto.createIdentity(), openWithoutCopy.vault.generation)
        ChatSettingsViewModel(openWithoutCopy).apply {
            setPassphrase("Palme Regen Kompass Kaffee")
            setConfirmation("Palme Regen Kompass Kaffee")
            saveBackup()
        }
        val notSetUp = ChatSettingsViewModel(support.context(2))
        notSetUp.removePassphrase()
        notSetUp.setPassphrase("Palme Regen Kompass Kaffee")
        notSetUp.setConfirmation("Palme Regen Kompass Kaffee")
        notSetUp.saveBackup()
        clear(settings)
        assertEquals("", settings.state.value.passphrase)
    }

    // endregion

    // region Report

    @Test
    fun reportsSendTheReasonAndOfferToBlock() = runTest {
        val mira = support.member(1)
        val envelope = MessageEnvelope(1, "7", 1, "2", "n", "c", "s")
        val item = ChatMessageItem("s", envelope, "2", null, ChatMessageItem.State.Readable, "Hallo")
        server.conversations[7] =
            de.velwebsolutions.oasis.chat.FakeChatServer.Conversation(
                7,
                "direct",
                null,
                1,
                7,
                members = mutableListOf(1, 2),
                accepted = mutableSetOf(1)
            )
        val report = ReportModel(mira, "7", item, "Jonas")
        assertEquals("Jonas: Hallo", report.state.value.quote)
        report.send()
        assertEquals("Wähl bitte aus, was nicht stimmt.", report.state.value.error)
        report.setCategory(ReportModel.categories.first())
        failing("POST conversations/7/reports")
        report.send()
        assertEquals(oops, report.state.value.error)
        report.send()
        assertEquals(
            ReportModel.categories.first(),
            server.reports.last()["reason"]?.let {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            }
        )
        val withComment = ReportModel(mira, "7", item, "Jonas")
        withComment.setCategory(ReportModel.categories.last())
        withComment.setComment("  Seit Tagen.  ")
        withComment.send()
        assertTrue(withComment.state.value.sent)
        assertNull(withComment.state.value.quote)
        failing("POST blocks")
        withComment.block()
        assertEquals(oops, withComment.state.value.error)
        withComment.block()
        assertTrue(withComment.state.value.blocked)
    }

    // endregion
}

private fun de.velwebsolutions.oasis.chatcrypto.ChatCrypto.lockForTest() =
    lockIdentity(createIdentity(), "Palme Regen Kompass Tee", de.velwebsolutions.oasis.chatcrypto.KdfLimits.Minimum)
