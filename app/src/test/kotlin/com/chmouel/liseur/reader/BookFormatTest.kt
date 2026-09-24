package com.chmouel.liseur.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookFormatTest {

    @get:Rule
    val dir = TemporaryFolder()

    private fun zip(name: String, vararg entries: String): File =
        dir.newFile(name).also { file ->
            ZipOutputStream(file.outputStream()).use { out ->
                entries.forEach { entry ->
                    out.putNextEntry(ZipEntry(entry))
                    out.write(if (entry == "mimetype") "application/epub+zip".toByteArray() else byteArrayOf(1))
                    out.closeEntry()
                }
            }
        }

    @Test
    fun `a pdf is known by its magic whatever its name`() {
        val file = dir.newFile("book.epub").apply { writeBytes("%PDF-1.7\n...".toByteArray()) }
        assertEquals(BookFormat.PDF, BookFormat.sniff(file))
    }

    @Test
    fun `a zip with an epub mimetype is an epub`() {
        assertEquals(BookFormat.EPUB, BookFormat.sniff(zip("a.epub", "mimetype", "META-INF/container.xml")))
    }

    @Test
    fun `a zip of pictures is a comic`() {
        assertEquals(BookFormat.COMIC, BookFormat.sniff(zip("a.epub", "001.jpg", "002.jpg")))
    }

    @Test
    fun `anything unreadable is left to the epub reader`() {
        assertEquals(BookFormat.EPUB, BookFormat.sniff(File(dir.root, "missing.epub")))
        assertEquals(BookFormat.EPUB, BookFormat.sniff(dir.newFile("empty")))
    }
}
