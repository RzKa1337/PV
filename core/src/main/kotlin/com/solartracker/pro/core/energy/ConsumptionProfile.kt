package com.solartracker.pro.core.energy

/** Constant power [powerKw] from [startHour] (inclusive) to [endHour] (exclusive), hours 0..24. */
data class ConsumptionPeriod(val startHour: Int, val endHour: Int, val powerKw: Double)

/**
 * Household/vehicle consumption as average power for each hour of the day (index 0..23).
 * The same profile is used for every simulated day.
 */
class ConsumptionProfile private constructor(private val hourlyKw: DoubleArray) {

    fun powerKwAtHour(hour: Int): Double = hourlyKw[hour.coerceIn(0, 23)]

    val dailyKwh: Double get() = hourlyKw.sum()

    val hourlyPowerKw: List<Double> get() = hourlyKw.toList()

    override fun equals(other: Any?) = other is ConsumptionProfile && other.hourlyKw.contentEquals(hourlyKw)
    override fun hashCode() = hourlyKw.contentHashCode()
    override fun toString() = "ConsumptionProfile(${hourlyKw.joinToString()})"

    companion object {
        /** The same power for 24 hours, e.g. 0.5 kW. */
        fun constant(powerKw: Double): ConsumptionProfile {
            require(powerKw.isFinite() && powerKw >= 0.0) { "Power must be >= 0" }
            return ConsumptionProfile(DoubleArray(24) { powerKw })
        }

        /** Hours not covered by any period consume 0 kW. */
        fun fromPeriods(periods: List<ConsumptionPeriod>): ConsumptionProfile {
            val errors = validatePeriods(periods)
            require(errors.isEmpty()) { errors.joinToString() }
            val hourly = DoubleArray(24)
            for (p in periods) for (h in p.startHour until p.endHour) hourly[h] = p.powerKw
            return ConsumptionProfile(hourly)
        }

        /** Returns human-readable problems (Polish), empty when the periods are valid. */
        fun validatePeriods(periods: List<ConsumptionPeriod>): List<String> = buildList {
            if (periods.isEmpty()) add("Dodaj co najmniej jeden przedział")
            val covered = BooleanArray(24)
            periods.forEach { p ->
                val label = "%02d:00–%02d:00".format(p.startHour, p.endHour)
                when {
                    p.startHour !in 0..23 || p.endHour !in 1..24 || p.startHour >= p.endHour ->
                        add("$label: godzina początku musi być przed końcem (0–24)")
                    !p.powerKw.isFinite() || p.powerKw < 0.0 -> add("$label: moc musi być ≥ 0")
                    else -> for (h in p.startHour until p.endHour) {
                        if (covered[h]) {
                            add("$label: przedziały nachodzą na siebie")
                            break
                        }
                        covered[h] = true
                    }
                }
            }
        }
    }
}
