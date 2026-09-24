package com.chmouel.liseur.data.apa

import com.chmouel.liseur.reader.BookFormat
import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * Keeps downloaded books encrypted on the device, so a book can be read
 * in APA and nowhere else.
 *
 * A sealed file is a six-byte header — magic, version, [BookFormat] — and
 * then a Tink streaming-AEAD ciphertext (AES-256-GCM-HKDF, 4 KB segments)
 * of the book, with the header as associated data. Streaming segments are
 * what let Readium read any byte range without decrypting the whole book,
 * and nothing is ever decrypted to disk. The key lives wrapped by the
 * Android Keystore (see [ApaVaultKey]), so a copied file is useless.
 */
class BookVault(private val aead: () -> StreamingAead) {

    fun isSealed(file: File): Boolean = header(file) != null

    /** The format recorded when [file] was sealed, or null if it is not sealed. */
    fun formatOf(file: File): BookFormat? = header(file)?.let { BookFormat.entries.getOrNull(it[FORMAT_AT].toInt()) }

    /** Encrypts [file] in place. A file that is already sealed is left as it is. */
    fun seal(file: File, format: BookFormat) {
        if (isSealed(file)) return
        val header = MAGIC + byteArrayOf(VERSION, format.ordinal.toByte())
        val sealed = File(file.parentFile, "${file.name}.sealing")
        try {
            sealed.outputStream().use { out ->
                out.write(header)
                aead().newEncryptingStream(out, header).use { encrypting ->
                    file.inputStream().use { it.copyTo(encrypting) }
                }
            }
            if (!sealed.renameTo(file)) {
                file.delete()
                if (!sealed.renameTo(file)) throw IOException("could not replace ${file.name}")
            }
        } finally {
            sealed.delete()
        }
    }

    /** The book's plain bytes as a seekable, read-only channel. */
    fun open(file: File): SeekableByteChannel {
        val header = header(file) ?: throw IOException("${file.name} is not a sealed book")
        val raw = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        return try {
            aead().newSeekableDecryptingChannel(Skipping(raw, HEADER_SIZE.toLong()), header).apply {
                // Tink only knows the plain size after a first read, and
                // Readium asks for the size first.
                read(ByteBuffer.allocate(1))
                position(0)
            }
        } catch (e: Exception) {
            raw.close()
            throw IOException("cannot decrypt ${file.name}", e)
        }
    }

    private fun header(file: File): ByteArray? {
        if (!file.isFile || file.length() < HEADER_SIZE) return null
        val head = BookFormat.readHead(file, HEADER_SIZE)
        return head.takeIf { it.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) && it[MAGIC.size] == VERSION }
    }

    /** [channel] with its first [offset] bytes out of sight. */
    private class Skipping(private val channel: FileChannel, private val offset: Long) : SeekableByteChannel {
        init {
            channel.position(offset)
        }

        override fun read(dst: ByteBuffer): Int = channel.read(dst)
        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
        override fun position(): Long = channel.position() - offset
        override fun position(newPosition: Long): SeekableByteChannel = apply { channel.position(newPosition + offset) }
        override fun size(): Long = channel.size() - offset
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
        override fun isOpen(): Boolean = channel.isOpen
        override fun close() = channel.close()
    }

    private companion object {
        val MAGIC = "APAV".toByteArray()
        const val VERSION: Byte = 1
        const val FORMAT_AT = 5
        const val HEADER_SIZE = 6
    }
}
