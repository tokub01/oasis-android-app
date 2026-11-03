package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.TokenProvider
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * An in-memory stand-in for the chat API (routes/api/v1/members/chat.php) with the server's essential rules, so two app
 * clients (Mira and Jonas) can run the whole protocol: identities, a conversation, owner key distribution, signed
 * messages, device links and biometric copies. The bearer token "t{userId}" selects the member (iOS: FakeChatServer).
 */
class FakeChatServer : Interceptor {
    class Conversation(
        val id: Int,
        val type: String,
        var title: String?,
        val createdBy: Int,
        var lifetimeDays: Int,
        var keyVersion: Int = 1,
        var rotationRequired: Boolean = true,
        val members: MutableList<Int>,
        val accepted: MutableSet<Int>,
        /** Per member: `muted_until` as the server returns it ("" = muted for good). */
        val mutedUntil: MutableMap<Int, String> = mutableMapOf(),
        /** A lying server: names this account as `created_by` (with the role owner, if a member) instead of the starter. */
        var shownOwner: Int? = null,
        /** A lying server: lists the owner with the role member. */
        var ownerRoleHidden: Boolean = false,
        /** Members who left or were removed: the directory still lists them (`GET public-keys`). */
        val former: MutableSet<Int> = mutableSetOf(),
        /** Per member: when they last had the chat open (`last_read_at`). */
        val lastReadAt: MutableMap<Int, String> = mutableMapOf(),
        /** The newest message's time (`last_activity_at`). */
        var lastActivityAt: String? = null,
        /** Members for whom the chat is unread. */
        val unread: MutableSet<Int> = mutableSetOf()
    ) {
        /** The owner the server names. */
        val owner: Int get() = shownOwner ?: createdBy
    }

    val users = mapOf(1 to "mira", 2 to "jonas", 3 to "aylin")

    /** Profile statuses shown under a 1:1 partner's name. */
    val statuses = mutableMapOf<Int, String>()
    val identities = mutableMapOf<Int, JsonObject>()
    val conversations = mutableMapOf<Int, Conversation>()
    val grants = mutableMapOf<Int, MutableList<JsonObject>>()
    val messages = mutableMapOf<Int, MutableList<Pair<Int, JsonObject>>>()

    /** `message` or `reaction` by message id, as the client sent it (`"kind"`). */
    val kinds = mutableMapOf<Int, String>()
    val deviceUnlocks = mutableListOf<Pair<Int, JsonObject>>()
    val deviceLinks = mutableMapOf<Int, MutableMap<String, JsonElement>>()
    val reports = mutableListOf<JsonObject>()

    /** What `GET suggestions` answers (round J: people to write first). */
    var suggestions: List<JsonObject> = emptyList()
    var suggestionsStatus = 200

    /** An encrypted upload (ChatAttachment): parts as they arrived, the message it went with and each recipient's answer. */
    class Upload(
        val id: Int,
        val conversationId: Int,
        val uploader: Int,
        val size: Int,
        val parts: Int,
        val viewOnce: Boolean,
        val received: MutableList<ByteArray> = mutableListOf(),
        var completed: Boolean = false,
        var messageId: Int? = null,
        var deleted: Boolean = false,
        /** Per recipient: accepted, declined or viewed. */
        val answers: MutableMap<Int, String> = mutableMapOf()
    ) {
        val ciphertext: ByteArray get() = received.fold(ByteArray(0)) { all, part -> all + part }
    }

    val uploads = mutableMapOf<Int, Upload>()

    /** The part size the fake announces (the server's is 5 MiB; small here, so files go in several parts). */
    var partBytes = 1_000
    val blocks = mutableListOf<Pair<Int, Int>>()
    private var nextId = 100

    /** Answers that replace the normal route once each, keyed "METHOD path" (path below chat/, e.g. "GET identity"). */
    val failures = mutableMapOf<String, Pair<Int, String>>()

    /** Requests being answered right now (tests wait for 0). */
    val inFlight = java.util.concurrent.atomic.AtomicInteger()

    /** Called for every request before it is answered ("METHOD path"), e.g. to lock a vault mid-flight. */
    var onRequest: (String) -> Unit = {}

    /**
     * The server's clock, now by default: expiry is judged by the later of server and device time (ServerClock.now), so
     * a fixed date in the past would let every message expire on the device.
     */
    var serverTime: String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    fun client(userId: Int): ApiClient {
        val tokens = object : TokenProvider {
            override fun currentToken() = "t$userId"

            override fun onUnauthenticated() = Unit
        }
        return ApiClient(
            ApiConfig("http://oasis.test", "OasisApp/1.0 (Android; Android 16)"),
            tokens,
            OkHttpClient.Builder().addInterceptor(this).build()
        )
    }

    fun me(userId: Int, canParticipate: Boolean = true) = Me(
        id = userId,
        username = users.getValue(userId),
        displayName = users.getValue(userId).replaceFirstChar(Char::uppercase),
        initials = "X",
        email = "x@example.tld",
        canParticipate = canParticipate
    )

    private fun newId() = ++nextId

    private fun name(user: Int) = users.getValue(user).replaceFirstChar(Char::uppercase)

    private fun conversationData(conversation: Conversation, user: Int) = buildJsonObject {
        put("id", conversation.id.toString())
        put("type", conversation.type)
        put("title", conversation.title)
        put("created_by", conversation.owner.toString())
        put("lifetime_days", conversation.lifetimeDays)
        put("key_version", conversation.keyVersion)
        put("rotation_required", conversation.rotationRequired)
        put("ended", conversation.type == "direct" && conversation.members.size < 2)
        put(
            "members",
            buildJsonArray {
                conversation.members.forEach { member ->
                    add(
                        buildJsonObject {
                            put("user_id", member.toString())
                            put("username", users.getValue(member))
                            put("display_name", name(member))
                            put("role", if (member == conversation.owner && !conversation.ownerRoleHidden) "owner" else "member")
                            put("joined_at", "2026-10-22T11:00:00.000000Z")
                            statuses[member]?.let { put("status", it) }
                            // ChatAvatar: initials in the colour, a photo for Jonas (2).
                            put(
                                "avatar",
                                buildJsonObject {
                                    put("initials", name(member).take(1))
                                    put("avatar_color", "sun")
                                    put("photo_url", if (member == 2) "/api/v1/network/profiles/jonas/photo/a.jpg" else null)
                                }
                            )
                        }
                    )
                }
            }
        )
        put("server_time", serverTime)
        put("request", user !in conversation.accepted)
        put("unread", user in conversation.unread)
        put("last_read_at", conversation.lastReadAt[user])
        put("last_activity_at", conversation.lastActivityAt)
        put("muted", conversation.mutedUntil[user] != null)
        put("muted_until", conversation.mutedUntil[user]?.takeIf { it.isNotEmpty() })
    }

    private fun publicUser(id: Int) = buildJsonObject {
        put("id", id.toString())
        put("username", users.getValue(id))
        put("display_name", name(id))
        put("public_keys", identities[id]?.get("public_keys") ?: JsonNull)
        put("key_changed_at", keyChangedAt[id])
    }

    /** Pages of the conversation list (every page lists all conversations, so page 2 repeats page 1). */
    var lastPage = 1

    /** `key_changed_at` per member (replaced identities). */
    val keyChangedAt = mutableMapOf<Int, String>()

    private fun data(value: JsonElement) = buildJsonObject { put("data", value) }

    override fun intercept(chain: Interceptor.Chain): Response {
        inFlight.incrementAndGet()
        try {
            return answer(chain)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    @Synchronized
    private fun answer(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val user = request.header("Authorization")?.removePrefix("Bearer t")?.toIntOrNull() ?: 0
        val path = request.url.encodedPath.substringAfter("/api/v1/chat/")
        val raw = request.body?.let { Buffer().also(it::writeTo).readByteArray() } ?: ByteArray(0)
        val binary = request.body?.contentType()?.subtype == "octet-stream"
        val text = if (binary) "" else raw.toString(Charsets.UTF_8)
        val body = if (text.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
        lastPart = raw.takeIf { binary }
        val query = request.url.queryParameterNames.associateWith { request.url.queryParameter(it) }
        val key = "${request.method} $path"
        onRequest(key)
        download(request.method, path, user)?.takeIf { key !in failures }?.let { bytes ->
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("fake")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
        val (status, json) = failures.remove(key)?.let { (code, text) -> code to Json.parseToJsonElement(text) }
            ?: route(request.method, path.split("/").filter { it.isNotEmpty() }, user, body, query)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("fake")
            .body(json.toString().toResponseBody("application/json".toMediaType()))
            .build()
    }

    private var lastPart: ByteArray? = null

    /** Replaces the ciphertext the server hands out for an upload (a tampering server). */
    val tampered = mutableMapOf<Int, ByteArray>()

    /**
     * `GET conversations/{c}/attachments/{id}`: the ciphertext, when this member may download it (docs, "Anhänge"): the
     * sender their own files except view-once ones once sent, recipients after accepting.
     */
    private fun download(method: String, path: String, user: Int): ByteArray? {
        val parts = path.split("/")
        if (method != "GET" || parts.size != 4 || parts[2] != "attachments") return null
        val upload = uploads[parts[3].toIntOrNull()] ?: return null
        val mayDownload = upload.completed && !upload.deleted &&
            if (upload.uploader == user) upload.messageId == null || !upload.viewOnce else upload.answers[user] == "accepted"
        return if (mayDownload) tampered[upload.id] ?: upload.ciphertext else null
    }

    private fun attachmentState(upload: Upload, user: Int) = buildJsonObject {
        put("id", upload.id.toString())
        put("view_once", upload.viewOnce)
        put("status", if (upload.uploader == user) "sent" else upload.answers[user] ?: "pending")
        put("available", !upload.deleted)
    }

    private fun notFound() = 404 to buildJsonObject { put("message", "Not found") }

    private fun attachmentRoute(
        method: String,
        parts: List<String>,
        user: Int,
        body: JsonObject,
        conversation: Conversation
    ): Pair<Int, JsonElement> {
        if (method == "POST" && parts.size == 3) {
            val size = body.getValue("size").jsonPrimitive.int
            val upload = Upload(
                newId(),
                conversation.id,
                user,
                size,
                (size + partBytes - 1) / partBytes,
                body["view_once"]?.jsonPrimitive?.boolean == true
            )
            uploads[upload.id] = upload
            return 201 to data(
                buildJsonObject {
                    put("id", upload.id.toString())
                    put("parts", upload.parts)
                    put("part_bytes", partBytes)
                }
            )
        }
        val upload = uploads[parts.getOrNull(3)?.toIntOrNull()]?.takeIf { it.conversationId == conversation.id } ?: return notFound()
        return when {
            method == "PUT" && parts.getOrNull(4) == "parts" -> {
                val index = parts[5].toInt()
                val bytes = lastPart ?: ByteArray(0)
                val expected = if (index == upload.parts - 1) upload.size - index * partBytes else partBytes
                if (index != upload.received.size || bytes.size != expected) {
                    422 to buildJsonObject { put("message", "Dieser Teil des Anhangs passt nicht.") }
                } else {
                    upload.received += bytes
                    200 to data(buildJsonObject { put("received_parts", upload.received.size) })
                }
            }

            method == "POST" && parts.getOrNull(4) == "complete" -> {
                upload.completed = upload.received.size == upload.parts
                200 to data(buildJsonObject { put("id", upload.id.toString()) })
            }

            method == "POST" && parts.getOrNull(4) in setOf("accept", "decline", "viewed") -> {
                val answer = parts[4]
                val current = upload.answers[user]
                val allowed = upload.uploader != user && !upload.deleted && current != "declined" && current != "viewed" &&
                    (answer != "viewed" || (upload.viewOnce && current == "accepted"))
                if (!allowed) return notFound()
                upload.answers[user] = mapOf("accept" to "accepted", "decline" to "declined", "viewed" to "viewed").getValue(answer)
                val recipients = conversation.members.filter { it != upload.uploader }
                val done = recipients.all { upload.answers[it] == "declined" || upload.answers[it] == "viewed" }
                if (done && (upload.viewOnce || recipients.all { upload.answers[it] == "declined" })) upload.deleted = true
                200 to data(attachmentState(upload, user))
            }

            else -> notFound()
        }
    }

    private fun states(messageId: Int, user: Int) = JsonArray(
        uploads.values.filter {
            it.messageId == messageId
        }.map { attachmentState(it, user) }
    )

    private fun route(
        method: String,
        parts: List<String>,
        user: Int,
        body: JsonObject,
        query: Map<String, String?>
    ): Pair<Int, JsonElement> {
        val first = parts.firstOrNull()
        when {
            method == "GET" && first == "identity" -> return 200 to data(identities[user] ?: JsonNull)

            method == "PUT" && first == "identity" -> {
                val bundle = body.getValue("public_keys").jsonObject
                val previous = identities[user]?.get("public_keys")?.jsonObject
                if (previous != null && previous["signing"] != bundle["signing"]) {
                    deviceUnlocks.removeAll { it.first == user }
                } else if (body["locked_identity"] is JsonNull && previous != null && deviceUnlocks.none { it.first == user }) {
                    return 422 to buildJsonObject {
                        put("message", "x")
                        put(
                            "errors",
                            buildJsonObject {
                                put(
                                    "locked_identity",
                                    JsonArray(
                                        listOf(JsonPrimitive("Richte zuerst Fingerabdruck ein, sonst kommst du nicht mehr an deine Chats."))
                                    )
                                )
                            }
                        )
                    }
                }
                identities[user] = buildJsonObject {
                    put("user_id", user.toString())
                    put("locked_identity", body["locked_identity"] ?: JsonNull)
                    put("public_keys", bundle)
                    put("key_changed_at", JsonNull)
                }
                return 200 to data(identities.getValue(user))
            }

            method == "GET" && first == "suggestions" -> return suggestionsStatus to data(JsonArray(suggestions))

            method == "GET" && first == "safety" -> return 200 to Json.parseToJsonElement(
                """{"data":{"lists":{"insult":["vollidiot"],"sexual":[],"hate":[],"threat":[],"extremism":[]},"tone":{"always":["digga"],"address":["alter"]}}}"""
            )

            method == "GET" && first == "users" -> {
                val name = query["username"]
                val found = users.filter { it.value == name && identities[it.key] != null }.map { publicUser(it.key) }
                return 200 to data(JsonArray(found))
            }

            method == "GET" && first == "device-unlocks" ->
                return 200 to data(JsonArray(deviceUnlocks.filter { it.first == user }.map { it.second }))

            method == "POST" && first == "device-unlocks" -> {
                val row = JsonObject(
                    body + mapOf("id" to JsonPrimitive(newId().toString()), "created_at" to JsonPrimitive("2026-10-22T12:00:00.000000Z"))
                )
                deviceUnlocks += user to row
                return 201 to data(row)
            }

            method == "DELETE" && first == "device-unlocks" && parts.size == 2 -> {
                deviceUnlocks.removeAll { it.second["id"]?.jsonPrimitive?.content == parts[1] && it.first == user }
                return 200 to data(JsonNull)
            }

            method == "POST" && first == "device-links" && parts.size == 1 -> {
                val id = newId()
                deviceLinks[id] =
                    mutableMapOf(
                        "user" to JsonPrimitive(user),
                        "public_key" to body.getValue("public_key"),
                        "label" to body.getValue("label")
                    )
                return 201 to data(
                    buildJsonObject {
                        put("id", id.toString())
                        put("expires_at", "2026-10-22T12:10:00.000000Z")
                    }
                )
            }

            method == "GET" && first == "device-links" && parts.size == 1 -> {
                val open = deviceLinks.filter {
                    it.value["user"]?.jsonPrimitive?.int == user && it.value["approval"] == null
                }.map { (id, link) ->
                    buildJsonObject {
                        put("id", id.toString())
                        put("public_key", link.getValue("public_key"))
                        put("label", link.getValue("label"))
                        put("created_at", "2026-10-22T12:00:00.000000Z")
                    }
                }
                return 200 to data(JsonArray(open))
            }

            method == "GET" && first == "device-links" && parts.size == 2 -> {
                val id = parts[1].toInt()
                val link = deviceLinks[id]
                if (link == null || link["user"]?.jsonPrimitive?.int != user) return 404 to buildJsonObject { put("message", "") }
                val approval = link["approval"] ?: return 200 to data(JsonNull)
                deviceLinks.remove(id)
                return 200 to data(approval)
            }

            method == "POST" && first == "device-links" && parts.size == 3 -> {
                deviceLinks[parts[1].toInt()]?.put("approval", body)
                return 200 to data(JsonNull)
            }

            method == "DELETE" && first == "device-links" && parts.size == 2 -> {
                deviceLinks.remove(parts[1].toInt())
                return 200 to data(JsonNull)
            }

            method == "GET" && first == "settings" -> return 200 to Json.parseToJsonElement(
                """{"chat_invites_audience":"members","friend_requests":true,"choices":{"nobody":"Niemand","members":"Alle Mitglieder","following":"Menschen, denen du folgst","contacts":"Freund:innen"}}"""
            )

            method == "PATCH" && first == "settings" -> return 200 to Json.parseToJsonElement(
                """{"chat_invites_audience":"contacts","friend_requests":false,"choices":{"nobody":"Niemand","contacts":"Freund:innen"}}"""
            )

            method == "POST" && first == "blocks" -> {
                blocks += user to body.getValue("user_id").jsonPrimitive.int
                return 200 to buildJsonObject { put("blocked", true) }
            }

            method == "GET" && first == "conversations" && parts.size == 1 -> {
                val mine = conversations.values.filter { user in it.members }.sortedByDescending { it.id }
                return 200 to buildJsonObject {
                    put("current_page", if (query["page"] == "2") 2 else 1)
                    put("last_page", lastPage)
                    put("data", JsonArray(mine.map { conversationData(it, user) }))
                }
            }

            method == "POST" && first == "conversations" && parts.size == 1 -> {
                val ids = body.getValue("member_ids").jsonArray.map { it.jsonPrimitive.int }
                val conversation = Conversation(
                    id = newId(),
                    type = body.getValue("type").jsonPrimitive.content,
                    title = (body["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    createdBy = user,
                    lifetimeDays = body.getValue("lifetime_days").jsonPrimitive.int,
                    members = (listOf(user) + ids).toMutableList(),
                    accepted = mutableSetOf(user)
                )
                conversations[conversation.id] = conversation
                return 201 to data(conversationData(conversation, user))
            }
        }
        if (first != "conversations" || parts.size < 2) return 404 to buildJsonObject { put("message", "Not found") }
        val id = parts[1].toIntOrNull() ?: return 404 to buildJsonObject { put("message", "Not found") }
        val conversation = conversations[id] ?: return 404 to buildJsonObject { put("message", "Not found") }
        if (user !in conversation.members) return 403 to buildJsonObject { put("message", "This action is unauthorized.") }
        if (parts.getOrNull(2) == "attachments") return attachmentRoute(method, parts, user, body, conversation)
        return when (method to parts.getOrElse(2) { "" }) {
            "GET" to "" -> 200 to data(conversationData(conversation, user))

            "GET" to "public-keys" -> 200 to data(JsonArray((conversation.members + conversation.former).distinct().map { publicUser(it) }))

            "GET" to "key-grants" ->
                200 to data(JsonArray(grants[id].orEmpty().filter { it["recipient_id"]?.jsonPrimitive?.content == user.toString() }))

            "POST" to "key-grants" -> {
                if (user != conversation.createdBy || body["key_version"]?.jsonPrimitive?.intOrNull != conversation.keyVersion) {
                    409 to buildJsonObject { put("message", "stale") }
                } else {
                    // A pending batch is replaced as a whole (ConversationKeys::distribute).
                    val stored = grants.getOrPut(id) { mutableListOf() }
                    stored.removeAll { it["key_version"]?.jsonPrimitive?.intOrNull == conversation.keyVersion }
                    stored += body.getValue("grants").jsonArray.map { it.jsonObject }
                    200 to data(conversationData(conversation, user))
                }
            }

            "POST" to "rotate" -> {
                conversation.rotationRequired = false
                200 to data(conversationData(conversation, user))
            }

            "GET" to "messages" -> {
                val page = messages[id].orEmpty().reversed().map { (messageId, envelope) ->
                    buildJsonObject {
                        put("id", messageId.toString())
                        put("kind", kinds[messageId] ?: "message")
                        put("envelope", envelope)
                        put("created_at", "2026-10-22T12:00:00.000000Z")
                        put("expires_at", envelope.getValue("expires_at"))
                        put("attachments", states(messageId, user))
                    }
                }
                200 to buildJsonObject {
                    put("data", JsonArray(page))
                    put("next_before_id", JsonNull)
                }
            }

            "POST" to "messages" -> {
                val envelope = body["envelope"] as? JsonObject
                if (conversation.rotationRequired || envelope == null ||
                    envelope["key_version"]?.jsonPrimitive?.intOrNull != conversation.keyVersion
                ) {
                    409 to buildJsonObject { put("message", "rotation") }
                } else {
                    val ids = body["attachment_ids"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }.orEmpty()
                    val linked = ids.mapNotNull { uploads[it] }.filter { it.uploader == user && it.completed && it.messageId == null }
                    val kind = body["kind"]?.jsonPrimitive?.content ?: "message"
                    // A control message (reaction, pin, vote) carries no files (docs/oasis-chat.md).
                    if (linked.size != ids.size || (kind != "message" && ids.isNotEmpty())) {
                        return 422 to buildJsonObject {
                            put("message", "x")
                            put(
                                "errors",
                                buildJsonObject {
                                    put(
                                        "attachment_ids",
                                        JsonArray(
                                            listOf(JsonPrimitive("Ein Anhang ist nicht mehr verfügbar. Bitte füge ihn noch einmal hinzu."))
                                        )
                                    )
                                }
                            )
                        }
                    }
                    val messageId = newId()
                    linked.forEach { it.messageId = messageId }
                    messages.getOrPut(id) { mutableListOf() } += messageId to envelope
                    kinds[messageId] = kind
                    conversation.accepted += user
                    201 to data(
                        buildJsonObject {
                            put("id", messageId.toString())
                            put("kind", kind)
                            put("envelope", envelope)
                            put("created_at", serverTime)
                            put("expires_at", envelope.getValue("expires_at"))
                            put("attachments", states(messageId, user))
                        }
                    )
                }
            }

            "POST" to "members" -> {
                conversation.members += body.getValue("user_id").jsonPrimitive.int
                conversation.keyVersion += 1
                conversation.rotationRequired = true
                200 to data(conversationData(conversation, user))
            }

            "DELETE" to "members" -> {
                conversation.members.remove(parts[3].toInt())
                conversation.former += parts[3].toInt()
                conversation.keyVersion += 1
                conversation.rotationRequired = true
                200 to data(conversationData(conversation, user))
            }

            "POST" to "read" -> {
                conversation.unread -= user
                200 to buildJsonObject { put("count", 0) }
            }

            "POST" to "accept" -> {
                conversation.accepted += user
                200 to buildJsonObject { put("accepted", true) }
            }

            "POST" to "leave" -> {
                conversation.members.remove(user)
                conversation.former += user
                conversation.keyVersion += 1
                conversation.rotationRequired = true
                200 to buildJsonObject { put("left", true) }
            }

            "PUT" to "mute" -> {
                // "1w" ends a week from now like on the server, so the app reads it back as "1w" on any day. The fixed
                // "8h" end is in the past, which still reads back as "8h", and ChatFlowTest prints it.
                val week = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.SECONDS).toString()
                val ends = mapOf("8h" to "2026-10-22T20:00:00.000000Z", "1w" to week, "always" to "")
                val until = ends[body["duration"]?.jsonPrimitive?.content]
                if (until == null) {
                    422 to buildJsonObject {
                        put("message", "invalid")
                        put(
                            "errors",
                            buildJsonObject {
                                put("duration", JsonArray(listOf(JsonPrimitive("Bitte wähle, wie lange der Chat stumm sein soll."))))
                            }
                        )
                    }
                } else {
                    conversation.mutedUntil[user] = until
                    200 to data(conversationData(conversation, user))
                }
            }

            "DELETE" to "mute" -> {
                conversation.mutedUntil.remove(user)
                200 to data(conversationData(conversation, user))
            }

            "POST" to "reports" -> {
                reports += body
                201 to data(
                    buildJsonObject {
                        put("id", "1")
                        put("conversation_id", id.toString())
                        put("status", "received")
                    }
                )
            }

            else -> 404 to buildJsonObject { put("message", "Not found") }
        }
    }
}
