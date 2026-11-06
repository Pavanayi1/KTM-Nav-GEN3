package com.navigator.app.analytics

/**
 * Predictive fuel cost. The rider enters the bike's CLAIMED mileage (km/l);
 * the model derates it to real-world (x0.83, so a claimed 20 becomes ~16.6 -
 * the rider's own spec), then nudges it by riding style: a perfectly smooth
 * ride (score 100) earns +12.5% over that base, a chaotic one (score 0) pays
 * -12.5%. Effective mileage is clamped to a sane band around the claim.
 */
object FuelModel {

    data class Estimate(
        val effectiveKmPerL: Double,
        val litres: Double,
        val cost: Double,
    )

    private const val REALITY_FACTOR = 0.83
    private const val STYLE_GAIN = 0.25 // ±12.5% across the score range

    fun estimate(distanceKm: Double, claimedKmPerL: Double, pricePerLitre: Double, smoothnessScore: Int): Estimate? {
        if (claimedKmPerL <= 0.0 || distanceKm <= 0.0) return null
        val base = claimedKmPerL * REALITY_FACTOR
        val styled = base * (1.0 + STYLE_GAIN * (smoothnessScore - 50) / 100.0)
        val effective = styled.coerceIn(claimedKmPerL * 0.6, claimedKmPerL * 1.1)
        val litres = distanceKm / effective
        return Estimate(effective, litres, litres * pricePerLitre)
    }
}
