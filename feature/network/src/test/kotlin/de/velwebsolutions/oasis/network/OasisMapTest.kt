package de.velwebsolutions.oasis.network

import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.network.api.MapRegion
import de.velwebsolutions.oasis.network.api.OasisInput
import de.velwebsolutions.oasis.network.api.OasisOptions
import de.velwebsolutions.oasis.network.api.OasisSummary
import de.velwebsolutions.oasis.network.api.RegionRef
import de.velwebsolutions.oasis.network.ui.oases.OasisFormViewModel
import de.velwebsolutions.oasis.network.ui.oases.OasisMapViewModel
import de.velwebsolutions.oasis.network.ui.oases.regionName
import de.velwebsolutions.oasis.testing.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/** The Karte der Oasen and an oasis's region (docs/oasis-network.md, „Karte der Oasen“). */
@RunWith(ParameterizedRobolectricTestRunner::class)
class OasisMapTest(private val answersAtOnce: Boolean) {
    companion object {
        /** Each test runs with answers that suspend and with answers on the calling thread. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "immediate={0}")
        fun modes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule val main = MainDispatcherRule()

    private val models = ViewModelKeeper()

    @Before
    fun answerMode() {
        immediate = answersAtOnce
    }

    @After
    fun clear() {
        immediate = false
        models.clear()
    }

    @Test
    fun theMapCountsTheOasesAndATileListsThem() {
        val network = StubbedNetwork(
            stub("GET", "network/regions", regionsJson()),
            stub("GET", "network/regions/BY/oases", """{"data":[${regionOasisJson("isar", "München")}]}"""),
            stub("GET", "network/regions/HB/oases", """{"message":"Nicht gefunden."}""", 404)
        )
        val opened = models.keep(OasisMapViewModel("BY", network.repository))
        val plain = models.keep(OasisMapViewModel(null, network.repository))
        runBlocking {
            opened.loadIfNeeded()
            opened.loadIfNeeded()
            assertEquals(1, network.transport.requests.count { it.path == "network/regions" })
            assertEquals(listOf("isar"), opened.oases.value.valueOrNull?.map { it.slug })
            assertEquals("Bayern · München", opened.oases.value.valueOrNull?.first()?.place)
            opened.choose("HB")
            assertEquals("HB", opened.chosen.value)
            assertTrue(opened.oases.value is Loadable.Failed)
            plain.load()
            assertNull(plain.chosen.value)
            assertTrue(plain.oases.value is Loadable.Idle)
        }
        val regions = decodeRegions()
        assertEquals("Bayern", regionName(regions, "BY"))
        assertEquals("XX", regionName(regions, "XX"))
        assertEquals("Bayern: 3 Crowds", regions.first { it.code == "BY" }.accessibleName)
        assertEquals("Bremen: 0 Crowds", regions.first { it.code == "HB" }.accessibleName)
        assertEquals("Berlin: 1 Crowd", regions.first { it.code == "BE" }.accessibleName)
        val broken = StubbedNetwork(stub("GET", "network/regions", """{"message":"x"}""", 500))
        val failing = models.keep(OasisMapViewModel("BY", broken.repository))
        runBlocking { failing.load() }
        assertTrue(failing.regions.value is Loadable.Failed)
        assertEquals(1, broken.transport.requests.size)
    }

    @Test
    fun theFormSendsTheRegionAndClearsItWithNull() {
        val network = StubbedNetwork(
            stub("GET", "network/oases/isar/edit", editFormJson()),
            stub("PUT", "network/oases/isar", """{"message":"Gespeichert.","data":{"slug":"isar"}}""")
        )
        val model = models.keep(OasisFormViewModel("isar", network.repository))
        runBlocking {
            model.load()
            assertEquals("BY", model.state.value.input.region)
            assertEquals("München", model.state.value.input.city)
            assertEquals(listOf("BY", "HB"), model.state.value.options?.regions?.map { it.value })
            model.save()
            val body = network.last("network/oases/isar").body.orEmpty()
            assertTrue(body, body.contains(""""region":"BY"""") && body.contains(""""city":"München""""))
            model.updateInput { it.copy(region = null) }
            model.save()
            val cleared = network.last("network/oases/isar").body.orEmpty()
            assertTrue(cleared, cleared.contains(""""region":null""") && cleared.contains(""""city":"""""))
        }
        val founding = OasisInput("Isar", region = "BY", city = "München")
        assertEquals(listOf("region" to "BY", "city" to "München"), founding.fields.takeLast(2))
        assertFalse(OasisInput("Isar").fields.any { it.first == "region" })
        assertTrue(OasisOptions().regions.isEmpty())
        val summary = OasisSummary(1, "isar", "Isar")
        assertNull(summary.place)
        assertEquals("Bayern", summary.copy(region = RegionRef("BY", "Bayern"), city = " ").place)
        assertEquals("Bayern", summary.copy(region = RegionRef("BY", "Bayern")).place)
        assertEquals(2, MapRegion("BY", "Bayern", tile = MapRegion.Tile(2, 4)).tile.x)
        assertEquals(0, MapRegion("BY", "Bayern", tile = MapRegion.Tile(2, 4)).oasesCount)
    }
}

fun regionsJson(): String {
    val tiles = listOf(
        Triple("SH", "Schleswig-Holstein", 2 to 0), Triple("HH", "Hamburg", 2 to 1), Triple("MV", "Mecklenburg-Vorpommern", 3 to 1),
        Triple("HB", "Bremen", 1 to 1), Triple("NI", "Niedersachsen", 1 to 2), Triple("ST", "Sachsen-Anhalt", 2 to 2),
        Triple("BB", "Brandenburg", 3 to 2), Triple("BE", "Berlin", 4 to 2), Triple("NW", "Nordrhein-Westfalen", 0 to 2),
        Triple("HE", "Hessen", 1 to 3), Triple("TH", "Thüringen", 2 to 3), Triple("SN", "Sachsen", 3 to 3),
        Triple("RP", "Rheinland-Pfalz", 0 to 3), Triple("SL", "Saarland", 0 to 4), Triple("BW", "Baden-Württemberg", 1 to 4),
        Triple("BY", "Bayern", 2 to 4)
    )
    val counts = mapOf("BY" to 3, "BE" to 1, "SN" to 2)
    val data = tiles.joinToString(",") { (code, name, tile) ->
        """{"code":"$code","name":"$name","oases_count":${counts[code] ?: 0},"tile":{"x":${tile.first},"y":${tile.second}}}"""
    }
    return """{"data":[$data]}"""
}

fun decodeRegions(): List<MapRegion> = de.velwebsolutions.oasis.core.network.OasisJson.snake.decodeFromString(
    de.velwebsolutions.oasis.core.network.DataEnvelope.serializer(kotlinx.serialization.builtins.ListSerializer(MapRegion.serializer())),
    regionsJson()
).data

fun regionOasisJson(slug: String, city: String?) = """
    {"id":7,"slug":"$slug","name":"Isar-Runde","description":"Wir paddeln.","color":"spring","visibility":"public","members_count":4,
    "region":{"code":"BY","name":"Bayern"},"city":${city?.let { "\"$it\"" } ?: "null"}}
""".trimIndent()

fun editFormJson() = """
    {"data":{"oasis":${regionOasisJson("isar", "München")},"house_rules":"Freundlich bleiben.","members":[],
    "options":{"visibilities":[{"value":"public","label":"Alle"}],"colors":[{"value":"spring","label":"Pfirsich"}],
    "regions":[{"value":"BY","label":"Bayern"},{"value":"HB","label":"Bremen"}]}}}
""".trimIndent()
