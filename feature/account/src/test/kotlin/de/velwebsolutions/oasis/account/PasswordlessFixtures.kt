package de.velwebsolutions.oasis.account

/** Answers of the passkey and phone endpoints (shapes of PasskeyResource and PhoneNumberController). */
object PasswordlessFixtures {
    const val LOCKED = """{"message":"Bitte bestätige zuerst dein Passwort."}"""

    const val PASSKEYS =
        """{"data":[{"id":3,"name":"Pixel 9","authenticator":"Google Passwortmanager","created_at":"2026-10-20T08:00:00+00:00",""" +
            """"last_used_at":"2026-10-25T18:30:00+00:00"},{"id":1,"name":"Laptop","authenticator":null,"created_at":null,"last_used_at":null}]}"""

    const val CREATION =
        """{"challenge":"reg-1","options":{"rp":{"id":"localhost","name":"Oasis"},""" +
            """"user":{"id":"dQ","name":"mira","displayName":"Mira"},""" +
            """"challenge":"Y2g","pubKeyCredParams":[{"type":"public-key","alg":-7}],"excludeCredentials":[]}}"""

    const val REQUEST =
        """{"challenge":"login-1","options":{"challenge":"Y2g","rpId":"localhost","allowCredentials":[],"userVerification":"required"}}"""

    const val ATTESTATION =
        """{"id":"cred","rawId":"cred","type":"public-key","response":{"clientDataJSON":"e30","attestationObject":"o2M"}}"""

    const val ASSERTION =
        """{"id":"cred","rawId":"cred","type":"public-key","response":{"clientDataJSON":"e30","authenticatorData":"YQ","signature":"cw","userHandle":"dQ"}}"""

    fun phone(available: Boolean = true, number: String? = null, pending: String? = null): String {
        val verified = if (number != null) "\"2026-10-26T09:00:00+00:00\"" else "null"
        val shown = number?.let { "\"$it\"" } ?: "null"
        val waiting = pending?.let { "\"$it\"" } ?: "null"
        return """{"data":{"sms_available":$available,"phone_number":$shown,"verified_at":$verified,"pending":$waiting}}"""
    }
}
