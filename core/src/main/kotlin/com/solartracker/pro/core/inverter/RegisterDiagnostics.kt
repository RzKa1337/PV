package com.solartracker.pro.core.inverter

import java.time.Instant

/** Quality of one register reading, judged per sample (the static catalogue state is [RegisterValidation]). */
enum class RegisterQuality(val label: String) {
    /** Register confirmed on a real device and the value is within its physical range. */
    VERIFIED("zweryfikowany"),
    /** Plausible value, but the register map is not confirmed on the user's device. */
    UNVERIFIED("niezweryfikowany"),
    /** Plausible on its own, but inconsistent with other readings (validation issue on its field). */
    SUSPECTED("podejrzany"),
    /** "Not supported" marker or outside the physically possible range. */
    INVALID("nieprawidłowy"),
    /** The register was not read (protocol without registers, failed read). */
    NOT_AVAILABLE("niedostępny"),
}

/** One register as read from the device: raw word, decoded value and its quality. */
data class RegisterSample(
    val timestamp: Instant,
    val address: Int,
    val name: String,
    val raw: Int,
    val decoded: Double?,
    val unit: String,
    val expectedMin: Double?,
    val expectedMax: Double?,
    val quality: RegisterQuality,
    val note: String? = null,
    val scale: Double? = null,
    val signed: Boolean? = null,
)

/** One poll: link status and the register samples (empty when the read failed). */
data class RegisterLogRecord(
    val timestamp: Instant,
    val communicationOk: Boolean,
    val communication: String,
    val samples: List<RegisterSample>,
    /** True for the simulator – such a log must never be used to verify the register map. */
    val simulated: Boolean = false,
)

object RegisterDiagnostics {
    /** Decoded value of [spec] from its raw 16-bit word (two's complement for signed registers). */
    fun decode(spec: RegisterSpec, raw: Int): Double {
        val v = if (spec.signed) ModbusCodec.toSigned16(raw) else raw
        return v * spec.scale
    }

    /**
     * Quality of one reading. [issues] are the telemetry validation issues of the same read: a register feeding a
     * field with an issue is SUSPECTED even when its own value is in range.
     */
    /**
     * @param verified addresses confirmed against the real device in validation mode (see [com.solartracker.pro.core.inverter.ValidationMode]);
     *   VERIFIED is never derived from documentation or the simulator
     */
    fun quality(spec: RegisterSpec, raw: Int, issues: List<TelemetryIssue> = emptyList(), verified: Set<Int> = emptySet()): Pair<RegisterQuality, String?> {
        if (raw !in 0..0xFFFF) return RegisterQuality.INVALID to "wartość spoza 16 bitów"
        if (raw == 0xFFFF && !spec.signed) return RegisterQuality.INVALID to "0xFFFF – rejestr prawdopodobnie nieobsługiwany"
        if (raw == 0x8000 && spec.signed) return RegisterQuality.INVALID to "0x8000 – wartość nieobsługiwana"
        val value = decode(spec, raw)
        spec.expected?.let { r -> if (value !in r) return RegisterQuality.INVALID to "poza zakresem fizycznym ${r.start}…${r.endInclusive} ${spec.unit}" }
        spec.field?.let { f -> issues.firstOrNull { it.field == f }?.let { return RegisterQuality.SUSPECTED to it.detail } }
        return if (spec.validation == RegisterValidation.VERIFIED || spec.address in verified) RegisterQuality.VERIFIED to "potwierdzony na urządzeniu"
        else RegisterQuality.UNVERIFIED to null
    }

    /** Samples of the SMG live block plus the fault/warning status words. */
    fun smgSamples(blocks: SmgRawBlocks, timestamp: Instant, issues: List<TelemetryIssue> = emptyList(), verified: Set<Int> = emptySet()): List<RegisterSample> {
        val live = SmgRegisters.LIVE.map { spec ->
            val raw = blocks.live[spec.address - SmgRegisterMap.LIVE_START]
            val (q, note) = quality(spec, raw, issues, verified)
            RegisterSample(timestamp, spec.address, spec.name, raw, if (q == RegisterQuality.INVALID && raw in listOf(0xFFFF, 0x8000)) null else decode(spec, raw),
                spec.unit, spec.expected?.start, spec.expected?.endInclusive, q, note, spec.scale, spec.signed)
        }
        val status = listOf(SmgRegisterMap.FAULT_CODE to "Kod błędu (u32, starsze słowo)", SmgRegisterMap.FAULT_CODE + 1 to "Kod błędu (u32, młodsze słowo)",
            SmgRegisterMap.WARNING_CODE to "Kod ostrzeżenia (u32, starsze słowo)", SmgRegisterMap.WARNING_CODE + 1 to "Kod ostrzeżenia (u32, młodsze słowo)")
            .map { (address, name) ->
                val raw = blocks.status[address - SmgRegisterMap.STATUS_START]
                RegisterSample(timestamp, address, name, raw, raw.toDouble(), "bity", null, null, RegisterQuality.UNVERIFIED, null)
            }
        return status + live
    }

    /** Count of samples per quality across a log (for a quick summary in the UI). */
    fun summary(records: List<RegisterLogRecord>): Map<RegisterQuality, Int> =
        records.flatMap { it.samples }.groupingBy { it.quality }.eachCount()
}
