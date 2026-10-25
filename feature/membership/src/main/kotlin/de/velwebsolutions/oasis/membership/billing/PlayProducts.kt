package de.velwebsolutions.oasis.membership.billing

/**
 * The membership as Google Play subscriptions – the same tiers, prices and product ids as the App Store
 * (oasis-ios/Config/Oasis.storekit and `config('oasis.apple.products')` on the server), so the server can map a
 * purchase the same way. Each id is one subscription in the Play Console with a single auto-renewing base plan:
 * `monat` (P1M) or `jahr` (P1Y, two months free). Play shows its own localised price; the prices below are the ones
 * the products were created with.
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

    /** The yearly rhythm key. */
    const val YEARLY = "jahr"

    /** Solidar, monthly. */
    const val SOLIDAR_MONTHLY = "de.velwebsolutions.oasis.solidar.monat"

    /** Solidar, yearly. */
    const val SOLIDAR_YEARLY = "de.velwebsolutions.oasis.solidar.jahr"

    /** Oase, monthly. */
    const val OASE_MONTHLY = "de.velwebsolutions.oasis.oase.monat"

    /** Oase, yearly. */
    const val OASE_YEARLY = "de.velwebsolutions.oasis.oase.jahr"

    /** Mäzen, monthly. */
    const val MAEZEN_MONTHLY = "de.velwebsolutions.oasis.maezen.monat"

    /** Mäzen, yearly. */
    const val MAEZEN_YEARLY = "de.velwebsolutions.oasis.maezen.jahr"

    /** One subscription of the catalogue. */
    data class Entry(val id: String, val tier: String, val rhythm: String)

    /** In the order of the checkout: Solidar, Oase, Mäzen. */
    val all: List<Entry> = listOf(
        Entry(SOLIDAR_MONTHLY, "solidar", MONTHLY),
        Entry(SOLIDAR_YEARLY, "solidar", YEARLY),
        Entry(OASE_MONTHLY, "oase", MONTHLY),
        Entry(OASE_YEARLY, "oase", YEARLY),
        Entry(MAEZEN_MONTHLY, "maezen", MONTHLY),
        Entry(MAEZEN_YEARLY, "maezen", YEARLY)
    )

    val ids: List<String> = all.map(Entry::id)

    /** The tiers in the checkout's order. */
    val tiers: List<String> = all.map(Entry::tier).distinct()

    /** The catalogue entry of a product id; null for products that are not ours. */
    fun entry(id: String): Entry? = all.firstOrNull { it.id == id }
}
