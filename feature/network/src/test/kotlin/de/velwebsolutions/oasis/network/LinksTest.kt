package de.velwebsolutions.oasis.network

import androidx.compose.ui.text.LinkAnnotation
import de.velwebsolutions.oasis.design.navigation.MainTab
import de.velwebsolutions.oasis.design.navigation.Navigator
import de.velwebsolutions.oasis.design.navigation.NetworkRoute
import de.velwebsolutions.oasis.network.api.TextSegment
import de.velwebsolutions.oasis.network.ui.components.RichTextBuilder
import de.velwebsolutions.oasis.network.ui.components.openNetworkLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Links in a Tropfen: which segments link where, and which links open inside the app (iOS `NetworkLinkHandler`). */
class LinksTest {
    private val base = "http://localhost".toHttpUrl()

    @Test
    fun `segments without the data for a link stay text`() {
        assertNull(RichTextBuilder.link(TextSegment(TextSegment.Kind.Link, "kaputt", url = "kein link"), base))
        assertNull(RichTextBuilder.link(TextSegment(TextSegment.Kind.Link, "ohne"), base))
        assertNull(RichTextBuilder.link(TextSegment(TextSegment.Kind.Mention, "@"), base))
        assertNull(RichTextBuilder.link(TextSegment(TextSegment.Kind.Mention, "@jonas", username = "jonas"), null))
        assertNull(RichTextBuilder.link(TextSegment(TextSegment.Kind.Tag, "#"), base))
        assertNull(RichTextBuilder.link(TextSegment(TextSegment.Kind.Tag, "#garten", tag = "garten"), null))
    }

    @Test
    fun `a tapped link goes to its handler, and a text without one keeps the default`() {
        val tapped = mutableListOf<String>()
        val segments = listOf(TextSegment(TextSegment.Kind.Mention, "@jonas", username = "jonas"))
        val text = RichTextBuilder.annotated(segments, base) { tapped += it }
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf("http://localhost/profil/jonas"), tapped)
        val plain = RichTextBuilder.annotated(segments, base)
        assertNull((plain.getLinkAnnotations(0, plain.length).single().item as LinkAnnotation.Url).linkInteractionListener)
    }

    @Test
    fun `Oasis pages open in the app, closing a sheet first, everything else in the browser`() {
        val web = mutableListOf<String>()
        val navigator = Navigator().apply { openWeb = { web += it } }
        openNetworkLink("https://example.org/a", navigator, base)
        openNetworkLink("kein link", navigator, base)
        openNetworkLink("http://localhost/profil/jonas", navigator, null)
        openNetworkLink("http://localhost/", navigator, base)
        assertEquals(listOf("https://example.org/a", "kein link", "http://localhost/profil/jonas", "http://localhost/"), web)

        navigator.present(NetworkRoute.Compose())
        openNetworkLink("http://localhost/profil/jonas", navigator, base)
        assertNull(navigator.sheet)
        openNetworkLink("http://localhost/themen/garten", navigator, base)
        assertEquals(NetworkRoute.Tag("garten"), navigator.stack(MainTab.Source).last())
    }
}

/** Colours on night surfaces are solid (the app is always light; no see-through white on Night). */
class SurfaceColorsTest {
    @Test
    fun `the soft circle of a night oasis is the solid night line`() {
        assertEquals(
            de.velwebsolutions.oasis.design.theme.OasisColors.NightLine,
            de.velwebsolutions.oasis.network.ui.oases.softCircle("night")
        )
        assertEquals(
            de.velwebsolutions.oasis.design.theme.OasisColors.Paper.copy(alpha = 0.25f),
            de.velwebsolutions.oasis.network.ui.oases.softCircle("spring")
        )
    }
}
