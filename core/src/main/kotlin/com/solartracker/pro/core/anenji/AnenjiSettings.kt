package com.solartracker.pro.core.anenji

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Configuration parameters the analyzer understands. [register] is the Modbus address ONLY when it has been
 * confirmed on a real device; today none is confirmed (the community SMG documentation covers live data, and
 * settings addresses are not taken from unverified sources), so device reads return NOT_AVAILABLE.
 * [aliases] = normalized column names recognized in imported logs.
 */
enum class SettingKey(val label: String, val unit: String, val aliases: List<String>, val register: Int? = null) {
    BATTERY_TYPE("Typ baterii", "", listOf("batterytype", "battype")),
    BATTERY_NOMINAL_VOLTAGE("Napięcie nominalne baterii", "V", listOf("batterynominalvoltage", "nominalbatteryvoltage", "systemvoltage")),
    BATTERY_CAPACITY("Pojemność baterii", "Ah", listOf("batterycapacityah", "batterycapacitysetting", "capacityah")),
    BULK_VOLTAGE("Napięcie ładowania (bulk/absorpcja)", "V", listOf("bulkvoltage", "absorptionvoltage", "bulkchargingvoltage", "cvvoltage", "chargingvoltage")),
    FLOAT_VOLTAGE("Napięcie podtrzymania (float)", "V", listOf("floatvoltage", "floatingchargingvoltage", "floatchargevoltage")),
    LOW_VOLTAGE_CUTOFF("Napięcie odcięcia", "V", listOf("lowvoltagecutoff", "cutoffvoltage", "batterycutoffvoltage", "lowdcvoltagecutoff")),
    RECONNECT_VOLTAGE("Napięcie powrotu", "V", listOf("reconnectvoltage", "batteryreconnectvoltage", "backtobatteryvoltage")),
    MAX_CHARGE_CURRENT("Maks. prąd ładowania", "A", listOf("maxchargecurrent", "maxchargingcurrent", "maximumchargingcurrent")),
    MAX_AC_CHARGE_CURRENT("Maks. prąd ładowania z sieci", "A", listOf("maxacchargecurrent", "maxacchargingcurrent", "maximumacchargingcurrent", "utilitychargecurrent")),
    PV_CHARGE_LIMIT("Limit ładowania z PV", "A", listOf("pvchargelimit", "maxpvchargecurrent", "pvchargingcurrentlimit")),
    OUTPUT_VOLTAGE("Napięcie wyjściowe (nastawa)", "V", listOf("outputvoltagesetting", "acoutputratingvoltage", "ratedoutputvoltage")),
    OUTPUT_FREQUENCY("Częstotliwość wyjściowa (nastawa)", "Hz", listOf("outputfrequencysetting", "acoutputratingfrequency", "ratedoutputfrequency")),
    OUTPUT_SOURCE_PRIORITY("Priorytet źródła", "", listOf("outputsourcepriority", "sourcepriority", "outputpriority")),
    CHARGER_SOURCE_PRIORITY("Priorytet ładowania", "", listOf("chargersourcepriority", "chargingpriority", "chargerpriority")),
    BATTERY_PRIORITY("Priorytet baterii", "", listOf("batterypriority")),
    BYPASS("Bypass", "", listOf("bypass", "bypassenabled")),
    ECO_MODE("Tryb ECO / oszczędzania", "", listOf("ecomode", "powersaving", "powersavingmode")),
    TEMPERATURE_COMPENSATION("Kompensacja temperaturowa", "mV/°C", listOf("temperaturecompensation", "tempcompensation")),
    GENERATOR("Agregat", "", listOf("generator", "generatorenabled")),
    GRID_VOLTAGE_RANGE("Zakres napięcia sieci", "", listOf("gridvoltagerange", "acinputrange", "inputvoltagerange")),
    OVERLOAD_ALARM("Próg alarmu przeciążenia", "%", listOf("overloadalarm", "overloadthreshold")),
    ;

    companion object {
        fun forColumn(normalized: String): SettingKey? = entries.firstOrNull { k ->
            normalized in k.aliases || normalized == "setting" + k.name.lowercase().replace("_", "")
        }
    }
}

/** Where a setting value came from. Only DEVICE_REGISTER values can ever be VERIFIED. */
enum class SettingSource(val label: String) {
    DEVICE_REGISTER("odczyt z falownika"),
    IMPORTED_LOG("zaimportowany log"),
    APP_SETTINGS("ustawienia aplikacji (wprowadzone przez użytkownika)"),
}

enum class SettingStatus(val label: String) {
    VERIFIED("zweryfikowany"),
    UNVERIFIED("niezweryfikowany"),
    NOT_AVAILABLE("niedostępny"),
}

data class SettingValue(
    val key: SettingKey,
    val register: Int?,
    val rawValue: String?,
    val decodedValue: Double?,
    val text: String?,
    val unit: String,
    val source: SettingSource?,
    val status: SettingStatus,
    val note: String? = null,
) {
    val display: String get() = when {
        status == SettingStatus.NOT_AVAILABLE -> "NOT_AVAILABLE"
        text != null -> text
        decodedValue != null -> String.format(Locale.ROOT, if (decodedValue % 1.0 == 0.0) "%.0f" else "%.2f", decodedValue) + (if (unit.isNotEmpty()) " $unit" else "")
        else -> "NOT_AVAILABLE"
    }

    /** Value used for comparison between snapshots. */
    val comparable: String? get() = text ?: decodedValue?.let { String.format(Locale.ROOT, "%.4f", it) }
}

enum class SnapshotVerification(val label: String) { NONE("BRAK"), PARTIAL("CZĘŚCIOWA"), FULL("PEŁNA") }

/** "ANENJI CONFIGURATION SNAPSHOT": every known parameter, NOT_AVAILABLE when not readable. */
data class AnenjiSettingsSnapshot(val timestamp: Instant, val device: String, val values: List<SettingValue>) {
    fun value(key: SettingKey): SettingValue? = values.firstOrNull { it.key == key }
    fun number(key: SettingKey): Double? = value(key)?.takeIf { it.status != SettingStatus.NOT_AVAILABLE }?.decodedValue

    val verification: SnapshotVerification get() {
        val known = values.filter { it.status != SettingStatus.NOT_AVAILABLE }
        return when {
            known.isEmpty() || known.none { it.status == SettingStatus.VERIFIED } -> SnapshotVerification.NONE
            known.all { it.status == SettingStatus.VERIFIED } && known.size == values.size -> SnapshotVerification.FULL
            else -> SnapshotVerification.PARTIAL
        }
    }

    fun format(zone: ZoneId): String = buildString {
        appendLine("ANENJI CONFIGURATION SNAPSHOT")
        appendLine(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(timestamp))
        appendLine(device)
        appendLine()
        values.forEach { v ->
            appendLine("${v.key.label}: ${v.display}" + if (v.status != SettingStatus.NOT_AVAILABLE) " [${v.status.label}, ${v.source?.label}]" else "")
        }
        appendLine()
        append("Weryfikacja: ${verification.label}")
    }

    companion object {
        /**
         * Builds a snapshot from whatever is known. Device registers are used only for keys with a confirmed
         * [SettingKey.register] (none today). Imported and app values are UNVERIFIED by definition.
         */
        fun build(
            timestamp: Instant,
            device: String,
            deviceRegisters: Map<Int, Int> = emptyMap(),
            imported: Map<SettingKey, Double> = emptyMap(),
            importedText: Map<SettingKey, String> = emptyMap(),
            app: Map<SettingKey, Double> = emptyMap(),
            appText: Map<SettingKey, String> = emptyMap(),
        ): AnenjiSettingsSnapshot = AnenjiSettingsSnapshot(timestamp, device, SettingKey.entries.map { k ->
            val reg = k.register?.let { r -> deviceRegisters[r]?.let { r to it } }
            when {
                reg != null -> SettingValue(k, reg.first, reg.second.toString(), reg.second.toDouble(), null, k.unit, SettingSource.DEVICE_REGISTER, SettingStatus.VERIFIED)
                k in imported || k in importedText -> SettingValue(k, null, (importedText[k] ?: imported[k]).toString(), imported[k], importedText[k], k.unit,
                    SettingSource.IMPORTED_LOG, SettingStatus.UNVERIFIED)
                k in app || k in appText -> SettingValue(k, null, null, app[k], appText[k], k.unit, SettingSource.APP_SETTINGS, SettingStatus.UNVERIFIED,
                    "wartość z ustawień aplikacji, nie odczytana z falownika")
                else -> SettingValue(k, null, null, null, null, k.unit, null, SettingStatus.NOT_AVAILABLE,
                    if (k.register == null) "brak zweryfikowanego rejestru dla tego modelu" else "brak odczytu")
            }
        })
    }
}

data class SettingChange(val key: SettingKey, val old: SettingValue, val new: SettingValue, val seenBetween: Instant, val seenAt: Instant)

/** "AnenjiSettingsDiff": facts only – a change is reported with the time window it happened in, never with a cause. */
data class AnenjiSettingsDiff(val changes: List<SettingChange>) {
    val noChanges: Boolean get() = changes.isEmpty()

    fun describe(zone: ZoneId): String = if (noChanges) "Nie wykryto zmian konfiguracji" else buildString {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        changes.forEach { c ->
            appendLine("ZMIANA PARAMETRU: ${c.key.label}")
            appendLine("Poprzednio: ${c.old.display}")
            appendLine("Teraz: ${c.new.display}")
            appendLine("Zmieniono między ${f.format(c.seenBetween)} a ${f.format(c.seenAt)}")
        }
    }.trimEnd()

    companion object {
        /** Compares two snapshots; parameters NOT_AVAILABLE in either are not compared. */
        fun between(old: AnenjiSettingsSnapshot, new: AnenjiSettingsSnapshot): AnenjiSettingsDiff = AnenjiSettingsDiff(
            SettingKey.entries.mapNotNull { k ->
                val a = old.value(k) ?: return@mapNotNull null
                val b = new.value(k) ?: return@mapNotNull null
                if (a.status == SettingStatus.NOT_AVAILABLE || b.status == SettingStatus.NOT_AVAILABLE) return@mapNotNull null
                if (a.comparable == b.comparable) null else SettingChange(k, a, b, old.timestamp, new.timestamp)
            },
        )

        /** All changes along a chronological series of snapshots. */
        fun timeline(snapshots: List<AnenjiSettingsSnapshot>): AnenjiSettingsDiff =
            AnenjiSettingsDiff(snapshots.sortedBy { it.timestamp }.zipWithNext { a, b -> between(a, b).changes }.flatten())
    }
}
