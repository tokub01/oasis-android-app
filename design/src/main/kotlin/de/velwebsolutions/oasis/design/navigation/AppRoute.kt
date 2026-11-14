package de.velwebsolutions.oasis.design.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every screen of the app (AppRoute on iOS). Screens navigate by value (`navigator.open(NetworkRoute.Post(1))`); the app
 * module maps each route to the screen of the owning feature module, which never depend on each other.
 */
@Serializable
sealed interface AppRoute : NavKey

/** A public website page (Impressum, Datenschutz, AGB, Hilfe …), opened in a Custom Tab. */
@Serializable
data class WebRoute(val url: String) : AppRoute

/** Screens of the network area (Posts, Crowds, profiles, discovery, search). Owned by `:feature:network`. */
@Serializable
sealed interface NetworkRoute : AppRoute {
    /** Start – the home feed (/app). */
    @Serializable data object Source : NetworkRoute

    /** Entdecken (/app/entdecken). */
    @Serializable data object Discover : NetworkRoute

    /** Durchblättern (/app/durchblaettern). */
    @Serializable data object Swipe : NetworkRoute

    /** One Post with replies (/posts/{id}). */
    @Serializable data class Post(val id: Int) : NetworkRoute

    /** Edit a Post (/posts/{id}/bearbeiten). */
    @Serializable data class EditPost(val id: Int) : NetworkRoute

    /** Teilen – share a Post into a crowd or the own feed (/posts/{id}/teilen). */
    @Serializable data class CarryPost(val id: Int, val quote: Boolean = true) : NetworkRoute

    /** Write a new Post, optionally into a crowd or as a reply. */
    @Serializable data class Compose(val oasisSlug: String? = null, val replyTo: Int? = null) : NetworkRoute

    /** Crowds overview (/app/crowds). */
    @Serializable data object Oases : NetworkRoute

    /** One crowd (/crowds/{slug}); [meetups] opens it at „Treffen“, [collection] with that collection chosen in „Sammlungen“. */
    @Serializable data class Oasis(val slug: String, val meetups: Boolean = false, val collection: Int? = null) : NetworkRoute

    /** „Zu Sammlung hinzufügen …“ for a Post of the crowd [slug] (a sheet). */
    @Serializable data class CollectPost(val postId: Int, val slug: String) : NetworkRoute

    /** Create a crowd (/app/crowds/neu). */
    @Serializable data object CreateOasis : NetworkRoute

    /** Edit a crowd (/crowds/{slug}/bearbeiten). */
    @Serializable data class EditOasis(val slug: String) : NetworkRoute

    /** Close a crowd (/crowds/{slug}/schliessen). */
    @Serializable data class CloseOasis(val slug: String) : NetworkRoute

    /** Statistics of a crowd (/crowds/{slug}/statistik). */
    @Serializable data class OasisStatistics(val slug: String) : NetworkRoute

    /** A member profile (/profil/{handle}). */
    @Serializable data class Profile(val handle: String) : NetworkRoute

    /** Edit the own profile (/app/profil). */
    @Serializable data object EditProfile : NetworkRoute

    /** Freund:innen (/app/freunde). */
    @Serializable data object Friends : NetworkRoute

    /** Deine Zahlen (/app/statistik). */
    @Serializable data object Statistics : NetworkRoute

    /** Zeitkapseln – Posts sealed for a later day (/app/zeitkapseln). */
    @Serializable data object TimeCapsules : NetworkRoute

    /** Zeitkapseln at „Einladungen“ (capsule hints: /app/zeitkapseln#einladungen). */
    @Serializable data object CapsuleInvitations : NetworkRoute

    /** Dein Jahrbuch – the own Posts of a year, the current one without [year] (/app/jahrbuch/{year?}). */
    @Serializable data class Yearbook(val year: Int? = null) : NetworkRoute

    /** Menschen einladen (/app/einladen). */
    @Serializable data object Invitations : NetworkRoute

    /** An invitation link (/einladung/{token}). */
    @Serializable data class Invitation(val token: String) : NetworkRoute

    /** A group link of „Gemeinsam starten“ (/gemeinsam/{token}). */
    @Serializable data class InvitationGroup(val token: String) : NetworkRoute

    /** Suche (/app/suche?q=). */
    @Serializable data class Search(val query: String? = null) : NetworkRoute

    /** A topic (/themen/{tag}). */
    @Serializable data class Tag(val tag: String) : NetworkRoute

    /** Gemerkt (/app/gemerkt). */
    @Serializable data object Bookmarks : NetworkRoute

    /** Karte der Crowds (/app/crowds/karte?region=BY): the Bundesländer, [region] chosen. */
    @Serializable data class OasisMap(val region: String? = null) : NetworkRoute

    /** Dein Crowd-Wochenbrief (/app/wochenbrief/{id?}): letter [id], the newest without one. */
    @Serializable data class WeeklyLetter(val id: Int? = null) : NetworkRoute

    /** Ideen für Mildcrowd (/app/ideen/{id?}): the wish list, idea [id] in front when its link was opened. */
    @Serializable data class Ideas(val id: Int? = null) : NetworkRoute
}

/** Screens of the end-to-end encrypted chat. Owned by `:feature:chat`. Web: /app/nachrichten (one JS app). */
@Serializable
sealed interface ChatRoute : AppRoute {
    /** Conversation list, including setup/unlock of the chat identity when needed. */
    @Serializable data object Conversations : ChatRoute

    /** One conversation. */
    @Serializable data class Conversation(val id: Int) : ChatRoute

    /** Start a new 1:1 or group conversation. */
    @Serializable data object NewConversation : ChatRoute

    /**
     * Start a new chat with this username preselected – what the web's "Schreiben" buttons open
     * (`/app/nachrichten#neu/{username}`, App\Support\Chat\ChatReach). Prefer this one for "Schreiben".
     */
    @Serializable data class NewConversationWith(val username: String) : ChatRoute

    /** Members, title, lifetime, safety numbers, leave/report. */
    @Serializable data class Details(val conversationId: Int) : ChatRoute

    /** Chat settings (passphrase, e-mail notices, message requests). */
    @Serializable data object Settings : ChatRoute

    /** Devices that may open the chat (biometric unlock, device links). */
    @Serializable data object Devices : ChatRoute

    /** "Teilen mit …": pick the chat for what another app shared into Mildcrowd (it becomes that chat's drafts). */
    @Serializable data object Share : ChatRoute
}

/** Account, privacy, safety and help screens. Owned by `:feature:account`. */
@Serializable
sealed interface AccountRoute : AppRoute {
    /** Einstellungen (/app/einstellungen): profile data, e-mail, password, 2FA, account deletion, export. */
    @Serializable data object Settings : AccountRoute

    /** Privatsphäre (/app/privatsphaere). */
    @Serializable data object Privacy : AccountRoute

    /** Privatsphäre-Check (/app/privatsphaere/check). */
    @Serializable data object PrivacyCheckup : AccountRoute

    /** Ausgeblendete Wörter (/app/privatsphaere/woerter). */
    @Serializable data object HiddenWords : AccountRoute

    /** Geräte & Anmeldungen (/app/privatsphaere/geraete). */
    @Serializable data object Devices : AccountRoute

    /** Deine Zeit in Mildcrowd (/app/privatsphaere/zeit): minutes per day, counted only on this device. */
    @Serializable data object ScreenTime : AccountRoute

    /** Willkommen / onboarding (/app/willkommen). */
    @Serializable data object Welcome : AccountRoute

    /** Blockiert & stumm (/app/sicherheit). */
    @Serializable data object Safety : AccountRoute

    /** Melden (/app/melden), optionally prefilled with the reported URL. */
    @Serializable data class Report(val url: String? = null) : AccountRoute

    /** Begriffe erklärt (/app/begriffe). */
    @Serializable data object Glossary : AccountRoute

    /** Was ist neu: the versions of Mildcrowd and how it is built (/neuigkeiten). */
    @Serializable data object Releases : AccountRoute

    /** Gesperrt (/app/gesperrt). */
    @Serializable data object Suspended : AccountRoute

    /** Beschwerde gegen eine Moderationsentscheidung (/app/moderation/complaint/{decision}). */
    @Serializable data class Complaint(val decisionId: Int) : AccountRoute
}

/** Hinweise (notifications) and their settings. Owned by `:feature:live`. */
@Serializable
sealed interface LiveRoute : AppRoute {
    /** Hinweise inbox (/app/hinweise). */
    @Serializable data object Inbox : LiveRoute

    /** Hinweis-Einstellungen incl. push, quiet hours, digest (/app/hinweise/einstellungen). */
    @Serializable data object Settings : LiveRoute
}

/** Membership and payments. Owned by `:feature:membership`. */
@Serializable
sealed interface MembershipRoute : AppRoute {
    /** Mitgliedschaft overview (/app/mitgliedschaft). */
    @Serializable data object Overview : MembershipRoute

    /** Payment portal: payment method, invoices (/app/mitgliedschaft/portal). */
    @Serializable data object Portal : MembershipRoute

    /** One invoice (/app/mitgliedschaft/rechnungen/{payment}). */
    @Serializable data class Invoice(val paymentId: Int) : MembershipRoute

    /** Kündigen (/app/mitgliedschaft/kuendigen). */
    @Serializable data object Cancel : MembershipRoute

    /** Widerrufen (/app/mitgliedschaft/widerrufen). */
    @Serializable data object Withdraw : MembershipRoute

    /** Mitglied werden / checkout (/mitglied-werden). */
    @Serializable data object Checkout : MembershipRoute

    /** Deine Vorteile: what the tier includes, companion places, supporter mark (/app/mitgliedschaft/vorteile). */
    @Serializable data object Perks : MembershipRoute

    /** „Wohin dein Beitrag geht“ (/transparenz, public): figures and published finance reports. */
    @Serializable data object Transparency : MembershipRoute

    /**
     * The link of a companion place a Mäzen shares (/app/mitgliedschaft/mitkommen/{token}); opens without a membership,
     * since taking the place is what gives one.
     */
    @Serializable data class CompanionLink(val token: String) : MembershipRoute
}

/** Moderation and administration (staff only). Owned by `:feature:staff`. */
@Serializable
sealed interface StaffRoute : AppRoute {
    /** Moderation overview (/moderation). */
    @Serializable data object Moderation : StaffRoute

    /** One moderation case (/moderation/cases/{case}). */
    @Serializable data class ModerationCase(val id: Int) : StaffRoute

    /** Review of a complaint (/moderation/complaints/{complaint}). */
    @Serializable data class ComplaintReview(val id: Int) : StaffRoute

    /** Roles (/moderation/roles). */
    @Serializable data object Roles : StaffRoute

    /** Sperren (/moderation/sperren). */
    @Serializable data object Suspensions : StaffRoute

    /** Admin dashboard (/admin). */
    @Serializable data object AdminDashboard : StaffRoute

    /** Ideen: set the status of members' ideas (on the web the status form on /app/ideen). */
    @Serializable data object AdminIdeas : StaffRoute

    /** Zahlungen (/admin/zahlungen). */
    @Serializable data object AdminPayments : StaffRoute

    /** Datenpannen (/admin/datenpannen). */
    @Serializable data object AdminBreaches : StaffRoute

    /** System (/admin/system). */
    @Serializable data object AdminSystem : StaffRoute
}
