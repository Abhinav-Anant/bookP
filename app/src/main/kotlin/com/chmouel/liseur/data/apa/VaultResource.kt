package com.chmouel.liseur.data.apa

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.file.FileSystemError
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.ResourceFactory
import org.readium.r2.shared.util.toUri

/**
 * Hands Readium the plain bytes of a sealed book without writing them
 * anywhere. Put in front of Readium's own factories, so every reader and
 * every metadata pass in the app opens sealed and plain files alike.
 */
class VaultResourceFactory(private val vault: BookVault) : ResourceFactory {
    override suspend fun create(url: AbsoluteUrl): Try<Resource, ResourceFactory.Error> {
        val file = url.takeIf { it.scheme.value == "file" }?.toUri()?.path?.let(::File)
        if (file == null || !withContext(Dispatchers.IO) { vault.isSealed(file) }) {
            return Try.failure(ResourceFactory.Error.SchemeNotSupported(url.scheme))
        }
        return Try.success(VaultResource(file, vault))
    }
}

private class VaultResource(private val file: File, private val vault: BookVault) : Resource {

    private val lock = Mutex()
    private var channel: SeekableByteChannel? = null

    // No source URL on purpose: every download is named `.epub`, and a
    // hint from the name would mislead Readium about a PDF or a comic.
    override val sourceUrl: AbsoluteUrl? = null

    override suspend fun properties(): Try<Resource.Properties, ReadError> = Try.success(Resource.Properties())

    override suspend fun length(): Try<Long, ReadError> = io { it.size() }

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> = io { channel ->
        val size = channel.size()
        val start = (range?.first ?: 0L).coerceIn(0, size)
        val end = (range?.last?.plus(1) ?: size).coerceIn(start, size)
        val buffer = ByteBuffer.allocate((end - start).toInt())
        channel.position(start)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        buffer.array().copyOf(buffer.position())
    }

    override fun close() {
        channel?.close()
        channel = null
    }

    private suspend fun <T> io(block: (SeekableByteChannel) -> T): Try<T, ReadError> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    val open = channel ?: vault.open(file).also { channel = it }
                    Try.success(block(open))
                } catch (e: Exception) {
                    Try.failure(ReadError.Access(FileSystemError.IO(e)))
                }
            }
        }
}
