package app.granger.core.books

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DrmCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun check(drm: Map<String, String>): DrmKind? =
        ZipFile(TestEpubs.build(tmp.root, drmFiles = drm)).use { DrmCheck.check(it) }

    private fun encryption(algorithm: String) =
        """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="$algorithm"/></EncryptedData></encryption>"""

    @Test fun lcpLicenseIsLcp() {
        assertThat(check(mapOf("META-INF/license.lcpl" to "{}"))).isEqualTo(DrmKind.LCP)
    }

    @Test fun adobeRightsIsAdobe() {
        assertThat(check(mapOf("META-INF/rights.xml" to "<rights/>"))).isEqualTo(DrmKind.ADOBE)
    }

    @Test fun fontObfuscationIsAllowed() {
        val xml = encryption("http://www.idpf.org/2008/embedding")
        assertThat(check(mapOf("META-INF/encryption.xml" to xml))).isNull()
        val adobeFonts = encryption("http://ns.adobe.com/pdf/enc#RC")
        assertThat(check(mapOf("META-INF/encryption.xml" to adobeFonts))).isNull()
    }

    @Test fun otherEncryptionIsProtected() {
        val xml = encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc")
        assertThat(check(mapOf("META-INF/encryption.xml" to xml))).isEqualTo(DrmKind.OTHER)
    }

    @Test fun plainEpubIsClean() {
        assertThat(check(emptyMap())).isNull()
    }
}
