package de.velwebsolutions.oasis.staff

import de.velwebsolutions.oasis.core.network.Page
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Every staff model can be built in code, with all values and with its defaults: the screens' tests decode most of them
 * from the API, which goes through the serializer's own constructor instead.
 */
class ModelConstructionTest {
    private val models = listOf(
        "StatTile", "AdminDashboard", "AdminVote", "NewVote", "PaymentRecord", "PaymentPage", "MolliePayment", "MollieCustomer",
        "MollieLookup", "DataBreach", "BreachRegister", "NewBreach", "SystemStatus", "Labelled", "ModerationOverview", "CaseSummary",
        "QueueFilter", "Decision", "ModerationCaseDetail", "StatementOfReasons", "ComplaintDetail", "ComplaintReview", "RoleUser",
        "RolePage", "SuspensionList", "Suspension", "AssignBody", "TypeBody", "ReferenceBody", "RoleBody", "SearchBody",
        "RefundBody", "NoteBody"
    ).map { Class.forName("de.velwebsolutions.oasis.staff.api.$it") }

    /** The class and its nested model classes (not the serializers and companions the compiler adds). */
    private fun withNested(type: Class<*>): List<Class<*>> =
        listOf(type) + type.declaredClasses.filterNot { it.simpleName == "Companion" || it.simpleName.contains("serializer") }
            .filterNot { it.isEnum || it.isInterface }
            .flatMap(::withNested)

    private fun isMarker(type: Class<*>) =
        type.name.endsWith("DefaultConstructorMarker") || type.name.endsWith("SerializationConstructorMarker")

    private fun primary(type: Class<*>): Constructor<*> =
        type.declaredConstructors.filter { !it.isSynthetic && it.parameterTypes.none(::isMarker) }.maxBy { it.parameterCount }

    /** A plain value of [type]; models are built from their primary constructor. */
    private fun value(type: Class<*>): Any? = when {
        type == Int::class.javaPrimitiveType || type == Integer::class.java -> 1
        type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> true
        type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> 1L
        type == String::class.java -> "x"
        type == Instant::class.java -> Instant.EPOCH
        List::class.java.isAssignableFrom(type) -> emptyList<Any>()
        Map::class.java.isAssignableFrom(type) -> emptyMap<Any, Any>()
        type == Page.Meta::class.java -> Page.Meta()
        type.isEnum -> type.enumConstants.first()
        else -> build(type)
    }

    private fun build(type: Class<*>): Any {
        val constructor = primary(type).apply { isAccessible = true }
        return constructor.newInstance(*constructor.parameterTypes.map(::value).toTypedArray())
    }

    /** Calls the constructor Kotlin adds for default arguments, with every default taken. */
    private fun buildWithDefaults(type: Class<*>): Any? {
        val withDefaults = type.declaredConstructors
            .firstOrNull { it.parameterTypes.lastOrNull()?.name?.endsWith("DefaultConstructorMarker") == true }
            ?: return null
        withDefaults.isAccessible = true
        val parameters = withDefaults.parameterTypes
        val values = parameters.mapIndexed { index, parameter ->
            when {
                index == parameters.lastIndex -> null
                parameter == Int::class.javaPrimitiveType && index >= primary(type).parameterCount -> -1
                else -> value(parameter)
            }
        }
        return withDefaults.newInstance(*values.toTypedArray())
    }

    @Test
    fun `every model is built with all values and with its defaults`() {
        val classes = models.flatMap(::withNested).filter { Modifier.isPublic(it.modifiers) }
        var withDefaults = 0
        classes.forEach { type ->
            assertNotNull(type.name, build(type))
            if (buildWithDefaults(type) != null) withDefaults++
        }
        assertEquals(true, withDefaults > 0)
        assertEquals(true, classes.size > models.size)
    }
}
