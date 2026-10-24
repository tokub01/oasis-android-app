package de.velwebsolutions.oasis.membership.ui

import de.velwebsolutions.oasis.core.network.ApiError

/** Field errors for a form and the text for the toast. */
data class FormErrors(val fields: Map<String, String>, val toast: String?)

/**
 * Errors without a field and the throttle (`throttle`, which belongs to no field) go to the toast, everything else
 * stays at its field.
 */
val ApiError.formErrors: FormErrors
    get() {
        val fields = fieldMessages.toMutableMap()
        val throttle = fields.remove("throttle")
        if (throttle != null) return FormErrors(fields, throttle)
        return FormErrors(fields, if (fields.isEmpty()) userMessage else null)
    }
