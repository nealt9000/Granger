package app.granger.packs

import app.granger.catalogOf
import app.granger.core.downloads.UNVERIFIED_PREFIX
import app.granger.packEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackImportTest {
    // The first 24 bytes of devdocs_en_sanctuary-type-classes_2026-05.zim, fetched 2026-11-03.
    private val header = byteArrayOf(0x5a, 0x49, 0x4d, 0x04, 6, 0, 3, 0) +
        "027a0fa59ecddc06364de56dd4b65588".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun readsTheUuidAsTheCatalogWritesIt() {
        assertThat(PackImport.uuidOf(header)).isEqualTo("027a0fa5-9ecd-dc06-364d-e56dd4b65588")
    }

    @Test fun anythingElseIsNotAZim() {
        assertThat(PackImport.uuidOf("PK\u0003\u0004 not a zim at all...".toByteArray())).isNull()
        assertThat(PackImport.uuidOf(header.copyOf(10))).isNull()
    }

    @Test fun aListedPackIsFiledAsItsCatalogEntryFromTheFile() {
        val pack = packEntry("devdocs").copy(zimUuid = "027a0fa5-9ecd-dc06-364d-e56dd4b65588", sizeBytes = 5_000)
        val picked = PackImport.Picked("content://sd/devdocs.zim", "devdocs.zim", 5_000, header)
        val spec = PackImport.spec(picked, PackImport.uuidOf(header)!!, catalogOf(listOf(pack)))
        assertThat(spec.itemId).isEqualTo("devdocs")
        assertThat(spec.hash).isEqualTo(pack.sha256)
        assertThat(spec.urls).containsExactly("content://sd/devdocs.zim")
    }

    @Test fun anotherSizeOrAnUnlistedZimIsKeptUnderItsOwnNameUnchecked() {
        val pack = packEntry("devdocs").copy(zimUuid = "027a0fa5-9ecd-dc06-364d-e56dd4b65588", sizeBytes = 5_000)
        val picked = PackImport.Picked("content://sd/x.zim", "wikipedia_sw_all (1).zim", 9_000, header)
        val spec = PackImport.spec(picked, PackImport.uuidOf(header)!!, catalogOf(listOf(pack)))
        assertThat(spec.itemId).isEqualTo("import:027a0fa5-9ecd-dc06-364d-e56dd4b65588")
        assertThat(spec.hash).startsWith(UNVERIFIED_PREFIX)
        assertThat(spec.title).isEqualTo("wikipedia_sw_all (1)")
        assertThat(spec.relativePath).isEqualTo("packs/wikipedia_sw_all__1_.zim")
        assertThat(spec.sizeBytes).isEqualTo(9_000)
    }
}
