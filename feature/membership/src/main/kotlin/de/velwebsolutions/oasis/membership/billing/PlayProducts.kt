package de.velwebsolutions.oasis.membership.billing

/**
 * The membership as Google Play subscriptions – the same tiers, prices and product ids as the App Store
 * (oasis-ios/Config/Oasis.storekit and `config('oasis.apple.products')` on the server), so the server can map a
 * purchase the same way. Each id is one subscription in the Play Console with a single auto-renewing base plan:
 * `monat` (P1M) or `jahr` (P1Y, two months free). Play shows its own localised price; the prices below are the ones
 * the products were created with. The server lists them (`google.products` of GET membership), the checkout offers that list.
 *
 * | Product id                                      | Tier       | Rhythm | Price           |
 * |-------------------------------------------------|------------|--------|-----------------|
 * | `de.velwebsolutions.mildcrowd.solidar.monat`    | Solidar    | monat  | 3 € / Monat     |
 * | `de.velwebsolutions.mildcrowd.solidar.jahr`     | Solidar    | jahr   | 30 € / Jahr     |
 * | `de.velwebsolutions.mildcrowd.mittendrin.monat` | Mittendrin | monat  | 5 € / Monat     |
 * | `de.velwebsolutions.mildcrowd.mittendrin.jahr`  | Mittendrin | jahr   | 50 € / Jahr     |
 * | `de.velwebsolutions.mildcrowd.maezen.monat`     | Mäzen      | monat  | 12 € / Monat    |
 * | `de.velwebsolutions.mildcrowd.maezen.jahr`      | Mäzen      | jahr   | 119,99 € / Jahr |
 */
object PlayProducts {
    /** The server's monthly rhythm key. */
    const val MONTHLY = "monat"

    /** The server's yearly rhythm key. */
    const val YEARLY = "jahr"
}
