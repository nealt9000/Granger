package app.granger.core.books

import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

enum class DrmKind { LCP, ADOBE, OTHER }

object DrmCheck {
    /** Algorithms that only obfuscate embedded fonts; these are allowed. */
    private val FONT_ALGORITHMS = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    /** @throws java.io.IOException if `encryption.xml` is corrupt or oversized. */
    fun check(zip: ZipFile): DrmKind? {
        if (zip.getEntry("META-INF/license.lcpl") != null) return DrmKind.LCP
        if (zip.getEntry("META-INF/rights.xml") != null) return DrmKind.ADOBE
        val entry = zip.getEntry("META-INF/encryption.xml") ?: return null
        val xml = Jsoup.parse(readCapped(zip, entry, XML_LIMIT).inputStream(), "UTF-8", "", Parser.xmlParser())
        val protected = xml.getElementsByTag("EncryptionMethod").any { it.attr("Algorithm") !in FONT_ALGORITHMS }
        return if (protected) DrmKind.OTHER else null
    }
}
