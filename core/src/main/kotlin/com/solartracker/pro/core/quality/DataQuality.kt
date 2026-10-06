package com.solartracker.pro.core.quality

import java.time.Duration
import java.time.Instant

/**
 * What kind of value the user is looking at. Every number shown in the app must carry one of these,
 * so a measurement is never confused with a calculation, an estimate or a forecast.
 */
enum class DataKind(val label: String) {
    /** Read from a device right now (fresh). */
    MEASURED("POMIAR"),
    /** Derived from measured values with an exact formula (e.g. V × A). */
    CALCULATED("OBLICZONE"),
    /** Model or assumption with known uncertainty. */
    ESTIMATED("SZACUNEK"),
    /** Prediction of the future. */
    FORECAST("PROGNOZA"),
    /** A measurement older than its freshness limit while the device is still connected. */
    STALE("NIEAKTUALNE"),
    /** The newest value from a device that is no longer connected. */
    LAST_KNOWN("OSTATNIA ZNANA"),
    /** Read from the device but rejected by validation (out of range, impossible jump, inconsistent). */
    INVALID("BŁĘDNE"),
    /** The source cannot provide this value (e.g. not in the protocol). */
    UNAVAILABLE("N/A"),
    /** Value not determined (missing data). */
    UNKNOWN("NIEZNANE"),
    ;

    val hasValue: Boolean get() = this != UNAVAILABLE && this != UNKNOWN && this != INVALID
}

/**
 * A value with its provenance. [value] is null for [DataKind.UNAVAILABLE]/[DataKind.UNKNOWN].
 *
 * @property confidence 0..1, null when not applicable (exact measurements)
 * @property uncertainty ± in the value's unit when known
 */
data class Quantity(
    val value: Double?,
    val unit: String,
    val kind: DataKind,
    val source: String,
    val timestamp: Instant?,
    val confidence: Double? = null,
    val uncertainty: Double? = null,
    val note: String? = null,
) {
    init {
        require(value == null || value.isFinite()) { "value must be finite" }
        require(confidence == null || confidence in 0.0..1.0) { "confidence must be 0..1" }
    }

    fun ageAt(now: Instant): Duration? = timestamp?.let { Duration.between(it, now) }

    /** Re-labels a measurement by its age: MEASURED → STALE when older than [maxAge]. */
    fun withFreshness(now: Instant, maxAge: Duration, connected: Boolean): Quantity {
        if (kind != DataKind.MEASURED && kind != DataKind.CALCULATED && kind != DataKind.STALE) return this
        if (!connected) return copy(kind = DataKind.LAST_KNOWN)
        val age = ageAt(now) ?: return this
        return if (age > maxAge) copy(kind = DataKind.STALE) else this
    }

    companion object {
        fun unavailable(unit: String, source: String, reason: String) =
            Quantity(null, unit, DataKind.UNAVAILABLE, source, null, note = reason)

        fun unknown(unit: String, source: String, reason: String? = null) =
            Quantity(null, unit, DataKind.UNKNOWN, source, null, note = reason)
    }
}
