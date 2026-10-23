package me.csepregi.pkmrec.update

/**
 * A simple semantic version (major.minor.patch). Release tags look like "v1.2.3".
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

        /** Parses "1.2.3", "v1.2.3" or "1.2.3-debug"; returns null for anything else. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.find(text.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
