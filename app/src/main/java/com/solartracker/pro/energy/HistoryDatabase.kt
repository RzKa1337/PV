package com.solartracker.pro.energy

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.solartracker.pro.core.analytics.CalibrationObservation
import com.solartracker.pro.core.analytics.CalibrationSample
import com.solartracker.pro.core.analytics.SkyCondition
import com.solartracker.pro.core.analytics.ForecastHorizon
import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.inverter.OperatingMode
import java.time.Duration
import java.time.Instant

/**
 * Local telemetry history: 30 s rows (kept [HISTORY_DAYS]), 15 min summaries (kept [SUMMARY_DAYS]),
 * and calibration samples. Only aggregated rows are written – never every live reading.
 */
class HistoryDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        for (table in listOf(T_HISTORY, T_SUMMARY)) {
            db.execSQL(
                "CREATE TABLE $table (start INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL, samples INTEGER NOT NULL, " +
                    "pv REAL, pv_max REAL, load REAL, battery REAL, grid REAL, bat_v REAL, bat_a REAL, soc REAL, inv_t REAL, bat_t REAL, " +
                    "pv_kwh REAL NOT NULL, load_kwh REAL NOT NULL, imp_kwh REAL NOT NULL, exp_kwh REAL NOT NULL, chg_kwh REAL NOT NULL, dis_kwh REAL NOT NULL, " +
                    "mode TEXT, faults TEXT, warnings TEXT)",
            )
        }
        db.execSQL("CREATE TABLE $T_CALIB (time INTEGER PRIMARY KEY, real_kw REAL NOT NULL, model_kw REAL NOT NULL)")
        addCalibrationContext(db)
        createForecastTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createForecastTable(db)
        if (oldVersion < 3) addCalibrationContext(db)
    }

    /** v3: context of each calibration sample (AutoCalibration 3.0). Old rows keep NULL (= unknown). */
    private fun addCalibrationContext(db: SQLiteDatabase) {
        CALIB_CONTEXT_COLUMNS.forEach { (name, type) -> db.execSQL("ALTER TABLE $T_CALIB ADD COLUMN $name $type") }
    }

    private fun createForecastTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $T_FORECAST (hour INTEGER NOT NULL, horizon TEXT NOT NULL, kwh REAL NOT NULL, issued INTEGER NOT NULL, PRIMARY KEY (hour, horizon))")
    }

    /** Stores a PV forecast for the hour starting at [hour]; a later issue (still before the hour) replaces it. */
    fun putForecast(hour: Instant, horizon: ForecastHorizon, kwh: Double, issued: Instant) {
        if (!issued.isBefore(hour) || !kwh.isFinite()) return
        writableDatabase.insertWithOnConflict(T_FORECAST, null, ContentValues().apply {
            put("hour", hour.toEpochMilli()); put("horizon", horizon.name); put("kwh", kwh); put("issued", issued.toEpochMilli())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun forecasts(horizon: ForecastHorizon, from: Instant, to: Instant): Map<Instant, Double> =
        readableDatabase.query(T_FORECAST, arrayOf("hour", "kwh"), "horizon = ? AND hour >= ? AND hour < ?",
            arrayOf(horizon.name, from.toEpochMilli().toString(), to.toEpochMilli().toString()), null, null, "hour").use { c ->
            buildMap { while (c.moveToNext()) put(Instant.ofEpochMilli(c.getLong(0)), c.getDouble(1)) }
        }

    fun insert(sample: HistorySample, summary: Boolean = false) {
        writableDatabase.insertWithOnConflict(if (summary) T_SUMMARY else T_HISTORY, null, values(sample), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun history(from: Instant, to: Instant, summary: Boolean = false): List<HistorySample> =
        readableDatabase.query(if (summary) T_SUMMARY else T_HISTORY, null, "start >= ? AND start < ?",
            arrayOf(from.toEpochMilli().toString(), to.toEpochMilli().toString()), null, null, "start").use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    /** Newest history row, or null. */
    fun latest(): HistorySample? =
        readableDatabase.query(T_HISTORY, null, null, null, null, null, "start DESC", "1").use { c -> if (c.moveToFirst()) read(c) else null }

    fun addCalibration(s: CalibrationSample) {
        writableDatabase.insertWithOnConflict(T_CALIB, null, ContentValues().apply {
            put("time", s.time.toEpochMilli()); put("real_kw", s.realKw); put("model_kw", s.modelKw)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Stores a calibration observation with its context; [usable] = accepted by the global calibration. */
    fun addObservation(o: CalibrationObservation, usable: Boolean) {
        writableDatabase.insertWithOnConflict(T_CALIB, null, ContentValues().apply {
            put("time", o.time.toEpochMilli()); put("real_kw", o.realKw); put("model_kw", o.modelKw)
            put("usable", if (usable) 1 else 0); put("cell_t", o.cellTemperatureC); put("near_limit", if (o.nearLimit) 1 else 0)
            put("elev", o.sunElevationDeg); put("az", o.sunAzimuthDeg); put("cloud", o.cloudCoverPercent); put("poa", o.irradianceWm2)
            put("csi", o.clearSkyIndex); put("cond", o.condition.name); put("shade", o.shadingFactor)
            put("fault", if (o.fault) 1 else 0); put("link_ok", if (o.linkOk) 1 else 0); put("invalid", if (o.invalidTelemetry) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** All observations with context (including those excluded from learning, so exclusions can be counted). */
    fun observations(since: Instant): List<CalibrationObservation> =
        readableDatabase.query(T_CALIB, null, "time >= ?", arrayOf(since.toEpochMilli().toString()), null, null, "time").use { c ->
            fun d(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getDouble(it) }
            fun b(n: String, default: Boolean) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) default else c.getInt(it) == 1 }
            buildList {
                while (c.moveToNext()) add(CalibrationObservation(
                    time = Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("time"))), realKw = d("real_kw") ?: 0.0, modelKw = d("model_kw") ?: 0.0,
                    cellTemperatureC = d("cell_t"), nearLimit = b("near_limit", false), sunElevationDeg = d("elev"), sunAzimuthDeg = d("az"),
                    cloudCoverPercent = d("cloud"), irradianceWm2 = d("poa"), clearSkyIndex = d("csi"),
                    condition = c.getString(c.getColumnIndexOrThrow("cond"))?.let { n -> SkyCondition.entries.firstOrNull { it.name == n } } ?: SkyCondition.UNKNOWN,
                    shadingFactor = d("shade"), fault = b("fault", false), linkOk = b("link_ok", true), invalidTelemetry = b("invalid", false),
                ))
            }
        }

    fun calibration(since: Instant): List<CalibrationSample> =
        readableDatabase.query(T_CALIB, arrayOf("time", "real_kw", "model_kw"), "time >= ? AND (usable IS NULL OR usable = 1)",
            arrayOf(since.toEpochMilli().toString()), null, null, "time").use { c ->
            buildList { while (c.moveToNext()) add(CalibrationSample(Instant.ofEpochMilli(c.getLong(0)), c.getDouble(1), c.getDouble(2))) }
        }

    /** Removes old rows; called occasionally. */
    fun prune(now: Instant) {
        val db = writableDatabase
        db.delete(T_HISTORY, "start < ?", arrayOf(now.minus(Duration.ofDays(HISTORY_DAYS)).toEpochMilli().toString()))
        db.delete(T_SUMMARY, "start < ?", arrayOf(now.minus(Duration.ofDays(SUMMARY_DAYS)).toEpochMilli().toString()))
        db.delete(T_CALIB, "time < ?", arrayOf(now.minus(Duration.ofDays(60)).toEpochMilli().toString()))
        db.delete(T_FORECAST, "hour < ?", arrayOf(now.minus(Duration.ofDays(SUMMARY_DAYS)).toEpochMilli().toString()))
    }

    private fun values(s: HistorySample) = ContentValues().apply {
        put("start", s.start.toEpochMilli()); put("end_ms", s.end.toEpochMilli()); put("samples", s.samples)
        put("pv", s.pvW); put("pv_max", s.pvMaxW); put("load", s.loadW); put("battery", s.batteryW); put("grid", s.gridW)
        put("bat_v", s.batteryVoltageV); put("bat_a", s.batteryCurrentA); put("soc", s.socPercent)
        put("inv_t", s.inverterTemperatureC); put("bat_t", s.batteryTemperatureC)
        put("pv_kwh", s.pvEnergyKwh); put("load_kwh", s.loadEnergyKwh); put("imp_kwh", s.gridImportKwh); put("exp_kwh", s.gridExportKwh)
        put("chg_kwh", s.batteryChargeKwh); put("dis_kwh", s.batteryDischargeKwh)
        put("mode", s.mode?.name); put("faults", s.faultCodes.joinToString(",")); put("warnings", s.warningCodes.joinToString(","))
    }

    private fun read(c: Cursor): HistorySample {
        fun d(name: String): Double? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getDouble(it) }
        fun codes(name: String) = c.getString(c.getColumnIndexOrThrow(name)).orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()
        return HistorySample(
            start = Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("start"))),
            end = Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("end_ms"))),
            samples = c.getInt(c.getColumnIndexOrThrow("samples")),
            pvW = d("pv"), pvMaxW = d("pv_max"), loadW = d("load"), batteryW = d("battery"), gridW = d("grid"),
            batteryVoltageV = d("bat_v"), batteryCurrentA = d("bat_a"), socPercent = d("soc"),
            inverterTemperatureC = d("inv_t"), batteryTemperatureC = d("bat_t"),
            pvEnergyKwh = d("pv_kwh") ?: 0.0, loadEnergyKwh = d("load_kwh") ?: 0.0, gridImportKwh = d("imp_kwh") ?: 0.0,
            gridExportKwh = d("exp_kwh") ?: 0.0, batteryChargeKwh = d("chg_kwh") ?: 0.0, batteryDischargeKwh = d("dis_kwh") ?: 0.0,
            mode = c.getString(c.getColumnIndexOrThrow("mode"))?.let { m -> OperatingMode.entries.firstOrNull { it.name == m } },
            faultCodes = codes("faults"), warningCodes = codes("warnings"),
        )
    }

    companion object {
        const val NAME = "telemetry.db"
        const val VERSION = 3
        const val HISTORY_DAYS = 30L
        const val SUMMARY_DAYS = 730L
        private const val T_HISTORY = "history"
        private const val T_SUMMARY = "summary"
        private const val T_CALIB = "calibration"
        private const val T_FORECAST = "forecast"
        private val CALIB_CONTEXT_COLUMNS = listOf(
            "usable" to "INTEGER", "cell_t" to "REAL", "near_limit" to "INTEGER", "elev" to "REAL", "az" to "REAL", "cloud" to "REAL",
            "poa" to "REAL", "csi" to "REAL", "cond" to "TEXT", "shade" to "REAL", "fault" to "INTEGER", "link_ok" to "INTEGER", "invalid" to "INTEGER",
        )
    }
}
