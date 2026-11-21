package app.granger.core.books

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser

object DrmCheck {
    /** Algorithms that only obfuscate embedded fonts; these are allowed. */
    private val FONT_ALGORITHMS = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    /** @throws kotlinx.io.IOException if `encryption.xml` is corrupt or oversized. */
    fun check(zip: BookZip): DrmKind? {
        if (zip.has("META-INF/license.lcpl")) return DrmKind.LCP
        if (zip.has("META-INF/rights.xml")) return DrmKind.ADOBE
        val bytes = zip.readCapped("META-INF/encryption.xml", XML_LIMIT) ?: return null
        val xml = Ksoup.parse(html = xmlText(bytes), parser = Parser.xmlParser(), baseUri = "")
        // By local name: real files often write `<enc:EncryptionMethod>`, and the XML parser keeps the prefix.
        val protected = xml.getAllElements()
            .filter { it.tagName().substringAfterLast(':') == "EncryptionMethod" }
            .any { it.attr("Algorithm") !in FONT_ALGORITHMS }
        return if (protected) DrmKind.OTHER else null
    }
}
