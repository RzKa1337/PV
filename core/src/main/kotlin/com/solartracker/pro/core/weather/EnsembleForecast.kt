package com.solartracker.pro.core.weather

import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Locale
import kotlin.math.cos

/**
 * An ensemble weather forecast: the same numerical model run many times from slightly different starting
 * conditions ("members"). Each member is a plausible weather scenario; the spread between members is the
 * provider's own measure of how uncertain the weather is – wide where clouds are hard to place, narrow on
 * settled days. Members carry only what the ensemble provides (irradiance, temperature, cloud cover).
 *
 * @property model provider model id (e.g. `icon_seamless_eps`)
 * @property members one hourly forecast per member, the control run first when present
 * @property diffuseFromModel true when the provider gave diffuse irradiance; false = split from GHI with [Erbs]
 */
class EnsembleForecast(
    val model: String,
    val fetchedAt: Instant,
    val members: List<WeatherForecast>,
    val zone: java.time.ZoneId? = null,
    val diffuseFromModel: Boolean = true,
) {
    init {
        require(members.isNotEmpty()) { "Ensemble without members" }
    }

    val size: Int get() = members.size

    /** Last hour covered by at least half of the members. */
    val coversUntil: Instant? get() = members.mapNotNull { it.coversUntil }.sorted().let { it.getOrNull(it.size / 2) }

    /** Readable model name for the UI. */
    val label: String get() = OpenMeteoEnsemble.label(model)
}

/**
 * Erbs, Klein & Duffie (1982) diffuse fraction of global horizontal irradiance from the clearness index
 * kt = GHI / (E0 · cos z). Used only when an ensemble member has no diffuse irradiance of its own.
 */
object Erbs {
    fun diffuseFraction(kt: Double): Double = when {
        kt <= 0.22 -> 1.0 - 0.09 * kt
        kt <= 0.80 -> 0.9511 - 0.1604 * kt + 4.388 * kt * kt - 16.638 * kt * kt * kt + 12.336 * kt * kt * kt * kt
        else -> 0.165
    }.coerceIn(0.0, 1.0)
}

/**
 * Open-Meteo Ensemble API (https://open-meteo.com/en/docs/ensemble-api, CC BY 4.0, no key). Members come back as
 * `variable` (control run) and `variable_memberNN`; a model suffix (when several models are requested) is ignored.
 */
object OpenMeteoEnsemble {

    /**
     * Tried in this order. DWD ICON seamless EPS: ICON-D2-EPS (2 km, 20 members) → ICON-EU-EPS (13 km, 40 members) →
     * ICON-EPS (global, 40 members), ~7 days. ECMWF IFS 0.25° ensemble (51 members, 15 days) as the fallback.
     */
    val MODELS = listOf("icon_seamless_eps", "ecmwf_ifs025")

    /** Days requested: the ICON ensembles reach ~7.5 days; ECMWF further, but its spread is what matters beyond that. */
    const val DAYS = 7

    private const val VARIABLES = "shortwave_radiation,diffuse_radiation,temperature_2m,cloud_cover"

    /** Fewer usable members than this is not an ensemble. */
    const val MIN_MEMBERS = 5

    fun label(model: String): String = when (model) {
        "icon_seamless_eps" -> "DWD ICON-EPS"
        "ecmwf_ifs025" -> "ECMWF IFS ENS"
        else -> model
    }

    fun url(location: GeoLocation, model: String, days: Int = DAYS): String =
        "https://ensemble-api.open-meteo.com/v1/ensemble?latitude=${coord(location.latitude)}" +
            "&longitude=${coord(location.longitude)}&hourly=$VARIABLES&models=$model" +
            "&forecast_days=$days&timezone=auto&timeformat=unixtime"

    private fun coord(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    /** One variable of one member, e.g. `shortwave_radiation_member07` → ("shortwave_radiation", 7); control → 0. */
    internal fun splitKey(key: String, variables: Collection<String>): Pair<String, Int>? {
        val variable = variables.filter { key == it || key.startsWith(it + "_") }.maxByOrNull { it.length } ?: return null
        val rest = key.removePrefix(variable)
        // Whatever else follows the variable is a model suffix (several models in one request) and is ignored.
        val member = Regex("_member(\\d+)").find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return variable to member
    }

    /**
     * @param location used to split beam/diffuse (hour-mean sun geometry) consistently with [WeatherAwareIrradianceModel]
     * @throws IllegalArgumentException when the response is not a usable ensemble
     */
    fun parse(json: String, fetchedAt: Instant, location: GeoLocation, model: String): EnsembleForecast {
        val element = try { Json.parseToJsonElement(json) } catch (e: Exception) { throw IllegalArgumentException("Invalid JSON", e) }
        val root = element as? JsonObject ?: throw IllegalArgumentException("JSON object expected")
        if ((root["error"] as? JsonPrimitive)?.content == "true") throw IllegalArgumentException("API error: ${(root["reason"] as? JsonPrimitive)?.content}")
        val hourly = root["hourly"] as? JsonObject ?: throw IllegalArgumentException("Missing 'hourly'")
        val time = (hourly["time"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull } ?: throw IllegalArgumentException("Missing 'time'")
        val zone = (root["timezone"] as? JsonPrimitive)?.content?.takeIf { it != "GMT" && it != "UTC" }
            ?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        val names = listOf("shortwave_radiation", "diffuse_radiation", "temperature_2m", "cloud_cover")
        // member → variable → values
        val byMember = sortedMapOf<Int, MutableMap<String, List<Double?>>>()
        for ((key, value) in hourly) {
            val (variable, member) = splitKey(key, names) ?: continue
            val arr = value as? JsonArray ?: continue
            val values = List(time.size) { i -> arr.getOrNull(i)?.let { if (it is JsonNull) null else (it as? JsonPrimitive)?.doubleOrNull } }
            byMember.getOrPut(member) { mutableMapOf() }[variable] = values
        }
        var anyDiffuse = false
        val members = byMember.values.mapNotNull { vars ->
            val ghi = vars["shortwave_radiation"] ?: return@mapNotNull null
            val dhiModel = vars["diffuse_radiation"]
            if (dhiModel?.any { it != null } == true) anyDiffuse = true
            val hours = time.indices.mapNotNull { i ->
                val t = time[i] ?: return@mapNotNull null
                val end = Instant.ofEpochSecond(t)
                val (dni, dhi) = ghi[i]?.let { split(location, end, it.coerceAtLeast(0.0), dhiModel?.get(i)) } ?: (null to null)
                HourlyWeather(
                    endTime = end,
                    ghi = ghi[i]?.coerceAtLeast(0.0),
                    dni = dni,
                    dhi = dhi,
                    temperatureC = vars["temperature_2m"]?.get(i),
                    cloudCoverPercent = vars["cloud_cover"]?.get(i)?.coerceIn(0.0, 100.0),
                )
            }
            hours.takeIf { h -> h.count { it.hasIrradiance } >= 24 }?.let { WeatherForecast(it, fetchedAt, zone) }
        }
        require(members.size >= MIN_MEMBERS) { "Only ${members.size} usable ensemble members" }
        return EnsembleForecast(model, fetchedAt, members, zone, diffuseFromModel = anyDiffuse)
    }

    /**
     * Hour-mean GHI (and DHI when given) → (DNI, DHI) for the hour ending at [end], using the hour-mean cos z exactly
     * like [WeatherAwareIrradianceModel] recombines them (GHI = DNI·⟨cos z⟩ + DHI), so the member's GHI is preserved.
     * Without DHI the Erbs correlation splits GHI using the hour's clearness index.
     */
    internal fun split(location: GeoLocation, end: Instant, ghi: Double, dhiGiven: Double?): Pair<Double, Double> {
        val samples = 6
        var cosSum = 0.0
        var e0Sum = 0.0
        for (k in 0 until samples) {
            val t = end.minusSeconds(3600L - (k * 3600L + 1800L) / samples)
            val pos = SolarCalculator.position(location, t)
            if (pos.isAboveHorizon) {
                val c = cos(Math.toRadians(pos.zenithDeg))
                cosSum += c
                e0Sum += ClearSkyModel.extraterrestrialIrradiance(t) * c
            }
        }
        val meanCos = cosSum / samples
        if (ghi <= 0.0) return 0.0 to 0.0
        // Sun (almost) below the horizon for the whole hour: all diffuse, no beam from a division by ~0.
        if (meanCos < MIN_MEAN_COS) return 0.0 to ghi
        val dhi = (dhiGiven?.coerceIn(0.0, ghi)) ?: run {
            val kt = (ghi / (e0Sum / samples)).coerceIn(0.0, 1.0)
            ghi * Erbs.diffuseFraction(kt)
        }
        val dni = ((ghi - dhi) / meanCos).coerceIn(0.0, ClearSkyModel.extraterrestrialIrradiance(end))
        return dni to (ghi - dni * meanCos)
    }

    private const val MIN_MEAN_COS = 0.05
}
