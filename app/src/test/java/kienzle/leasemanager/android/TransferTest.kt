package kienzle.leasemanager.android

import com.google.gson.JsonObject
import kienzle.leasemanager.android.transfer.LeasePackage
import kienzle.leasemanager.android.transfer.TransferException
import kienzle.leasemanager.android.transfer.readLeasePackage
import kienzle.leasemanager.android.transfer.sha256Hex
import kienzle.leasemanager.android.transfer.writeSignedReturn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class TransferTest {
    private val id = UUID.fromString("3f2b8c1e-5a47-4d0e-9a53-0c1d2e3f4a5b")
    private val pdf = "%PDF-fake".toByteArray()

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(hash: String = sha256Hex(pdf), schema: String = "leasepack:v1") = JsonObject().apply {
        addProperty("schemaVersion", schema)
        addProperty("contractId", id.toString())
        addProperty("createdAt", "2026-11-01T10:00:00Z")
        addProperty("originalPdfSha256", hash)
        addProperty("contractName", "Pacht Acker")
        addProperty("contractJson", "{}")
        addProperty("packageSignature", "00")
    }.toString().toByteArray()

    private fun pack(manifest: ByteArray = manifest()) =
        ByteArrayInputStream(archive(listOf("manifest.json" to manifest, "contract.pdf" to pdf)))

    @Test
    fun readsValidPackage() {
        val pkg = readLeasePackage(pack())
        assertEquals(id, pkg.contractId)
        assertEquals("Pacht Acker", pkg.contractName)
        assertArrayEquals(pdf, pkg.pdf)
    }

    @Test
    fun rejectsHashMismatch() {
        assertThrows(TransferException::class.java) { readLeasePackage(pack(manifest(hash = "0".repeat(64)))) }
    }

    @Test
    fun rejectsUnknownSchema() {
        assertThrows(TransferException::class.java) { readLeasePackage(pack(manifest(schema = "leasepack:v2"))) }
    }

    @Test
    fun rejectsUnexpectedEntries() {
        val data = archive(listOf("manifest.json" to manifest(), "contract.pdf" to pdf, "../evil" to byteArrayOf(1)))
        assertThrows(TransferException::class.java) { readLeasePackage(ByteArrayInputStream(data)) }
    }

    @Test
    fun rejectsMissingPdf() {
        val data = archive(listOf("manifest.json" to manifest()))
        assertThrows(TransferException::class.java) { readLeasePackage(ByteArrayInputStream(data)) }
    }

    @Test
    fun signedReturnContainsMatchingHashes() {
        val source = LeasePackage(id, "Pacht Acker", sha256Hex(pdf), pdf)
        val signed = "%PDF-signed".toByteArray()
        val out = ByteArrayOutputStream()
        writeSignedReturn(out, source, signed, "Max", "Android", java.time.Instant.parse("2026-11-01T10:00:00Z"))

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("manifest.json", "signed.pdf"), entries.keys)
        assertArrayEquals(signed, entries["signed.pdf"])
        val json = com.google.gson.JsonParser.parseString(String(entries["manifest.json"]!!)).asJsonObject
        assertEquals("leasesigned:v1", json["schemaVersion"].asString)
        assertEquals(id.toString(), json["contractId"].asString)
        assertEquals(source.originalPdfSha256, json["originalPdfSha256"].asString)
        assertEquals(sha256Hex(signed), json["signedPdfSha256"].asString)
        assertEquals("Max", json["signerName"].asString)
    }
}
