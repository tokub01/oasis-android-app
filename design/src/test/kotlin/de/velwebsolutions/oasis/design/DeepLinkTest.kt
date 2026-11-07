package de.velwebsolutions.oasis.design

import de.velwebsolutions.oasis.design.navigation.AccountRoute
import de.velwebsolutions.oasis.design.navigation.ChatRoute
import de.velwebsolutions.oasis.design.navigation.DeepLink
import de.velwebsolutions.oasis.design.navigation.IncomingLink
import de.velwebsolutions.oasis.design.navigation.LiveRoute
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.MembershipRoute
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.design.navigation.StaffRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkTest {
    private fun route(url: String) = DeepLink.route(url)

    @Test
    fun `only links of the own server open screens or account links`() {
        DeepLink.ownHost = "get-oasis.com"
        try {
            assertEquals(NetworkRoute.Post(12), route("https://get-oasis.com/tropfen/12"))
            assertEquals(NetworkRoute.Post(12), route("https://GET-OASIS.com/tropfen/12"))
            assertNull(route("https://get-oasis.com.evil.example/tropfen/12"))
            assertEquals(
                IncomingLink.Browser("https://evil.example/email/bestaetigen/1/h?expires=1&signature=s"),
                DeepLink.incoming("https://evil.example/email/bestaetigen/1/h?expires=1&signature=s")
            )
            assertEquals(IncomingLink.Screen(MembershipRoute.Overview), DeepLink.incoming("oasis://mitgliedschaft"))
            val navigator = Navigator().apply { openWeb = {} }
            assertFalse(navigator.handle("https://evil.example/app/nachrichten#c/5"))
        } finally {
            DeepLink.ownHost = null
        }
    }

    @Test
    fun `maps web links to screens`() {
        assertEquals(NetworkRoute.Post(12), route("https://get-oasis.com/tropfen/12"))
        assertEquals(NetworkRoute.EditPost(12), route("https://get-oasis.com/tropfen/12/bearbeiten"))
        assertEquals(NetworkRoute.Oasis("garten"), route("https://get-oasis.com/oasen/garten"))
        assertEquals(NetworkRoute.OasisStatistics("garten"), route("https://get-oasis.com/oasen/garten/statistik"))
        // The Konto-Hinweis `oasis_held` („Oase bearbeiten“) links the edit form of the held oasis.
        assertEquals(NetworkRoute.EditOasis("garten"), route("https://get-oasis.com/oasen/garten/bearbeiten"))
        assertEquals(NetworkRoute.CloseOasis("garten"), route("https://get-oasis.com/oasen/garten/schliessen"))
        assertEquals(NetworkRoute.Profile("mira"), route("https://get-oasis.com/profil/mira"))
        assertEquals(NetworkRoute.Search("tee"), route("https://get-oasis.com/app/suche?q=tee"))
        assertEquals(NetworkRoute.Source, route("https://get-oasis.com/app"))
        assertEquals(AccountRoute.Devices, route("https://get-oasis.com/app/privatsphaere/geraete"))
        assertEquals(AccountRoute.Complaint(4), route("https://get-oasis.com/app/moderation/complaint/4"))
        assertEquals(LiveRoute.Settings, route("https://get-oasis.com/app/hinweise/einstellungen"))
        assertEquals(MembershipRoute.Invoice(9), route("https://get-oasis.com/app/mitgliedschaft/rechnungen/9"))
        assertEquals(StaffRoute.ModerationCase(3), route("https://get-oasis.com/moderation/cases/3"))
        assertEquals(StaffRoute.AdminSystem, route("https://get-oasis.com/admin/system"))
        assertEquals(MembershipRoute.Overview, route("oasis://mitgliedschaft?checkout=abc"))
    }

    @Test
    fun `maps the tier pages and a companion link with a valid token`() {
        val token = "a".repeat(20) + "B7".repeat(10)
        assertEquals(NetworkRoute.TimeCapsules, route("https://get-oasis.com/app/zeitkapseln"))
        assertEquals(NetworkRoute.Yearbook(), route("https://get-oasis.com/app/jahrbuch"))
        assertEquals(NetworkRoute.Yearbook(2025), route("https://get-oasis.com/app/jahrbuch/2025"))
        assertEquals(MembershipRoute.Perks, route("https://get-oasis.com/app/mitgliedschaft/vorteile"))
        assertEquals(MembershipRoute.CompanionLink(token), route("https://get-oasis.com/app/mitgliedschaft/mitkommen/$token"))
        assertEquals(null, route("https://get-oasis.com/app/mitgliedschaft/mitkommen/kurz"))
        assertEquals(null, route("https://get-oasis.com/app/mitgliedschaft/mitkommen"))
        assertEquals(IncomingLink.Browser("https://get-oasis.com/anmelden"), DeepLink.incoming("https://get-oasis.com/anmelden"))
        assertEquals(IncomingLink.Browser("https://get-oasis.com/anmelden/neu"), DeepLink.incoming("https://get-oasis.com/anmelden/neu"))
        val navigator = Navigator()
        assertTrue(navigator.handle("https://get-oasis.com/app/mitgliedschaft/mitkommen/$token"))
        assertEquals(MainTab.Profile, navigator.selectedTab)
        assertEquals(listOf(MembershipRoute.CompanionLink(token)), navigator.stack(MainTab.Profile).toList())
    }

    @Test
    fun `maps the chat hash routes`() {
        assertEquals(ChatRoute.Conversation(5), route("https://get-oasis.com/app/nachrichten#c/5"))
        assertEquals(ChatRoute.Details(5), route("https://get-oasis.com/app/nachrichten#c/5/info"))
        assertEquals(ChatRoute.NewConversationWith("mira_s"), route("https://get-oasis.com/app/nachrichten#neu/mira_s"))
        assertEquals(ChatRoute.NewConversation, route("https://get-oasis.com/app/nachrichten#neu"))
        assertEquals(ChatRoute.Conversations, route("https://get-oasis.com/app/nachrichten#c/abc"))
        assertEquals(ChatRoute.Conversation(8), route("https://get-oasis.com/app/nachrichten?c=8"))
    }

    @Test
    fun `unknown paths stay in the browser`() {
        assertNull(route("https://get-oasis.com/impressum"))
        assertNull(route("https://get-oasis.com/"))
    }

    @Test
    fun `incoming links open screens, account steps or the browser`() {
        fun incoming(path: String) = DeepLink.incoming("https://get-oasis.com$path")
        assertEquals(IncomingLink.Screen(NetworkRoute.Post(12)), incoming("/tropfen/12"))
        assertEquals(IncomingLink.Screen(NetworkRoute.Invitation("abc")), incoming("/einladung/abc"))
        assertEquals(IncomingLink.Screen(NetworkRoute.Source), incoming("/app"))
        assertEquals(IncomingLink.Screen(MembershipRoute.Overview), DeepLink.incoming("oasis://mitgliedschaft?checkout=abc"))
        assertEquals(
            IncomingLink.VerifyEmail("5", "a1b2", "1790000000", "sig%"),
            incoming("/email/bestaetigen/5/a1b2?expires=1790000000&signature=sig%25")
        )
        assertEquals(
            IncomingLink.ResetPassword(
                "tok",
                "mira@demo.oasis.tld",
                "https://get-oasis.com/passwort-zuruecksetzen/tok?email=mira%40demo.oasis.tld"
            ),
            incoming("/passwort-zuruecksetzen/tok?email=mira%40demo.oasis.tld")
        )
        assertEquals(
            IncomingLink.ResetPassword("tok", "", "https://get-oasis.com/passwort-zuruecksetzen/tok"),
            incoming("/passwort-zuruecksetzen/tok")
        )
        assertEquals(IncomingLink.LoginCode, incoming("/anmelden/code"))
        assertEquals(IncomingLink.LoginCode, incoming("/anmelden/code/eingeben"))
        listOf(
            "/app/chat/api/keys",
            "/app/mitgliedschaft/rechnungen/9",
            "/app/konto/export",
            "/email/bestaetigen/5/a1b2",
            "/email/bestaetigen/5/a1b2?expires=1",
            "/digest/abmelden/x",
            "/anmelden"
        ).forEach { path -> assertEquals(path, IncomingLink.Browser("https://get-oasis.com$path"), incoming(path)) }
        assertEquals(IncomingLink.Browser("kaputt ://"), DeepLink.incoming("kaputt ://"))
        assertEquals(IncomingLink.Browser("mailto:mira@demo.oasis.tld"), DeepLink.incoming("mailto:mira@demo.oasis.tld"))
        assertEquals(IncomingLink.Screen(NetworkRoute.Post(1)), incoming("/tropfen/1/a/b"))
        assertEquals(IncomingLink.Browser("https://get-oasis.com/email/andere/5/a"), incoming("/email/andere/5/a"))
    }

    /** The website's "In der App öffnen" (mail apps open links in their own browser) hands pages over via oasis://. */
    @Test
    fun `account links handed over by the website, but never a reset token`() {
        assertEquals(
            IncomingLink.VerifyEmail("5", "a1b2", "1790000000", "sig%"),
            DeepLink.incoming("oasis://email/bestaetigen/5/a1b2?expires=1790000000&signature=sig%25")
        )
        assertEquals(IncomingLink.LoginCode, DeepLink.incoming("oasis://anmelden/code"))
        assertEquals(IncomingLink.Screen(NetworkRoute.Post(12)), DeepLink.incoming("oasis://tropfen/12"))
        assertFalse(DeepLink.incoming("oasis://passwort-zuruecksetzen/tok") is IncomingLink.ResetPassword)
    }

    /** Handed to the browser, they would come straight back to the app and go to the browser again, without end. */
    @Test
    fun `app links without a screen open the Quelle instead of the browser`() {
        listOf(
            "oasis://x",
            "oasis://email/bestaetigen/5/a1b2",
            "oasis://passwort-zuruecksetzen/tok",
            "https://get-oasis.com/app/unbekannt",
            "https://get-oasis.com/app/moderation/anderes"
        ).forEach { url -> assertEquals(url, IncomingLink.Screen(NetworkRoute.Source), DeepLink.incoming(url)) }
        val opened = mutableListOf<String>()
        val navigator = Navigator().apply { openWeb = { opened += it } }
        navigator.select(MainTab.Chats)
        assertTrue(navigator.handle("oasis://x"))
        assertEquals(MainTab.Source, navigator.selectedTab)
        assertTrue("nothing went to the browser", opened.isEmpty())
    }

    @Test
    fun `account links wait in the navigator and the rest goes to the browser`() {
        val opened = mutableListOf<String>()
        val navigator = Navigator().apply { openWeb = { opened += it } }
        assertFalse(navigator.handle("https://get-oasis.com/impressum"))
        assertEquals(listOf("https://get-oasis.com/impressum"), opened)
        assertTrue(navigator.handle("https://get-oasis.com/anmelden/code"))
        assertEquals(IncomingLink.LoginCode, navigator.pendingLink)
        assertEquals(IncomingLink.LoginCode, navigator.takeLink())
        assertNull(navigator.pendingLink)
        assertNull(navigator.takeLink())
    }

    @Test
    fun `navigator opens deep links on their tab`() {
        val navigator = Navigator()
        assertTrue(navigator.handle("https://get-oasis.com/app/nachrichten#c/5"))
        assertEquals(MainTab.Chats, navigator.selectedTab)
        assertEquals(listOf(ChatRoute.Conversation(5)), navigator.stack(MainTab.Chats).toList())
        navigator.handle(NetworkRoute.Discover)
        assertEquals(MainTab.Discover, navigator.selectedTab)
        assertTrue(navigator.stack(MainTab.Discover).isEmpty())
    }

    @Test
    fun `back pops the sheet first and reselecting a tab pops to its root`() {
        val navigator = Navigator()
        navigator.open(NetworkRoute.Post(1))
        navigator.present(NetworkRoute.Compose())
        navigator.open(NetworkRoute.Oasis("garten"))
        assertTrue(navigator.back())
        assertEquals(NetworkRoute.Compose(), navigator.sheet)
        navigator.dismissSheet()
        navigator.select(MainTab.Source)
        assertTrue(navigator.stack(MainTab.Source).isEmpty())
    }
}
