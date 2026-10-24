package de.velwebsolutions.oasis.design.navigation

import java.net.URI
import java.net.URLDecoder

/**
 * Maps Oasis web URLs (the same links used in mails, push payloads and shared posts) to app routes, so a link to
 * get-oasis.com opens the matching screen. Unknown paths return null and are opened in the browser instead.
 */
object DeepLink {
    private val Username = Regex("[a-z0-9_]{3,30}")
    private val ConversationId = Regex("[1-9]\\d{0,15}")

    fun route(url: String): AppRoute? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        // App URLs (`oasis://mitgliedschaft?checkout=…` after a payment) carry the first path segment as host.
        val appHost = if (uri.scheme == "oasis") listOfNotNull(uri.host) else emptyList()
        val parts = appHost + (uri.rawPath ?: "").split("/").filter { it.isNotEmpty() }.map { decode(it) }
        val query = (uri.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore("=")) to decode(it.substringAfter("=", ""))
        }

        // The chat is one web page routed by hash: #c/{id}, #c/{id}/info, #neu, #neu/{username} (ui/route.js).
        val fragment = uri.rawFragment
        if (parts == listOf("app", "nachrichten") && fragment != null) return chatRoute(decode(fragment))

        when (parts.size) {
            0 -> return null

            1 -> return when (parts[0]) {
                "app" -> NetworkRoute.Source
                "moderation" -> StaffRoute.Moderation
                "admin" -> StaffRoute.AdminDashboard
                "mitglied-werden" -> MembershipRoute.Checkout
                "mitgliedschaft" -> MembershipRoute.Overview
                else -> null
            }
        }

        val third = parts.getOrNull(2)
        return when (parts[0]) {
            "tropfen" -> {
                val id = parts[1].toIntOrNull() ?: return null
                when (third) {
                    "bearbeiten" -> NetworkRoute.EditPost(id)
                    "weitertragen" -> NetworkRoute.CarryPost(id)
                    else -> NetworkRoute.Post(id)
                }
            }

            "oasen" -> when (third) {
                "bearbeiten" -> NetworkRoute.EditOasis(parts[1])
                "schliessen" -> NetworkRoute.CloseOasis(parts[1])
                "statistik" -> NetworkRoute.OasisStatistics(parts[1])
                else -> NetworkRoute.Oasis(parts[1])
            }

            "profil" -> NetworkRoute.Profile(parts[1])

            "einladung" -> NetworkRoute.Invitation(parts[1])

            "themen" -> NetworkRoute.Tag(parts[1])

            "moderation" -> when (parts[1]) {
                "cases" -> third?.toIntOrNull()?.let { StaffRoute.ModerationCase(it) }
                "complaints" -> third?.toIntOrNull()?.let { StaffRoute.ComplaintReview(it) }
                "roles" -> StaffRoute.Roles
                "sperren" -> StaffRoute.Suspensions
                else -> null
            }

            "admin" -> when (parts[1]) {
                "abstimmungen" -> StaffRoute.AdminVotes
                "zahlungen" -> StaffRoute.AdminPayments
                "datenpannen" -> StaffRoute.AdminBreaches
                "system" -> StaffRoute.AdminSystem
                else -> null
            }

            "app" -> appRoute(parts[1], parts.drop(2), query)

            else -> null
        }
    }

    /** The chat's hash routes; anything else is the conversation list (like `parseRoute` in resources/js/chat/ui/route.js). */
    private fun chatRoute(fragment: String): ChatRoute {
        val segments = fragment.removePrefix("/").split("/")
        return when (segments.first()) {
            "neu" -> when {
                segments.size == 2 && Username.matches(segments[1]) -> ChatRoute.NewConversationWith(segments[1])
                segments.size == 1 -> ChatRoute.NewConversation
                else -> ChatRoute.Conversations
            }

            "c" -> {
                val id = segments.getOrNull(1)?.takeIf { ConversationId.matches(it) }?.toIntOrNull() ?: return ChatRoute.Conversations
                when {
                    segments.size == 3 && segments[2] == "info" -> ChatRoute.Details(id)
                    segments.size == 2 -> ChatRoute.Conversation(id)
                    else -> ChatRoute.Conversations
                }
            }

            else -> ChatRoute.Conversations
        }
    }

    private fun appRoute(section: String, rest: List<String>, query: Map<String, String>): AppRoute? = when (section) {
        "entdecken" -> NetworkRoute.Discover

        "durchblaettern" -> NetworkRoute.Swipe

        "freunde" -> NetworkRoute.Friends

        "oasen" -> if (rest.firstOrNull() == "neu") NetworkRoute.CreateOasis else NetworkRoute.Oases

        "profil" -> NetworkRoute.EditProfile

        "statistik" -> NetworkRoute.Statistics

        "einladen" -> NetworkRoute.Invitations

        "suche" -> NetworkRoute.Search(query["q"])

        "gemerkt" -> NetworkRoute.Bookmarks

        "nachrichten" -> (query["c"]?.toIntOrNull() ?: query["conversation"]?.toIntOrNull())
            ?.let { ChatRoute.Conversation(it) } ?: ChatRoute.Conversations

        "hinweise" -> if (rest.firstOrNull() == "einstellungen") LiveRoute.Settings else LiveRoute.Inbox

        "einstellungen" -> AccountRoute.Settings

        "privatsphaere" -> when (rest.firstOrNull()) {
            "check" -> AccountRoute.PrivacyCheckup
            "woerter" -> AccountRoute.HiddenWords
            "geraete" -> AccountRoute.Devices
            else -> AccountRoute.Privacy
        }

        "willkommen" -> AccountRoute.Welcome

        "sicherheit" -> AccountRoute.Safety

        "melden" -> AccountRoute.Report(query["url"])

        "begriffe" -> AccountRoute.Glossary

        "gesperrt" -> AccountRoute.Suspended

        "abstimmungen" -> AccountRoute.Votes

        "moderation" ->
            if (rest.firstOrNull() == "complaint") rest.getOrNull(1)?.toIntOrNull()?.let { AccountRoute.Complaint(it) } else null

        "mitgliedschaft" -> when (rest.firstOrNull()) {
            "portal" -> MembershipRoute.Portal
            "kuendigen" -> MembershipRoute.Cancel
            "widerrufen" -> MembershipRoute.Withdraw
            "rechnungen" -> rest.getOrNull(1)?.toIntOrNull()?.let { MembershipRoute.Invoice(it) }
            else -> MembershipRoute.Overview
        }

        else -> null
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
