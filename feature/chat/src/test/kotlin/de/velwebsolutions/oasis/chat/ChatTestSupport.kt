package de.velwebsolutions.oasis.chat

import android.net.Uri
import de.velwebsolutions.oasis.chat.core.AttachmentKind
import de.velwebsolutions.oasis.chat.core.AttachmentView
import de.velwebsolutions.oasis.chat.core.ChatContext
import de.velwebsolutions.oasis.chat.core.ChatMedia
import de.velwebsolutions.oasis.chat.core.ChatPreferences
import de.velwebsolutions.oasis.chat.core.ChatVault
import de.velwebsolutions.oasis.chat.core.DeviceSecretStore
import de.velwebsolutions.oasis.chat.core.DraftAttachment
import de.velwebsolutions.oasis.chat.core.MemoryChatPreferences
import de.velwebsolutions.oasis.chat.core.MemoryDeviceSecretStore
import de.velwebsolutions.oasis.chat.data.ChatRepository
import de.velwebsolutions.oasis.chat.data.DefaultChatRepository
import de.velwebsolutions.oasis.chatcrypto.ChatCrypto
import de.velwebsolutions.oasis.chatcrypto.JvmSodium
import de.velwebsolutions.oasis.chatcrypto.KdfLimits
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** The real crypto core on lazysodium-java (libsodium is initialised once per test JVM). */
val testCrypto: ChatCrypto by lazy { ChatCrypto(JvmSodium()) }

/** Contexts of members against one [FakeChatServer]: each with its own vault, like two devices. */
class ChatTestSupport(val server: FakeChatServer = FakeChatServer()) {
    /** Timers of the vault never fire in these tests (no idle lock while a test waits for the fake server). */
    private val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun context(
        userId: Int,
        secrets: DeviceSecretStore = MemoryDeviceSecretStore(available = false),
        canParticipate: Boolean = true,
        preferences: ChatPreferences = MemoryChatPreferences(),
        vault: ChatVault = ChatVault(vaultScope, idleLock = Duration.INFINITE),
        cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
        repository: (ChatRepository) -> ChatRepository = { it },
        media: ChatMedia = FakeChatMedia()
    ): ChatContext {
        vault.prepare(userId.toString())
        return ChatContext(
            repository = repository(DefaultChatRepository(server.client(userId))),
            vault = vault,
            crypto = testCrypto,
            secrets = secrets,
            preferences = preferences,
            me = server.me(userId, canParticipate),
            media = media,
            cryptoDispatcher = cryptoDispatcher
        )
    }
}

/**
 * A member whose chat is set up (passphrase copy with the minimal Argon2id costs, so tests stay fast) and open.
 * [locked] leaves the vault locked after publishing the keys.
 */
fun ChatTestSupport.member(
    userId: Int,
    secrets: DeviceSecretStore = MemoryDeviceSecretStore(available = false),
    passphrase: String = TEST_PASSPHRASE,
    locked: Boolean = false,
    preferences: ChatPreferences = MemoryChatPreferences(),
    media: ChatMedia = FakeChatMedia()
): ChatContext {
    val context = context(userId, secrets = secrets, preferences = preferences, media = media)
    val identity = testCrypto.createIdentity()
    val copy = testCrypto.lockIdentity(identity, passphrase, KdfLimits.Minimum)
    val stored = runBlocking { context.repository.saveIdentity(copy, testCrypto.exportPublicKeys(identity)) }
    context.vault.setStoredIdentity(stored)
    if (locked) identity.wipe() else context.vault.unlock(identity, context.vault.generation)
    return context
}

/** The passphrase of [member]. */
const val TEST_PASSPHRASE = "Palme Regen Kompass Tee"

/** Runs crypto on [Dispatchers.Default] after [before] – e.g. a lock that happens while Argon2id works. */
class HookedDispatcher(private val before: () -> Unit) : CoroutineDispatcher() {
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        before()
        Dispatchers.Default.dispatch(context, block)
    }
}

/**
 * Waits in real time (at most a few seconds) until [done] holds and the fake server answered every request, running
 * what the answers resumed on the test dispatcher of [scheduler].
 */
suspend fun settle(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, server: FakeChatServer, done: () -> Boolean) {
    kotlinx.coroutines.withContext(Dispatchers.Default) {
        kotlinx.coroutines.delay(30)
        kotlinx.coroutines.withTimeout(5_000) {
            while (!(done() && server.inFlight.get() == 0)) {
                kotlinx.coroutines.delay(10)
                kotlinx.coroutines.withContext(Dispatchers.Main) { scheduler.runCurrent() }
            }
        }
    }
    scheduler.runCurrent()
}

/**
 * The device side of attachments without a device: picked files and recordings come from the test, decrypted files
 * become [AttachmentView.Playable] with the decrypted bytes kept in [presented].
 */
class FakeChatMedia : ChatMedia {
    /** What a pick turns into (a 3-byte photo by default), or throw an AttachmentProblem. */
    var prepared: (
        Uri,
        Long
    ) -> DraftAttachment = { _, id -> DraftAttachment(id, AttachmentKind.Image, "image/jpeg", byteArrayOf(1, 2, 3), 2, 1) }
    var recordingStarts = true
    var recorded: ByteArray? = byteArrayOf(9, 9)
    var onLimit: (() -> Unit)? = null
    val presented = mutableListOf<Pair<AttachmentKind, ByteArray>>()
    val discarded = mutableListOf<AttachmentView>()
    var cancels = 0
    var clears = 0

    override suspend fun prepare(uri: Uri, localId: Long): DraftAttachment = prepared(uri, localId)

    /** What a "Datei" pick turns into (a PDF by default). */
    var preparedFile: (Uri, Long) -> DraftAttachment = { _, id ->
        DraftAttachment(id, AttachmentKind.File, "application/pdf", byteArrayOf(5, 6), name = "Rechnung.pdf")
    }

    override suspend fun prepareFile(uri: Uri, localId: Long): DraftAttachment = preparedFile(uri, localId)

    /** Shared files become files of their name, or throw an AttachmentProblem for names starting with "kaputt". */
    override suspend fun prepareShared(shared: de.velwebsolutions.oasis.core.share.SharedFile, localId: Long): DraftAttachment {
        if (shared.name.startsWith("kaputt")) throw de.velwebsolutions.oasis.chat.core.AttachmentProblem("Die Datei ließ sich nicht lesen.")
        return DraftAttachment(localId, AttachmentKind.File, shared.mime, shared.file.readBytes(), name = shared.name)
    }

    override fun startRecording(onLimit: () -> Unit): Boolean {
        this.onLimit = onLimit
        return recordingStarts
    }

    override suspend fun finishRecording(localId: Long): DraftAttachment? =
        recorded?.let { DraftAttachment(localId, AttachmentKind.Audio, "audio/mp4", it, durationMs = 1_500) }

    override fun cancelRecording() {
        cancels++
    }

    override suspend fun present(kind: AttachmentKind, data: ByteArray, mime: String, name: String?): AttachmentView {
        presented += kind to data
        return AttachmentView.Playable(File("entschluesselt-${presented.size}"), null)
    }

    override fun discard(view: AttachmentView) {
        discarded += view
    }

    override fun clear() {
        clears++
    }
}

/** Attachment steps that only write down what was asked ("picked …", "load 7 retry", …). */
fun attachmentActions(calls: MutableList<String>) = de.velwebsolutions.oasis.chat.ui.AttachmentActions(
    onPicked = { uri, asFile -> calls += "${if (asFile) "file" else "picked"} $uri" },
    onRemove = { calls += "remove $it" },
    onViewOnce = { id, on -> calls += "viewOnce $id $on" },
    onStartRecording = { calls += "record" },
    onFinishRecording = { calls += "finish" },
    onCancelRecording = { calls += "cancel" },
    onLoad = { attachment, retry -> calls += "load ${attachment.id}${if (retry) " retry" else ""}" },
    onAnswer = { _, attachment, accept -> calls += "${if (accept) "accept" else "decline"} ${attachment.id}" },
    onOpenViewOnce = { _, attachment -> calls += "open ${attachment.id}" },
    onCloseViewOnce = { calls += "close" },
    onCancelViewOnce = { calls += "cancelViewOnce" }
)
