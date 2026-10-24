package de.velwebsolutions.oasis.membership.billing

/**
 * The membership as Google Play subscriptions – the same tiers, prices and product ids as the App Store
 * (oasis-ios/Config/Oasis.storekit and `config('oasis.apple.products')` on the server), so the server can map a
 * purchase the same way. Each id is one subscription in the Play Console with a single auto-renewing base plan:
 * `monat` (P1M) or `jahr` (P1Y, two months free). Play shows its own localised price; [fallbackPrice] is only the
 * price the products were created with, for documentation and tests.
 *
 * | Product id                                 | Tier    | Rhythm | Price     |
 * |--------------------------------------------|---------|--------|-----------|
 * | `de.velwebsolutions.oasis.solidar.monat`   | Solidar | monat  | 3 € / Monat   |
 * | `de.velwebsolutions.oasis.solidar.jahr`    | Solidar | jahr   | 30 € / Jahr   |
 * | `de.velwebsolutions.oasis.oase.monat`      | Oase    | monat  | 5 € / Monat   |
 * | `de.velwebsolutions.oasis.oase.jahr`       | Oase    | jahr   | 50 € / Jahr   |
 * | `de.velwebsolutions.oasis.maezen.monat`    | Mäzen   | monat  | 12 € / Monat  |
 * | `de.velwebsolutions.oasis.maezen.jahr`     | Mäzen   | jahr   | 120 € / Jahr  |
 */
object PlayProducts {
    /** The server's rhythm keys. */
    const val MONTHLY = "monat"
    const val YEARLY = "jahr"

    const val SOLIDAR_MONTHLY = "de.velwebsolutions.oasis.solidar.monat"
    const val SOLIDAR_YEARLY = "de.velwebsolutions.oasis.solidar.jahr"
    const val OASE_MONTHLY = "de.velwebsolutions.oasis.oase.monat"
    const val OASE_YEARLY = "de.velwebsolutions.oasis.oase.jahr"
    const val MAEZEN_MONTHLY = "de.velwebsolutions.oasis.maezen.monat"
    const val MAEZEN_YEARLY = "de.velwebsolutions.oasis.maezen.jahr"

    /** One subscription of the catalogue. */
    data class Entry(val id: String, val tier: String, val rhythm: String, val fallbackPrice: String)

    /** In the order of the checkout: Solidar, Oase, Mäzen. */
    val all: List<Entry> = listOf(
        Entry(SOLIDAR_MONTHLY, "solidar", MONTHLY, "3,00 €"),
        Entry(SOLIDAR_YEARLY, "solidar", YEARLY, "30,00 €"),
        Entry(OASE_MONTHLY, "oase", MONTHLY, "5,00 €"),
        Entry(OASE_YEARLY, "oase", YEARLY, "50,00 €"),
        Entry(MAEZEN_MONTHLY, "maezen", MONTHLY, "12,00 €"),
        Entry(MAEZEN_YEARLY, "maezen", YEARLY, "120,00 €")
    )

    val ids: List<String> = all.map(Entry::id)

    /** The tiers in the checkout's order. */
    val tiers: List<String> = all.map(Entry::tier).distinct()

    fun entry(id: String): Entry? = all.firstOrNull { it.id == id }
}
