package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.api.Release
import de.velwebsolutions.oasis.account.api.ReleaseItem
import de.velwebsolutions.oasis.account.api.ReleaseNoteGroup
import de.velwebsolutions.oasis.account.api.ReleaseSection
import de.velwebsolutions.oasis.account.ui.releases.PreferencesReleaseNotesStore
import de.velwebsolutions.oasis.account.ui.releases.ReleaseNotesStore
import de.velwebsolutions.oasis.account.ui.releases.ReleasesViewModel
import de.velwebsolutions.oasis.account.ui.releases.UpdateNotesViewModel
import de.velwebsolutions.oasis.core.di.AppVersion
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import de.velwebsolutions.oasis.testing.StubTransport
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Was ist neu: the versions from `releases`, the notes for Android and „Neu in Oasis …“ once after an update. */
@RunWith(RobolectricTestRunner::class)
class ReleasesTest {
    @get:Rule val main = MainDispatcherRule()

    private val serverError = """{"message":"Server Error"}"""

    /** The version seen last, in memory. */
    private class MemoryStore(var seen: String? = null) : ReleaseNotesStore {
        override fun lastSeen() = seen

        override fun markSeen(version: String) {
            seen = version
        }
    }

    @Test
    fun theListDecodesAndShowsOnlyTheNotesForAllAndAndroid() = runTest {
        val releases = repository(StubTransport().stub("GET", "releases", Fixtures.releases)).releases()
        assertEquals("0.24.0", releases.current)
        assertTrue(releases.about.contains("Claude Code"))
        assertEquals(listOf("0.24.0", "0.16.0"), releases.data.map { it.version })
        val newest = releases.data.first()
        assertEquals("3. November 2026", newest.day)
        assertEquals(
            listOf(
                ReleaseNoteGroup("Neu", listOf("„Was ist neu“ zeigt jede Version.")),
                ReleaseNoteGroup("Verbessert", listOf("Der Chat startet ruhiger.")),
                ReleaseNoteGroup("Sicherheit", listOf("Eine Schwachstelle beim Hochladen von Bildern wurde behoben."))
            ),
            newest.notesFor()
        )
        assertEquals("a version without Android notes", emptyList<ReleaseNoteGroup>(), releases.data[1].notesFor())
    }

    @Test
    fun kindsKeepTheirOrderAndUnknownKindsComeLast() {
        val release = Release(
            "0.25.0",
            LocalDate.of(2027, 1, 1),
            "Neu",
            sections = listOf(
                ReleaseSection("android", listOf(ReleaseItem("anders", "Etwas anderes"), ReleaseItem("entfernt", "Weg"))),
                ReleaseSection("alle", listOf(ReleaseItem("behoben", "Repariert")))
            )
        )
        assertEquals(listOf("Behoben", "Entfernt", "Anders"), release.notesFor().map { it.label })
        assertEquals(listOf("Behoben"), release.notesFor("ios").map { it.label })
        assertEquals("1. Januar 2027", release.day)
    }

    @Test
    fun thePageLoadsOrShowsTheError() = runTest {
        val transport = StubTransport().stub("GET", "releases", serverError, status = 500)
        val model = ReleasesViewModel(repository(transport), AppVersion("0.24.0", freshInstall = false))
        assertEquals("0.24.0", model.version)
        model.load()
        assertTrue(model.state.value is Loadable.Failed)
        transport.stub("GET", "releases", Fixtures.releases)
        model.load()
        assertEquals("0.24.0", model.state.value.valueOrNull?.current)
    }

    @Test
    fun aFreshInstallOnlyRemembersItsVersion() = runTest {
        val transport = StubTransport()
        val store = MemoryStore()
        val model = UpdateNotesViewModel(repository(transport), store, AppVersion("0.24.0", freshInstall = true))
        model.check()
        assertNull(model.notes.value)
        assertEquals("0.24.0", store.seen)
        assertTrue("no request", transport.requests.isEmpty())
        UpdateNotesViewModel(repository(transport), store, AppVersion("0.24.0", freshInstall = false)).check()
        assertTrue("the same version asks nothing", transport.requests.isEmpty())
    }

    @Test
    fun afterAnUpdateTheNotesShowOnceUntilVerstanden() = runTest {
        val transport = StubTransport().stub("GET", "releases", Fixtures.releases)
        val store = MemoryStore("0.16.0")
        val model = UpdateNotesViewModel(repository(transport), store, AppVersion("0.24.0", freshInstall = false))
        model.check()
        assertEquals("Versionen für alle", model.notes.value?.title)
        assertEquals("0.16.0", store.seen)
        model.dismiss()
        assertNull(model.notes.value)
        assertEquals("0.24.0", store.seen)

        val beforeTheFeature = UpdateNotesViewModel(repository(transport), MemoryStore(), AppVersion("0.24.0", freshInstall = false))
        beforeTheFeature.check()
        assertEquals("0.24.0", beforeTheFeature.notes.value?.version)
    }

    @Test
    fun offlineOrWithoutNotesNothingShowsAndTheNextStartAsksAgain() = runTest {
        val transport = StubTransport().stub("GET", "releases", serverError, status = 500)
        val store = MemoryStore("0.16.0")
        val offline = UpdateNotesViewModel(repository(transport), store, AppVersion("0.24.0", freshInstall = false))
        offline.check()
        assertNull(offline.notes.value)
        transport.stub("GET", "releases", Fixtures.releases)
        val unknown = UpdateNotesViewModel(repository(transport), store, AppVersion("0.99.0", freshInstall = false))
        unknown.check()
        assertNull(unknown.notes.value)
        assertEquals("0.16.0", store.seen)
    }

    @Test
    fun theSeenVersionIsKeptInThePreferences() {
        val store = PreferencesReleaseNotesStore(RuntimeEnvironment.getApplication())
        assertNull(store.lastSeen())
        store.markSeen("0.24.0")
        assertEquals("0.24.0", PreferencesReleaseNotesStore(RuntimeEnvironment.getApplication()).lastSeen())
    }
}
