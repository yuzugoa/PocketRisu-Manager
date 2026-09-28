package io.github.risumanager.operation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class BackupStreamVerifierTest {
    @Test
    fun copiesAndVerifiesAStream() = runTest {
        val source = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()

        BackupStreamVerifier.copyAndVerify(
            input = ByteArrayInputStream(source),
            output = output,
            expectedSize = source.size.toLong(),
            expectedSha256 = sha256(source),
            onProgress = progress::add,
        )

        assertThat(output.toByteArray()).isEqualTo(source)
        assertThat(progress.last()).isEqualTo(source.size.toLong())
    }

    @Test
    fun rejectsTruncatedTransfer() = runTest {
        val source = "short".encodeToByteArray()

        val failure = runCatching {
            BackupStreamVerifier.copyAndVerify(
                ByteArrayInputStream(source),
                ByteArrayOutputStream(),
                expectedSize = source.size + 1L,
                expectedSha256 = sha256(source),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupException::class.java)
        assertThat((failure as BackupException).code).isEqualTo("BACKUP_SIZE_MISMATCH")
    }

    @Test
    fun rejectsHashMismatch() = runTest {
        val source = "complete but altered".encodeToByteArray()

        val failure = runCatching {
            BackupStreamVerifier.copyAndVerify(
                ByteArrayInputStream(source),
                ByteArrayOutputStream(),
                expectedSize = source.size.toLong(),
                expectedSha256 = "0".repeat(64),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupException::class.java)
        assertThat((failure as BackupException).code).isEqualTo("BACKUP_HASH_MISMATCH")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
