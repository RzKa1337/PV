package com.solartracker.pro.core.update

/**
 * Semantic version (https://semver.org): MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD].
 * A leading "v" (as in git tags) is accepted. Build metadata is ignored for ordering.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A release is newer than any pre-release of the same version.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers sort before alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String): SemanticVersion? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (ma, mi, pa, pre) = m.destructured
            val ids = if (pre.isEmpty()) emptyList() else pre.split('.')
            if (ids.any { it.isEmpty() }) return null
            return SemanticVersion(
                ma.toIntOrNull() ?: return null,
                mi.toIntOrNull() ?: return null,
                pa.toIntOrNull() ?: return null,
                ids,
            )
        }
    }
}
