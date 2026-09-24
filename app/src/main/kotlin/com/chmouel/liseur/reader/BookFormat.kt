package com.chmouel.liseur.reader

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Which reader a downloaded file needs.
 *
 * Read from the file itself rather than its name: downloads are all
 * saved as `<uuid>.epub`, whatever the server sent.
 */
enum class BookFormat {
    EPUB,
    PDF,

    /** A CBZ: a zip of page images, read with Readium's image navigator. */
    COMIC,
    ;

    companion object {
        private val PDF_MAGIC = "%PDF".toByteArray()
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        fun sniff(file: File): BookFormat {
            val head = try {
                readHead(file, PDF_MAGIC.size)
            } catch (_: IOException) {
                return EPUB
            }
            return when {
                head.contentEquals(PDF_MAGIC) -> PDF
                head.contentEquals(ZIP_MAGIC) && !isEpubZip(file) -> COMIC
                else -> EPUB
            }
        }

        /** Up to [size] bytes from the start of [file]; `readNBytes` needs API 33. */
        fun readHead(file: File, size: Int): ByteArray {
            val head = ByteArray(size)
            var read = 0
            file.inputStream().use { stream ->
                while (read < size) {
                    val n = stream.read(head, read, size - read)
                    if (n < 0) break
                    read += n
                }
            }
            return head.copyOf(read)
        }

        private fun isEpubZip(file: File): Boolean = try {
            ZipFile(file).use { zip ->
                zip.getEntry("mimetype") != null || zip.getEntry("META-INF/container.xml") != null
            }
        } catch (_: IOException) {
            true
        }
    }
}
