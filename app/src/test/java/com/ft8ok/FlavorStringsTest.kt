package com.ft8ok

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FT4OK must never tell its operator about FT8. Every string in
 * src/main that names FT8 or FT8OK needs an override of the same name in src/ft4, and
 * no override may still say FT8.
 */
class FlavorStringsTest {

    private val entry = Regex("""<string name="([^"]+)">(.*?)</string>""")

    private fun strings(path: String): Map<String, String> {
        // Unit tests run from the module directory.
        val file = File(path)
        assertTrue("missing $path (run from :app)", file.exists())
        return entry.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `every string naming ft8 has an ft4 version that does not`() {
        val main = strings("src/main/res/values/strings.xml")
        val ft4 = strings("src/ft4/res/values/strings.xml")
        val naming = main.filterValues { it.contains("FT8") }.keys
        val missing = naming - ft4.keys
        assertTrue("strings naming FT8 with no FT4OK version: $missing", missing.isEmpty())
        val stillFt8 = ft4.filterValues { it.contains("FT8") }.keys
        assertTrue("FT4OK strings still saying FT8: $stillFt8", stillFt8.isEmpty())
    }
}
