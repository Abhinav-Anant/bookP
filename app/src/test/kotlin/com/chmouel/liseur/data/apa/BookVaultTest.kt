package com.chmouel.liseur.data.apa

import com.chmouel.liseur.reader.BookFormat
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookVaultTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val aead: StreamingAead by lazy {
        StreamingAeadConfig.register()
        KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB)
            .getPrimitive(StreamingAead::class.java)
    }

    private val vault by lazy { BookVault { aead } }

    // Several segments, and an uneven tail.
    private val plain = Random(42).nextBytes(3 * 4096 + 123)

    private fun sealedBook(format: BookFormat = BookFormat.PDF) =
        dir.newFile("book.epub").also {
            it.writeBytes(plain)
            vault.seal(it, format)
        }

    @Test
    fun `a sealed file no longer holds the plain bytes`() {
        val file = sealedBook()
        val stored = file.readBytes()
        assertTrue(vault.isSealed(file))
        assertFalse(stored.copyOfRange(0, 64).contentEquals(plain.copyOfRange(0, 64)))
        assertTrue(stored.size > plain.size)
    }

    @Test
    fun `the format is readable without the key`() {
        assertEquals(BookFormat.COMIC, vault.formatOf(sealedBook(BookFormat.COMIC)))
        assertNull(vault.formatOf(dir.newFile("plain.epub").apply { writeBytes(plain) }))
    }

    @Test
    fun `any range reads back as the original bytes`() {
        val file = sealedBook()
        vault.open(file).use { channel ->
            assertEquals(plain.size.toLong(), channel.size())
            for (start in listOf(0, 1, 4095, 4096, 9000, plain.size - 10)) {
                val length = minOf(500, plain.size - start)
                val buffer = ByteBuffer.allocate(length)
                channel.position(start.toLong())
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
                assertArrayEquals("range at $start", plain.copyOfRange(start, start + length), buffer.array())
            }
        }
    }

    @Test
    fun `sealing twice is harmless`() {
        val file = sealedBook()
        vault.seal(file, BookFormat.PDF)
        vault.open(file).use { assertEquals(plain.size.toLong(), it.size()) }
    }
}
