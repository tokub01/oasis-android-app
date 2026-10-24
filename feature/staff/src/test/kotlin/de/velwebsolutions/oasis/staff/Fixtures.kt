package de.velwebsolutions.oasis.staff

import de.velwebsolutions.oasis.core.network.OasisJson
import de.velwebsolutions.oasis.staff.data.DefaultStaffRepository
import de.velwebsolutions.oasis.testing.StubTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Real answers of the staff API (copied from the iOS tests, recorded against http://localhost/api/v1/staff/… with demo data). */
object Fixtures {
    fun json(name: String): String = requireNotNull(javaClass.getResource("/fixtures/$name.json")) { "fixture $name" }.readText()

    val overview get() = json("overview")
    val cases get() = json("cases")
    val caseDetail get() = json("caseDetail")
    val caseEscalated get() = json("caseEscalated")
    val caseCheck get() = json("caseCheck")
    val complaint get() = json("complaint")
    val roles get() = json("roles")
    val suspensions get() = json("suspensions")
    val dashboard get() = json("dashboard")
    val votes get() = json("votes")
    val payments get() = json("payments")
    val breaches get() = json("breaches")
    val breachStore get() = json("breachStore")
    val system get() = json("system")

    /** The `data` object of a `{ "data": … }` fixture as JSON text. */
    fun dataOf(fixture: String): String = OasisJson.verbatim.parseToJsonElement(fixture).jsonObject.getValue("data").toString()

    /** `{"data": <fixture's data>, "message": message}`. */
    fun confirmed(fixture: String, message: String) = """{"data":${dataOf(fixture)},"message":"$message"}"""

    fun repository(vararg stubs: StubTransport.Stub): Pair<DefaultStaffRepository, StubTransport> {
        val transport = StubTransport(*stubs)
        return DefaultStaffRepository(transport.client) to transport
    }

    /** The query of the last request as a map. */
    fun StubTransport.lastQuery(): Map<String, String> {
        val url = requests.last().request.url
        return url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
    }

    fun StubTransport.lastObject(path: String): JsonObject? = lastBody(path) as? JsonObject
}
