package com.solartracker.pro.core.inverter

import java.time.Instant
import kotlin.math.abs

/** Result of comparing one register with a value the user read on the inverter display / a reference meter. */
enum class ReferenceVerdict(val label: String) {
    MATCH("zgodny"),
    CLOSE("bliski"),
    SUSPECT("podejrzany"),
    WRONG_DECODING("błędne dekodowanie"),
}

/**
 * One manual reference for "ANENJI VALIDATION MODE": RAW REGISTER → DECODED VALUE → APPLICATION VALUE → EXPECTED PHYSICAL
 * VALUE (from the display). [fromRealDevice] = false for simulator data – such references never count for VERIFIED.
 */
data class ManualReference(
    val address: Int,
    val rawValue: Int,
    val decodedValue: Double,
    val applicationValue: Double?,
    val manualReferenceValue: Double,
    val manualReferenceUnit: String,
    val manualReferenceTimestamp: Instant,
    val fromRealDevice: Boolean,
)

data class ReferenceComparison(
    val reference: ManualReference,
    /** Reference converted to the register's unit. */
    val referenceInRegisterUnit: Double,
    val difference: Double,
    val relativeDifference: Double?,
    val verdict: ReferenceVerdict,
    /** Likely decoding error (scale ×10/÷10/×100, sign), when the numbers suggest one. */
    val hint: String?,
)

/** Evidence for one register built from all its references. */
data class RegisterEvidence(
    val address: Int,
    val comparisons: List<ReferenceComparison>,
    val status: RegisterQuality,
    val reason: String,
)

/**
 * ANENJI VALIDATION MODE. Read-only: compares decoded registers with values confirmed by the user. A register becomes
 * VERIFIED only with ≥ [MIN_MATCHES] MATCH references from the real device at ≥ 2 different values and no contradicting
 * reference; documentation and simulator data never make a register VERIFIED.
 */
object ValidationMode {
    const val MIN_MATCHES = 3
    /** Relative tolerance for MATCH (display rounding + time offset between reading and display). */
    const val MATCH_TOLERANCE = 0.03
    const val CLOSE_TOLERANCE = 0.10

    /** Converts a user-entered value to the register's unit (W↔kW, A↔mA, V↔mV). */
    fun toUnit(value: Double, from: String, to: String): Double? {
        val f = from.trim().lowercase(); val t = to.trim().lowercase()
        if (f == t || f.isEmpty() || t.isEmpty()) return value
        val factor = mapOf(("kw" to "w") to 1000.0, ("w" to "kw") to 0.001, ("ma" to "a") to 0.001, ("a" to "ma") to 1000.0,
            ("mv" to "v") to 0.001, ("v" to "mv") to 1000.0, ("kva" to "va") to 1000.0, ("va" to "kva") to 0.001)[f to t]
        return factor?.let { value * it }
    }

    fun compare(spec: RegisterSpec, ref: ManualReference): ReferenceComparison? {
        val expected = toUnit(ref.manualReferenceValue, ref.manualReferenceUnit, spec.unit) ?: return null
        val decoded = ref.decodedValue
        val diff = decoded - expected
        // The tolerance is never tighter than one register step (scale).
        val tol = maxOf(abs(expected) * MATCH_TOLERANCE, abs(spec.scale), 0.5 * abs(spec.scale) + 1e-9)
        val rel = if (abs(expected) > 1e-9) diff / expected else null
        val hint = decodingHint(decoded, expected, spec)
        val verdict = when {
            abs(diff) <= tol -> ReferenceVerdict.MATCH
            hint != null -> ReferenceVerdict.WRONG_DECODING
            rel != null && abs(rel) <= CLOSE_TOLERANCE -> ReferenceVerdict.CLOSE
            else -> ReferenceVerdict.SUSPECT
        }
        return ReferenceComparison(ref, expected, diff, rel, verdict, hint)
    }

    private fun decodingHint(decoded: Double, expected: Double, spec: RegisterSpec): String? {
        if (abs(expected) < 1e-6 || abs(decoded) < 1e-6) return null
        val ratio = decoded / expected
        fun near(x: Double) = abs(ratio - x) / abs(x) <= MATCH_TOLERANCE
        return when {
            near(10.0) -> "wartość 10× za duża – skala powinna być prawdopodobnie ${spec.scale / 10}"
            near(0.1) -> "wartość 10× za mała – skala powinna być prawdopodobnie ${spec.scale * 10}"
            near(100.0) -> "wartość 100× za duża – sprawdź skalę"
            near(0.01) -> "wartość 100× za mała – sprawdź skalę"
            near(-1.0) -> "odwrócony znak – sprawdź konwencję znaku / signed"
            !spec.signed && decoded > 30_000 * abs(spec.scale) && expected < 0 -> "rejestr powinien być ze znakiem (signed)"
            else -> null
        }
    }

    /** Status of a register from its references (simulator references are ignored). */
    fun evidence(spec: RegisterSpec, references: List<ManualReference>): RegisterEvidence {
        val real = references.filter { it.address == spec.address && it.fromRealDevice }
        val cmp = real.mapNotNull { compare(spec, it) }
        val ignored = references.count { it.address == spec.address && !it.fromRealDevice }
        val matches = cmp.filter { it.verdict == ReferenceVerdict.MATCH }
        val wrong = cmp.count { it.verdict == ReferenceVerdict.WRONG_DECODING }
        val suspect = cmp.count { it.verdict == ReferenceVerdict.SUSPECT }
        val distinct = matches.map { Math.round(it.referenceInRegisterUnit / maxOf(abs(spec.scale), 1e-9)) }.distinct().size
        val (status, reason) = when {
            cmp.isEmpty() -> RegisterQuality.UNVERIFIED to (if (ignored > 0) "tylko odczyty z symulatora – nie liczą się do weryfikacji" else "brak porównań z urządzeniem")
            wrong > 0 -> RegisterQuality.SUSPECTED to "błędne dekodowanie w $wrong porównaniach"
            suspect > 0 && suspect * 2 >= cmp.size -> RegisterQuality.SUSPECTED to "rozbieżne wartości w $suspect z ${cmp.size} porównań"
            matches.size >= MIN_MATCHES && distinct >= 2 && suspect == 0 -> RegisterQuality.VERIFIED to "${matches.size} zgodnych porównań z urządzeniem przy $distinct różnych wartościach"
            else -> RegisterQuality.UNVERIFIED to "za mało dowodów: zgodnych ${matches.size}/$MIN_MATCHES, różnych wartości $distinct/2"
        }
        return RegisterEvidence(spec.address, cmp, status, reason)
    }

    /** Addresses confirmed on the real device (input for [RegisterDiagnostics.quality]). */
    fun verifiedAddresses(references: List<ManualReference>): Set<Int> =
        SmgRegisters.LIVE.filter { evidence(it, references).status == RegisterQuality.VERIFIED }.map { it.address }.toSet()
}

/** JSON storage of manual display references (kept on the phone). A broken file gives an empty list. */
object ReferenceCodec {
    private val json = kotlinx.serialization.json.Json

    fun encode(list: List<ManualReference>): String = json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(list.map { r ->
        kotlinx.serialization.json.JsonObject(buildMap {
            put("address", kotlinx.serialization.json.JsonPrimitive(r.address))
            put("raw", kotlinx.serialization.json.JsonPrimitive(r.rawValue))
            put("decoded", kotlinx.serialization.json.JsonPrimitive(r.decodedValue))
            r.applicationValue?.let { put("app", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("manual", kotlinx.serialization.json.JsonPrimitive(r.manualReferenceValue))
            put("unit", kotlinx.serialization.json.JsonPrimitive(r.manualReferenceUnit))
            put("time", kotlinx.serialization.json.JsonPrimitive(r.manualReferenceTimestamp.toString()))
            put("real", kotlinx.serialization.json.JsonPrimitive(r.fromRealDevice))
        })
    }))

    fun decode(text: String): List<ManualReference> = runCatching { json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray }.getOrNull().orEmpty().mapNotNull { e ->
        val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        runCatching {
            ManualReference(s("address")!!.toInt(), s("raw")!!.toInt(), s("decoded")!!.toDouble(), s("app")?.toDoubleOrNull(), s("manual")!!.toDouble(),
                s("unit")!!, Instant.parse(s("time")!!), s("real")!!.toBooleanStrict())
        }.getOrNull()
    }
}
