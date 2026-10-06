package com.solartracker.pro.core.twin

import com.solartracker.pro.core.forecast.EnergyRisk
import com.solartracker.pro.core.forecast.EnergySecurity
import com.solartracker.pro.core.health.FaultSeverity
import com.solartracker.pro.core.health.FaultWarning
import com.solartracker.pro.core.health.LossCause
import com.solartracker.pro.core.health.PerformanceReport
import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.LinkStatus
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.quality.Quantity
import com.solartracker.pro.core.solar.SolarPosition
import java.time.Instant

enum class TwinElement(val label: String) { SUN("Słońce"), PV_ARRAY("Panele PV"), INVERTER("Falownik"), BATTERY("Bateria"), LOADS("Odbiorniki") }

enum class TwinHealth(val label: String) { OK("OK"), WARNING("UWAGA"), FAULT("PROBLEM"), UNKNOWN("NIEZNANY") }

/** One element of the chain Sun → PV → inverter → battery → loads with values by provenance. */
data class TwinNode(
    val element: TwinElement,
    val measured: Map<String, Quantity>,
    val calculated: Map<String, Quantity>,
    val forecast: Map<String, Quantity>,
    val health: TwinHealth,
    val healthNote: String?,
)

data class DigitalTwin(val time: Instant, val nodes: List<TwinNode>) {
    fun node(e: TwinElement): TwinNode = nodes.first { it.element == e }
}

/** Inputs are the outputs of the existing models; the twin only arranges them (no own telemetry). */
data class TwinInput(
    val time: Instant,
    val sun: SolarPosition?,
    val poaWm2: Double?,
    val weatherSource: String?,
    /** Validated (sanitized) reading. */
    val telemetry: InverterTelemetry?,
    val fresh: Boolean,
    val link: LinkStatus?,
    val modelPvKw: Double?,
    val forecastPvKw: Double?,
    val performance: PerformanceReport?,
    val security: EnergySecurity?,
    val loadForecastKw: Double?,
    val warnings: List<FaultWarning> = emptyList(),
)

object DigitalTwinBuilder {
    fun build(i: TwinInput): DigitalTwin {
        val t = i.time
        val kind = if (i.fresh) DataKind.MEASURED else DataKind.LAST_KNOWN
        val src = i.telemetry?.providerId ?: "falownik"
        fun m(v: Double?, unit: String) = v?.let { Quantity(it, unit, kind, src, i.telemetry?.timestamp) }
        fun c(v: Double?, unit: String, source: String) = v?.let { Quantity(it, unit, DataKind.CALCULATED, source, t) }
        fun e(v: Double?, unit: String, source: String) = v?.let { Quantity(it, unit, DataKind.ESTIMATED, source, t) }
        fun f(v: Double?, unit: String, source: String) = v?.let { Quantity(it, unit, DataKind.FORECAST, source, t) }
        fun <K, V> mapOfNotNull(vararg p: Pair<K, V?>): Map<K, V> = p.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        fun warn(prefix: String) = i.warnings.filter { it.id.startsWith(prefix) }
        fun healthOf(ws: List<FaultWarning>): TwinHealth? = when {
            ws.any { it.severity == FaultSeverity.CRITICAL } -> TwinHealth.FAULT
            ws.any { it.severity == FaultSeverity.WARNING } -> TwinHealth.WARNING
            else -> null
        }
        val tel = i.telemetry

        val sun = TwinNode(
            TwinElement.SUN, emptyMap(),
            mapOfNotNull("wysokość" to c(i.sun?.elevationDeg, "°", "położenie Słońca"), "azymut" to c(i.sun?.azimuthDeg, "°", "położenie Słońca")),
            mapOfNotNull("nasłonecznienie paneli" to e(i.poaWm2, "W/m²", i.weatherSource ?: "model")),
            TwinHealth.OK, i.weatherSource,
        )

        val unknown = i.performance?.losses?.firstOrNull { it.cause == LossCause.UNKNOWN }?.percent
        val pvHealth = healthOf(warn("pv-")) ?: when {
            tel == null || !i.fresh -> TwinHealth.UNKNOWN
            unknown != null && unknown > 25 -> TwinHealth.WARNING
            else -> TwinHealth.OK
        }
        val pv = TwinNode(
            TwinElement.PV_ARRAY,
            mapOfNotNull("moc" to m(tel?.pv?.powerW, "W"), "napięcie" to m(tel?.pv?.voltageV, "V"), "prąd" to m(tel?.pv?.currentA, "A")),
            mapOfNotNull("model" to e(i.modelPvKw?.times(1000), "W", "model PV"), "wydajność" to i.performance?.performancePercent?.let { Quantity(it, "%", DataKind.CALCULATED, "pomiar / oczekiwana", t) }),
            mapOfNotNull("prognoza teraz" to f(i.forecastPvKw?.times(1000), "W", "prognoza PV")),
            pvHealth, i.performance?.status,
        )

        val invHealth = when {
            i.link == LinkStatus.OFFLINE -> TwinHealth.FAULT
            tel?.inverter?.faults?.isNotEmpty() == true -> TwinHealth.FAULT
            else -> healthOf(warn("inverter") + warn("comm")) ?: if (tel == null) TwinHealth.UNKNOWN else if (i.link == LinkStatus.DEGRADED || tel.inverter.warnings.isNotEmpty()) TwinHealth.WARNING else TwinHealth.OK
        }
        val inverter = TwinNode(
            TwinElement.INVERTER,
            mapOfNotNull("moc wyjściowa" to m(tel?.inverter?.powerW, "W"), "temperatura" to m(tel?.inverter?.temperatureC, "°C"),
                "sieć" to m(tel?.grid?.powerW, "W")),
            emptyMap(), emptyMap(), invHealth,
            tel?.inverter?.let { inv -> (inv.faults + inv.warnings).joinToString { it.description }.ifEmpty { null } } ?: i.link?.name,
        )

        val s = i.security
        val batHealth = healthOf(warn("battery")) ?: when (s?.risk) {
            EnergyRisk.HIGH -> TwinHealth.WARNING
            null, EnergyRisk.UNKNOWN -> if (tel?.battery?.socPercent == null) TwinHealth.UNKNOWN else TwinHealth.OK
            else -> TwinHealth.OK
        }
        val battery = TwinNode(
            TwinElement.BATTERY,
            mapOfNotNull("SOC" to m(tel?.battery?.socPercent, "%"), "napięcie" to m(tel?.battery?.voltageV, "V"), "moc" to m(tel?.battery?.powerW, "W"),
                "temperatura" to m(tel?.battery?.temperatureC, "°C")),
            mapOfNotNull("bezpieczeństwo" to s?.securityPercent?.let { Quantity(it.toDouble(), "%", DataKind.FORECAST, "prognoza SOC", t, s.confidence) }),
            mapOfNotNull("najniższy SOC" to s?.predictedMinSoc?.let { Quantity(it.toDouble(), "%", DataKind.FORECAST, "prognoza SOC", t, s.confidence) }),
            batHealth, s?.explanation,
        )

        val loads = TwinNode(
            TwinElement.LOADS,
            mapOfNotNull("moc" to m(tel?.load?.powerW, "W")), emptyMap(),
            mapOfNotNull("prognoza teraz" to f(i.loadForecastKw?.times(1000), "W", "prognoza zużycia")),
            healthOf(warn("unexpected-load")) ?: if (tel == null) TwinHealth.UNKNOWN else TwinHealth.OK, null,
        )
        return DigitalTwin(t, listOf(sun, pv, inverter, battery, loads))
    }
}
