package de.velwebsolutions.oasis.chat

import de.velwebsolutions.oasis.core.model.Me
import de.velwebsolutions.oasis.core.network.ApiClient
import de.velwebsolutions.oasis.core.network.ApiConfig
import de.velwebsolutions.oasis.core.network.TokenProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val mutedUntil: MutableMap<Int, String> = mutableMapOf()
    )

    val users = mapOf(1 to "mira", 2 to "jonas", 3 to "aylin")
    val identities = mutableMapOf<Int, JsonObject>()
    val conversations = mutableMapOf<Int, Conversation>()
    val grants = mutableMapOf<Int, MutableList<JsonObject>>()
    val messages = mutableMapOf<Int, MutableList<Pair<Int, JsonObject>>>()
    val deviceUnlocks = mutableListOf<Pair<Int, JsonObject>>()
    val deviceLinks = mutableMapOf<Int, MutableMap<String, JsonElement>>()
    val reports = mutableListOf<JsonObject>()
    val blocks = mutableListOf<Pair<Int, Int>>()
    private var nextId = 100

    /** Answers that replace the normal route once each, keyed "METHOD path" (path below chat/, e.g. "GET identity"). */
    val failures = mutableMapOf<String, Pair<Int, String>>()

    /** Requests being answered right now (tests wait for 0). */
    val inFlight = java.util.concurrent.atomic.AtomicInteger()

    /** Called for every request before it is answered ("METHOD path"), e.g. to lock a vault mid-flight. */
    var onRequest: (String) -> Unit = {}
    var serverTime = "2026-10-22T12:00:00Z"

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
        put("created_by", conversation.createdBy.toString())
        put("lifetime_days", conversation.lifetimeDays)
        put("key_version", conversation.keyVersion)
        put("rotation_required", conversation.rotationRequired)
        put(
            "members",
            buildJsonArray {
                conversation.members.forEach { member ->
                    add(
                        buildJsonObject {
                            put("user_id", member.toString())
                            put("username", users.getValue(member))
                            put("display_name", name(member))
                            put("role", if (member == conversation.createdBy) "owner" else "member")
                            put("joined_at", "2026-10-22T11:00:00.000000Z")
                        }
                    )
                }
            }
        )
        put("server_time", serverTime)
        put("request", user !in conversation.accepted)
        put("unread", false)
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
        val text = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        val body = if (text.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
        val query = request.url.queryParameterNames.associateWith { request.url.queryParameter(it) }
        val key = "${request.method} $path"
        onRequest(key)
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
        return when (method to parts.getOrElse(2) { "" }) {
            "GET" to "" -> 200 to data(conversationData(conversation, user))

            "GET" to "public-keys" -> 200 to data(JsonArray(conversation.members.map { publicUser(it) }))

            "GET" to "key-grants" ->
                200 to data(JsonArray(grants[id].orEmpty().filter { it["recipient_id"]?.jsonPrimitive?.content == user.toString() }))

            "POST" to "key-grants" -> {
                if (user != conversation.createdBy || body["key_version"]?.jsonPrimitive?.intOrNull != conversation.keyVersion) {
                    409 to buildJsonObject { put("message", "stale") }
                } else {
                    grants.getOrPut(id) { mutableListOf() } += body.getValue("grants").jsonArray.map { it.jsonObject }
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
                        put("envelope", envelope)
                        put("created_at", "2026-10-22T12:00:00.000000Z")
                        put("expires_at", envelope.getValue("expires_at"))
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
                    messages.getOrPut(id) { mutableListOf() } += newId() to envelope
                    conversation.accepted += user
                    201 to data(
                        buildJsonObject {
                            put("id", "1")
                            put("envelope", envelope)
                            put("created_at", serverTime)
                            put("expires_at", envelope.getValue("expires_at"))
                        }
                    )
                }
            }

            "POST" to "read" -> 200 to buildJsonObject { put("count", 0) }

            "POST" to "accept" -> {
                conversation.accepted += user
                200 to buildJsonObject { put("accepted", true) }
            }

            "POST" to "leave" -> {
                conversation.members.remove(user)
                conversation.keyVersion += 1
                conversation.rotationRequired = true
                200 to buildJsonObject { put("left", true) }
            }

            "PUT" to "mute" -> {
                val ends = mapOf("8h" to "2026-10-22T20:00:00.000000Z", "1w" to "2026-10-29T12:00:00.000000Z", "always" to "")
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
