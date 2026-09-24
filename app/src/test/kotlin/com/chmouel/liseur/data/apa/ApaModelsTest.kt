package com.chmouel.liseur.data.apa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApaModelsTest {

    private val home = JSONObject(
        """
        {"rows":[
          {"key":"featured","books":[
            {"id":"b1","title":"Gita","author":"Vyasa","coverUrl":"https://x/c.jpg","isFree":true,"priceInr":0,"language":"sa","genres":["classic"]},
            {"id":"b2","title":"Paid","author":null,"coverUrl":null,"isFree":false,"priceInr":149.5,"language":"hi"}
          ]},
          {"key":"new","books":[]},
          {"key":"free","books":[{"id":"b1","title":"Gita","isFree":true}]}
        ],
        "favoriteIds":["b1"]}
        """,
    )

    @Test
    fun `rows keep their order and drop empty ones`() {
        val rows = ApaModels.homeRows(home)
        assertEquals(listOf("featured", "free"), rows.map(HomeRow::key))
        assertEquals(2, rows[0].books.size)
    }

    @Test
    fun `cards map prices, nulls and missing genres`() {
        val paid = ApaModels.homeRows(home)[0].books[1]
        assertFalse(paid.isFree)
        assertEquals(149.5, paid.priceInr, 0.0)
        assertNull(paid.author)
        assertNull(paid.coverUrl)
        assertEquals(emptyList<String>(), paid.genres)
        assertEquals(listOf("classic"), ApaModels.homeRows(home)[0].books[0].genres)
    }

    @Test
    fun `devices keep the current flag and drop rows without an id`() {
        val devices = ApaModels.devices(
            JSONObject(
                """{"devices":[
                  {"id":"d1","name":"Pixel","lastSeen":"2026-09-24T10:00:00Z","current":true},
                  {"id":"d2","name":null,"lastSeen":null},
                  {"name":"ghost"}
                ]}""",
            ),
        )
        assertEquals(listOf("d1", "d2"), devices.map(ApaDevice::id))
        assertTrue(devices[0].current)
        assertFalse(devices[1].current)
        assertNull(devices[1].name)
    }

    @Test
    fun `favourite ids come from the home payload`() {
        assertEquals(setOf("b1"), ApaModels.favoriteIds(home))
        assertEquals(emptySet<String>(), ApaModels.favoriteIds(JSONObject("{}")))
    }

    @Test
    fun `detail carries flags, formats and more by the author`() {
        val detail = ApaModels.detail(
            JSONObject(
                """
                {"id":"b1","title":"Gita","author":"Vyasa","isFree":true,"priceInr":0,
                 "publisher":"Anant","description":"","pages":300,"publishedOn":"2026-09-01","isbn":null,
                 "formats":["epub","pdf"],"authorBio":"Sage","inLibrary":true,"favorite":false,"wishlist":true,
                 "moreByAuthor":[{"id":"b9","title":"Mahabharata","isFree":false,"priceInr":99}]}
                """,
            ),
        )
        assertEquals("Gita", detail.card.title)
        assertEquals("Anant", detail.publisher)
        assertNull(detail.description)
        assertNull(detail.isbn)
        assertEquals(300, detail.pages)
        assertEquals(listOf("epub", "pdf"), detail.formats)
        assertTrue(detail.inLibrary)
        assertTrue(detail.wishlist)
        assertFalse(detail.favorite)
        assertEquals("b9", detail.moreByAuthor.single().id)
    }
}
