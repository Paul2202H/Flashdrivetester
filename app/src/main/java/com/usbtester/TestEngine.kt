
package com.usbtester

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext
import kotlin.math.min

data class TestProgress(
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String = "",
    val phase: String = "Idle",
    val writeBytesPerSecond: Double = 0.0,
    val readBytesPerSecond: Double = 0.0,
    val successfulFiles: Int = 0,
    val failedFiles: Int = 0,
    val errors: List<String> = emptyList()
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f
        else (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

data class TestFileRecord(val name: String, val bytes: Long, val sha256: String)

data class TestResult(
    val status: String,
    val testedBytes: Long,
    val verifiedBytes: Long,
    val corruptedBytes: Long,
    val failedFiles: Int,
    val successfulFiles: Int,
    val writeBytesPerSecond: Double,
    val readBytesPerSecond: Double,
    val durationMillis: Long,
    val errors: List<String>,
    val createdFiles: List<String>
)

class TestEngine(private val context: Context) {

    companion object {
        const val MIB = 1024L * 1024L
        private const val CHUNK_BYTES = 4 * 1024 * 1024
        private const val IO_BUFFER = 256 * 1024
    }

    private val random = SecureRandom()

    suspend fun run(
        folderUri: Uri,
        requestedBytes: Long,
        onProgress: (TestProgress) -> Unit
    ): TestResult = withContext(Dispatchers.IO) {

        require(requestedBytes > 0) {
            "Test size must be greater than zero."
        }

        val start = System.nanoTime()

        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IllegalArgumentException(
                "The selected folder is no longer accessible."
            )

        require(folder.isDirectory && folder.canWrite()) {
            "The selected folder is not writable."
        }

        val records = mutableListOf<TestFileRecord>()
        val created = mutableListOf<String>()
        val errors = mutableListOf<String>()

        var bytesDone = 0L
        var verifiedBytes = 0L
        var corruptBytes = 0L
        var writeNanos = 0L
        var readNanos = 0L
        var successful = 0
        var failed = 0

        // Write distinct pseudorandom test files.
        var remaining = requestedBytes

        while (remaining > 0) {
            coroutineContext.ensureActive()

            val amount = min(remaining, CHUNK_BYTES.toLong())
            val name = "usbtester_${System.currentTimeMillis()}_${records.size}.bin"

            val doc = folder.createFile("application/octet-stream", name)
                ?: throw IOException("Could not create $name. The drive may be full.")

            val actualName = doc.name ?: name
            created += actualName

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(IO_BUFFER)
            var fileWritten = 0L
            val writeStart = System.nanoTime()

            try {
                val outputStream = context.contentResolver
                    .openOutputStream(doc.uri, "w")
                    ?: throw IOException("Could not open $actualName for writing.")

                BufferedOutputStream(outputStream, IO_BUFFER).use { out ->
                    while (fileWritten < amount) {
                        coroutineContext.ensureActive()

                        val count = min(
                            buffer.size.toLong(),
                            amount - fileWritten
                        ).toInt()

                        random.nextBytes(buffer)
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)

                        fileWritten += count
                        bytesDone += count

                        onProgress(
                            TestProgress(
                                bytesDone = bytesDone,
                                totalBytes = requestedBytes,
                                currentFile = actualName,
                                phase = "Writing",
                                writeBytesPerSecond = rate(
                                    bytesDone,
                                    System.nanoTime() - start
                                ),
                                successfulFiles = successful,
                                failedFiles = failed,
                                errors = errors.toList()
                            )
                        )
                    }

                    out.flush()
                }

                writeNanos += System.nanoTime() - writeStart

                records += TestFileRecord(
                    actualName,
                    amount,
                    digest.digest().toHex()
                )

            } catch (t: Throwable) {
                failed++
                errors += "Write failed for $actualName: ${t.message ?: t.javaClass.simpleName}"

                onProgress(
                    TestProgress(
                        bytesDone = bytesDone,
                        totalBytes = requestedBytes,
                        currentFile = actualName,
                        phase = "Write error",
                        failedFiles = failed,
                        errors = errors.toList()
                    )
                )

                throw t
            }

            remaining -= amount
        }

        // Read back and verify every file.
        var readTotal = 0L

        for (record in records) {
            coroutineContext.ensureActive()

            // FIX: Use a normal if statement instead of continue inside run.
            val doc = folder.findFile(record.name)

            if (doc == null) {
                failed++
                errors += "Missing file during verification: ${record.name}"
                continue
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var fileRead = 0L
            val readStart = System.nanoTime()

            try {
                val inputStream = context.contentResolver
                    .openInputStream(doc.uri)
                    ?: throw IOException("Could not open ${record.name} for reading.")

                BufferedInputStream(inputStream, IO_BUFFER).use { input ->
                    val buffer = ByteArray(IO_BUFFER)

                    while (true) {
                        coroutineContext.ensureActive()

                        val n = input.read(buffer)

                        if (n < 0) break

                        if (fileRead + n > record.bytes) {
                            corruptBytes += fileRead + n - record.bytes
                            errors += "Unexpected extra data in ${record.name}"
                        }

                        digest.update(buffer, 0, n)
                        fileRead += n
                        readTotal += n

                        onProgress(
                            TestProgress(
                                bytesDone = requestedBytes + readTotal,
                                totalBytes = requestedBytes * 2,
                                currentFile = record.name,
                                phase = "Verifying",
                                writeBytesPerSecond = rate(
                                    requestedBytes,
                                    writeNanos
                                ),
                                readBytesPerSecond = rate(
                                    readTotal,
                                    System.nanoTime() - readStart
                                ),
                                successfulFiles = successful,
                                failedFiles = failed,
                                errors = errors.toList()
                            )
                        )
                    }
                }

                readNanos += System.nanoTime() - readStart

                if (
                    fileRead != record.bytes ||
                    digest.digest().toHex() != record.sha256
                ) {
                    failed++

                    corruptBytes += min(fileRead, record.bytes)

                    errors += "Verification mismatch: ${record.name} " +
                        "(expected ${record.bytes} bytes, read $fileRead)"
                } else {
                    successful++
                    verifiedBytes += record.bytes
                }

            } catch (t: Throwable) {
                failed++
                errors += "Read failed for ${record.name}: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        val duration = (System.nanoTime() - start) / 1_000_000

        val status = when {
            failed > 0 || corruptBytes > 0 -> "FAIL"
            verifiedBytes == requestedBytes -> "PASS"
            else -> "WARNING"
        }

        onProgress(
            TestProgress(
                bytesDone = requestedBytes * 2,
                totalBytes = requestedBytes * 2,
                phase = "Complete",
                writeBytesPerSecond = rate(requestedBytes, writeNanos),
                readBytesPerSecond = rate(readTotal, readNanos),
                successfulFiles = successful,
                failedFiles = failed,
                errors = errors.toList()
            )
        )

        TestResult(
            status,
            requestedBytes,
            verifiedBytes,
            corruptBytes,
            failed,
            successful,
            rate(requestedBytes, writeNanos),
            rate(readTotal, readNanos),
            duration,
            errors,
            created
        )
    }

    private fun rate(bytes: Long, nanos: Long): Double =
        if (nanos <= 0) 0.0
        else bytes.toDouble() / (nanos / 1_000_000_000.0)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
