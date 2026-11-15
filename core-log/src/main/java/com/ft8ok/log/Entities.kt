package com.ft8ok.log

/**
 * A DXCC entity, or one of the extra entities cty.dat carries (CQ WAE, marked `*`).
 *
 * Longitude is **east-positive** here. cty.dat itself writes west as positive, and
 * that is converted on reading so nothing downstream has to remember it.
 */
data class Entity(
    val name: String,
    val cqZone: Int,
    val ituZone: Int,
    val continent: String,
    val lat: Double,
    val lon: Double,
    /** The entity's main prefix, without cty.dat's `*` marker. */
    val prefix: String,
    /** A CQ-WAE-only entity (`*` in cty.dat), not on the DXCC list. */
    val waeOnly: Boolean
)

/**
 * Callsign to entity (ADR 0012). An interface so the bundled table stays swappable
 * for a self-built one, and so anything that uses it can be tested with no file.
 */
fun interface EntityLookup {
    fun entityOf(call: String): Entity?

    companion object {
        /** Knows nothing: what the app uses until a table is loaded. */
        val NONE = EntityLookup { null }
    }
}

/**
 * cty.dat, the country file maintained by Jim Reisert AD1C (country-files.com, MIT;
 * see THIRD_PARTY_LICENSES.md).
 *
 * Format, one record per entity: a header line of eight colon-terminated fields
 * (name, CQ zone, ITU zone, continent, latitude, longitude with west positive, UTC
 * offset, primary prefix), then a comma-separated list of prefixes ended by `;`. A
 * leading `=` makes an entry a whole callsign rather than a prefix. An entry may
 * override the entity's zones and continent with `(cq)`, `[itu]` and `{cont}`, and
 * its position and time zone with `<lat/lon>` and `~offset~`; the zone, continent
 * and position overrides are applied, the time zone is not used.
 */
class CtyDat private constructor(
    private val exact: Map<String, Entity>,
    private val prefixes: Map<String, Entity>,
    private val longestPrefix: Int,
    val entityCount: Int
) : EntityLookup {

    override fun entityOf(call: String): Entity? {
        val c = call.trim().uppercase()
        if (c.isEmpty() || c.startsWith("<")) return null
        exact[c]?.let { return it }
        val key = lookupKey(c) ?: return null
        exact[key]?.let { return it }
        for (len in minOf(key.length, longestPrefix) downTo 1) {
            prefixes[key.substring(0, len)]?.let { return it }
        }
        return null
    }

    companion object {

        private val SUFFIXES_TO_DROP = setOf("P", "M", "MM", "AM", "QRP", "A", "B", "R", "LH", "J")

        /**
         * What to look up for a callsign with a slash. Operating suffixes (`/P`,
         * `/M`, `/QRP` and the like) and a call-area digit (`W1AW/7`) do not change
         * the entity. Otherwise the shorter part is the prefix: `KH6/W1AW` and
         * `W1AW/KH6` are both Hawaii. Deliberately simple; cty.dat's exact entries
         * cover the well-known exceptions.
         */
        internal fun lookupKey(call: String): String? {
            val parts = call.split('/').filter { it.isNotEmpty() }.toMutableList()
            if (parts.isEmpty()) return null
            while (parts.size > 1 && (parts.last() in SUFFIXES_TO_DROP || parts.last().matches(Regex("\\d")))) {
                parts.removeAt(parts.size - 1)
            }
            return when (parts.size) {
                1 -> parts[0]
                else -> parts.minByOrNull { it.length }
            }
        }

        fun parse(text: String): CtyDat {
            val exact = HashMap<String, Entity>()
            val prefixes = HashMap<String, Entity>()
            var longest = 0
            var count = 0
            var current: Entity? = null
            val pending = StringBuilder()

            fun flush() {
                val base = current ?: return
                for (raw in pending.split(',')) {
                    var token = raw.trim()
                    if (token.isEmpty()) continue
                    var e = base
                    Regex("\\((\\d+)\\)").find(token)?.let { e = e.copy(cqZone = it.groupValues[1].toInt()) }
                    Regex("\\[(\\d+)\\]").find(token)?.let { e = e.copy(ituZone = it.groupValues[1].toInt()) }
                    Regex("\\{([A-Z]{2})\\}").find(token)?.let { e = e.copy(continent = it.groupValues[1]) }
                    Regex("<(-?[\\d.]+)/(-?[\\d.]+)>").find(token)?.let {
                        e = e.copy(lat = it.groupValues[1].toDouble(), lon = -it.groupValues[2].toDouble())
                    }
                    token = token.replace(Regex("\\(\\d+\\)|\\[\\d+\\]|\\{[A-Z]{2}\\}|<[^>]*>|~[^~]*~"), "")
                    if (token.startsWith("=")) {
                        exact[token.substring(1)] = e
                    } else {
                        prefixes[token] = e
                        if (token.length > longest) longest = token.length
                    }
                }
                pending.clear()
            }

            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                if (!line.startsWith(" ") && !line.startsWith("\t")) {
                    flush()
                    val f = line.split(':').map { it.trim() }
                    require(f.size >= 8) { "cty.dat: bad entity line: $line" }
                    val prefix = f[7]
                    current = Entity(
                        name = f[0],
                        cqZone = f[1].toInt(),
                        ituZone = f[2].toInt(),
                        continent = f[3],
                        lat = f[4].toDouble(),
                        lon = -f[5].toDouble(),
                        prefix = prefix.removePrefix("*"),
                        waeOnly = prefix.startsWith("*")
                    )
                    count++
                } else {
                    val body = line.trim()
                    pending.append(body.removeSuffix(";")).append(',')
                    if (body.endsWith(";")) flush()
                }
            }
            flush()
            return CtyDat(exact, prefixes, longest, count)
        }
    }
}
