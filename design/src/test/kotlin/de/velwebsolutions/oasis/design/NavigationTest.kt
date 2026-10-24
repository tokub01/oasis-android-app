package de.velwebsolutions.oasis.design

import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.AppRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.DeepLink
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import de.velwebsolutions.oasis.design.navigation.WebRoute
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    private val base = "https://get-oasis.com"

    /** Every web path the app knows, with the screen it opens (the same table as iOS' DeepLink). */
    private val links: Map<String, AppRoute?> = mapOf(
        "/app" to NetworkRoute.Source,
        "/moderation" to StaffRoute.Moderation,
        "/admin" to StaffRoute.AdminDashboard,
        "/mitglied-werden" to MembershipRoute.Checkout,
        "/mitgliedschaft" to MembershipRoute.Overview,
        "/impressum" to null,
        "/tropfen/4" to NetworkRoute.Post(4),
        "/tropfen/4/bearbeiten" to NetworkRoute.EditPost(4),
        "/tropfen/4/weitertragen" to NetworkRoute.CarryPost(4),
        "/tropfen/4/anderes" to NetworkRoute.Post(4),
        "/tropfen/abc" to null,
        "/oasen/garten" to NetworkRoute.Oasis("garten"),
        "/oasen/garten/bearbeiten" to NetworkRoute.EditOasis("garten"),
        "/oasen/garten/schliessen" to NetworkRoute.CloseOasis("garten"),
        "/oasen/garten/statistik" to NetworkRoute.OasisStatistics("garten"),
        "/oasen/garten/sonstwas" to NetworkRoute.Oasis("garten"),
        "/profil/mira" to NetworkRoute.Profile("mira"),
        "/einladung/tok" to NetworkRoute.Invitation("tok"),
        "/themen/tee" to NetworkRoute.Tag("tee"),
        "/themen/gr%C3%BCn" to NetworkRoute.Tag("grün"),
        "/moderation/cases/3" to StaffRoute.ModerationCase(3),
        "/moderation/cases" to null,
        "/moderation/complaints/5" to StaffRoute.ComplaintReview(5),
        "/moderation/complaints" to null,
        "/moderation/complaints/x" to null,
        "/moderation/cases/x" to null,
        "/moderation/roles" to StaffRoute.Roles,
        "/moderation/sperren" to StaffRoute.Suspensions,
        "/moderation/anderes" to null,
        "/admin/abstimmungen" to StaffRoute.AdminVotes,
        "/admin/zahlungen" to StaffRoute.AdminPayments,
        "/admin/datenpannen" to StaffRoute.AdminBreaches,
        "/admin/system" to StaffRoute.AdminSystem,
        "/admin/anderes" to null,
        "/sonst/etwas" to null,
        "/app/entdecken" to NetworkRoute.Discover,
        "/app/durchblaettern" to NetworkRoute.Swipe,
        "/app/freunde" to NetworkRoute.Friends,
        "/app/oasen" to NetworkRoute.Oases,
        "/app/oasen/neu" to NetworkRoute.CreateOasis,
        "/app/profil" to NetworkRoute.EditProfile,
        "/app/statistik" to NetworkRoute.Statistics,
        "/app/einladen" to NetworkRoute.Invitations,
        "/app/suche" to NetworkRoute.Search(null),
        "/app/suche?q=gr%C3%BCner+tee" to NetworkRoute.Search("grüner tee"),
        "/app/gemerkt" to NetworkRoute.Bookmarks,
        "/app/nachrichten" to ChatRoute.Conversations,
        "/app/nachrichten?conversation=9" to ChatRoute.Conversation(9),
        "/app/nachrichten?c=x" to ChatRoute.Conversations,
        "/app/hinweise" to LiveRoute.Inbox,
        "/app/hinweise/einstellungen" to LiveRoute.Settings,
        "/app/einstellungen" to AccountRoute.Settings,
        "/app/privatsphaere" to AccountRoute.Privacy,
        "/app/privatsphaere/check" to AccountRoute.PrivacyCheckup,
        "/app/privatsphaere/woerter" to AccountRoute.HiddenWords,
        "/app/privatsphaere/geraete" to AccountRoute.Devices,
        "/app/willkommen" to AccountRoute.Welcome,
        "/app/sicherheit" to AccountRoute.Safety,
        "/app/melden" to AccountRoute.Report(null),
        "/app/melden?url=https%3A%2F%2Fget-oasis.com%2Ftropfen%2F1" to AccountRoute.Report("https://get-oasis.com/tropfen/1"),
        "/app/begriffe" to AccountRoute.Glossary,
        "/app/gesperrt" to AccountRoute.Suspended,
        "/app/abstimmungen" to AccountRoute.Votes,
        "/app/moderation/complaint/4" to AccountRoute.Complaint(4),
        "/app/moderation/complaint/x" to null,
        "/app/moderation/anderes" to null,
        "/app/mitgliedschaft" to MembershipRoute.Overview,
        "/app/mitgliedschaft/portal" to MembershipRoute.Portal,
        "/app/mitgliedschaft/kuendigen" to MembershipRoute.Cancel,
        "/app/mitgliedschaft/widerrufen" to MembershipRoute.Withdraw,
        "/app/mitgliedschaft/rechnungen/9" to MembershipRoute.Invoice(9),
        "/app/mitgliedschaft/rechnungen" to null,
        "/app/unbekannt" to null,
        "/app/nachrichten#neu" to ChatRoute.NewConversation,
        "/app/nachrichten#/neu/mira" to ChatRoute.NewConversationWith("mira"),
        "/app/nachrichten#neu/M" to ChatRoute.Conversations,
        "/app/nachrichten#c" to ChatRoute.Conversations,
        "/app/nachrichten#c/0" to ChatRoute.Conversations,
        "/app/nachrichten#c/5/info" to ChatRoute.Details(5),
        "/app/nachrichten#c/5/mehr" to ChatRoute.Conversations,
        "/app/nachrichten#c/5/x/y" to ChatRoute.Conversations,
        "/app/nachrichten#sonst" to ChatRoute.Conversations
    )

    @Test
    fun `every web path opens its screen`() {
        links.forEach { (path, route) -> assertEquals(path, route, DeepLink.route(base + path)) }
        assertEquals(MembershipRoute.Portal, DeepLink.route("oasis://app/mitgliedschaft/portal"))
        assertNull(DeepLink.route("oasis://"))
        assertNull(DeepLink.route("mailto:hallo@get-oasis.com"))
        assertNull(DeepLink.route("kein link mit leerzeichen"))
        assertNull(DeepLink.route("$base/themen/100%"))
    }

    @Test
    fun `routes survive saved state`() {
        val routes = links.values.filterNotNull().toSet() + setOf(
            WebRoute("https://get-oasis.com/impressum"),
            NetworkRoute.Compose("garten", 4),
            ChatRoute.Settings,
            ChatRoute.Devices
        )
        listOf(
            NetworkRoute.serializer(),
            ChatRoute.serializer(),
            AccountRoute.serializer(),
            LiveRoute.serializer(),
            MembershipRoute.serializer(),
            StaffRoute.serializer()
        ).forEach { assertTrue(it.descriptor.serialName.startsWith("de.velwebsolutions")) }
        routes.forEach { route ->
            assertEquals(route, Json.decodeFromString(AppRoute.serializer(), Json.encodeToString(AppRoute.serializer(), route)))
        }
    }

    @Test
    fun `routes belong to their tab and tabs have their root`() {
        assertEquals(MainTab.Chats, Navigator.tab(ChatRoute.Conversations))
        listOf(NetworkRoute.Discover, NetworkRoute.Swipe, NetworkRoute.Search(), NetworkRoute.Tag("t")).forEach {
            assertEquals(MainTab.Discover, Navigator.tab(it))
        }
        listOf(
            NetworkRoute.Oases,
            NetworkRoute.Oasis("o"),
            NetworkRoute.CreateOasis,
            NetworkRoute.EditOasis("o"),
            NetworkRoute.CloseOasis("o"),
            NetworkRoute.OasisStatistics("o"),
            NetworkRoute.Invitation("t")
        ).forEach { assertEquals(MainTab.Oases, Navigator.tab(it)) }
        listOf(
            NetworkRoute.Source,
            NetworkRoute.Post(1),
            NetworkRoute.EditPost(1),
            NetworkRoute.CarryPost(1),
            NetworkRoute.Compose()
        ).forEach {
            assertEquals(MainTab.Source, Navigator.tab(it))
        }
        listOf(NetworkRoute.Profile("m"), AccountRoute.Settings, LiveRoute.Inbox, MembershipRoute.Overview, StaffRoute.Roles).forEach {
            assertEquals(MainTab.Profile, Navigator.tab(it))
        }
        assertEquals(
            listOf(NetworkRoute.Source, NetworkRoute.Discover, NetworkRoute.Oases, ChatRoute.Conversations, null),
            MainTab.entries.map { Navigator.rootRoute(it) }
        )
        assertEquals(listOf("Quelle", "Entdecken", "Oasen", "Chats", "Profil"), MainTab.entries.map { it.title })
    }

    @Test
    fun `websites open outside the stacks`() {
        val opened = mutableListOf<String>()
        val navigator = Navigator().apply { openWeb = { opened += it } }
        navigator.open(WebRoute("https://get-oasis.com/agb"))
        navigator.handle(WebRoute("https://get-oasis.com/datenschutz"))
        assertEquals(listOf("https://get-oasis.com/agb", "https://get-oasis.com/datenschutz"), opened)
        assertTrue(navigator.stack(MainTab.Source).isEmpty())
        Navigator().open(WebRoute("https://get-oasis.com/hilfe"))
    }

    @Test
    fun `back, sheets and unknown links`() {
        val navigator = Navigator()
        assertFalse(navigator.back())
        navigator.open(NetworkRoute.Post(1))
        assertTrue(navigator.back())
        assertTrue(navigator.stack(MainTab.Source).isEmpty())
        navigator.present(ChatRoute.NewConversation)
        navigator.sheetDismissible = false
        navigator.dismissSheet()
        assertTrue(navigator.sheetDismissible)
        assertNull(navigator.sheet)
        assertFalse(navigator.handle("https://get-oasis.com/impressum"))
        navigator.handle(NetworkRoute.Post(2))
        navigator.select(MainTab.Oases)
        assertEquals(MainTab.Oases, navigator.selectedTab)
        assertEquals(listOf(NetworkRoute.Post(2)), navigator.stack(MainTab.Source).toList())
        navigator.popToRoot(MainTab.Source)
        assertTrue(navigator.stack(MainTab.Source).isEmpty())
    }
}
