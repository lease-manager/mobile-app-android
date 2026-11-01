package kienzle.leasemanager.android.transfer

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Formats are specified in shared/libs/leasepack of the lease-manager workspace. */
const val PACKAGE_SCHEMA = "leasepack:v1"
const val SIGNED_SCHEMA = "leasesigned:v1"
const val MAX_MANIFEST_BYTES = 1024 * 1024
const val MAX_PDF_BYTES = 50 * 1024 * 1024

class TransferException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class LeasePackage(
    val contractId: UUID,
    val contractName: String,
    val originalPdfSha256: String,
    val pdf: ByteArray,
)

fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

/**
 * Reads a `.leasepack`. Only integrity (PDF hash) is checked; the HMAC signature is local to one
 * desktop installation and cannot be verified on a mobile device.
 */
fun readLeasePackage(input: InputStream): LeasePackage {
    val entries = readZip(input, mapOf("manifest.json" to MAX_MANIFEST_BYTES, "contract.pdf" to MAX_PDF_BYTES))
    val manifest = parseObject(entries["manifest.json"], "manifest.json")
    val schema = string(manifest, "schemaVersion")
    if (schema != PACKAGE_SCHEMA) throw TransferException("Unsupported package schema: $schema")
    val pdf = entries["contract.pdf"] ?: throw TransferException("Package does not contain contract.pdf")
    val expected = string(manifest, "originalPdfSha256")
    if (sha256Hex(pdf) != expected) throw TransferException("Package hash mismatch")
    return LeasePackage(
        contractId = parseUuid(string(manifest, "contractId")),
        contractName = string(manifest, "contractName"),
        originalPdfSha256 = expected,
        pdf = pdf,
    )
}

/** Writes a `.leasesigned` archive for the desktop app. */
fun writeSignedReturn(
    output: OutputStream,
    source: LeasePackage,
    signedPdf: ByteArray,
    signerName: String,
    deviceInfo: String,
    signedAt: Instant = Instant.now(),
) {
    if (signedPdf.size > MAX_PDF_BYTES) throw TransferException("Signed PDF is too large")
    val manifest = JsonObject().apply {
        addProperty("schemaVersion", SIGNED_SCHEMA)
        addProperty("contractId", source.contractId.toString())
        addProperty("originalPdfSha256", source.originalPdfSha256)
        addProperty("signedPdfSha256", sha256Hex(signedPdf))
        addProperty("signedAt", signedAt.toString())
        addProperty("signerName", signerName)
        addProperty("deviceInfo", deviceInfo)
    }
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("signed.pdf"))
        zip.write(signedPdf)
        zip.closeEntry()
    }
}

private fun readZip(input: InputStream, limits: Map<String, Int>): Map<String, ByteArray> {
    val result = HashMap<String, ByteArray>()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val limit = limits[entry.name]
            if (entry.isDirectory || limit == null) throw TransferException("Unexpected entry: ${entry.name}")
            if (result.containsKey(entry.name)) throw TransferException("Duplicate entry: ${entry.name}")
            result[entry.name] = readBounded(zip, limit, entry.name)
        }
    }
    return result
}

private fun readBounded(input: InputStream, limit: Int, name: String): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw TransferException("Entry exceeds the maximum size: $name")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun parseObject(data: ByteArray?, name: String): JsonObject {
    if (data == null) throw TransferException("Package does not contain $name")
    try {
        val element = JsonParser.parseString(String(data, Charsets.UTF_8))
        if (!element.isJsonObject) throw TransferException("$name must be a JSON object")
        return element.asJsonObject
    } catch (e: JsonParseException) {
        throw TransferException("$name is malformed", e)
    }
}

private fun string(obj: JsonObject, name: String): String {
    val element = obj.get(name)
    if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw TransferException("Manifest is missing string field: $name")
    }
    return element.asString
}

private fun parseUuid(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw TransferException("Manifest contains an invalid contractId", e)
    }
