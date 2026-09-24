package com.chmouel.liseur.data.apa

import org.json.JSONArray
import org.json.JSONObject

/** A book as the Home rows, search and "more by" lists show it. */
data class BookCard(
    val id: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val isFree: Boolean,
    val priceInr: Double,
    val language: String?,
    val genres: List<String>,
)

/** One Home row; [key] is `featured`, `editors_pick`, `trending`, `new`, `free` or `wishlist`. */
data class HomeRow(val key: String, val books: List<BookCard>)

data class BookDetail(
    val card: BookCard,
    val subtitle: String?,
    val publisher: String?,
    val description: String?,
    val pages: Int?,
    val publishedOn: String?,
    val isbn: String?,
    val formats: List<String>,
    val authorBio: String?,
    val inLibrary: Boolean,
    val favorite: Boolean,
    val wishlist: Boolean,
    val moreByAuthor: List<BookCard>,
)

/** A device signed in to the reader's account; [current] is this one. */
data class ApaDevice(val id: String, val name: String?, val lastSeen: String?, val current: Boolean)

/** Parsing for the discovery (`/apa/...`) routes of the APA API. */
object ApaModels {

    fun devices(json: JSONObject): List<ApaDevice> =
        json.objects("devices").mapNotNull { d ->
            ApaDevice(
                id = d.text("id") ?: return@mapNotNull null,
                name = d.text("name"),
                lastSeen = d.text("lastSeen"),
                current = d.optBoolean("current"),
            )
        }

    fun homeRows(json: JSONObject): List<HomeRow> =
        json.objects("rows")
            .map { HomeRow(it.optString("key"), cards(it.optJSONArray("books"))) }
            .filter { it.key.isNotEmpty() && it.books.isNotEmpty() }

    fun favoriteIds(json: JSONObject): Set<String> = json.strings("favoriteIds").toSet()

    fun cards(array: JSONArray?): List<BookCard> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it)?.let(::card) }

    fun detail(json: JSONObject): BookDetail = BookDetail(
        card = card(json) ?: throw IllegalArgumentException("book without id"),
        subtitle = json.text("subtitle"),
        publisher = json.text("publisher"),
        description = json.text("description"),
        pages = json.optInt("pages").takeIf { it > 0 },
        publishedOn = json.text("publishedOn"),
        isbn = json.text("isbn"),
        formats = json.strings("formats"),
        authorBio = json.text("authorBio"),
        inLibrary = json.optBoolean("inLibrary"),
        favorite = json.optBoolean("favorite"),
        wishlist = json.optBoolean("wishlist"),
        moreByAuthor = cards(json.optJSONArray("moreByAuthor")),
    )

    private fun card(json: JSONObject): BookCard? {
        val id = json.text("id") ?: return null
        return BookCard(
            id = id,
            title = json.text("title") ?: id,
            author = json.text("author"),
            coverUrl = json.text("coverUrl"),
            isFree = json.optBoolean("isFree"),
            priceInr = json.optDouble("priceInr", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            language = json.text("language"),
            genres = json.strings("genres"),
        )
    }

    /** A string field, treating JSON null and blank as absent. */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }

    private fun JSONObject.strings(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
    }
}
