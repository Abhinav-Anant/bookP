package com.chmouel.liseur.data.apa

import com.chmouel.liseur.data.remote.RemoteCredentials
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

enum class AddResult { ADDED, PAYMENT_REQUIRED, FAILED }

data class HomePage(val rows: List<HomeRow>, val favoriteIds: Set<String>)

/** The signed-in APA account: the API address and this device's key. */
data class ApaConnection(val baseUrl: String, val apiKey: String)

/**
 * The discovery half of the APA API: Home rows, search, book detail and
 * the reader's wishlist/favourite flags.
 *
 * Signed with the same device key the Komga client uses, read fresh on
 * every call so a sign-out or account switch is picked up at once.
 */
class ApaDiscoverClient(
    private val connection: suspend () -> ApaConnection?,
    private val http: OkHttpClient,
) {
    suspend fun home(): Result<HomePage> = call {
        val json = JSONObject(get("/apa/home"))
        HomePage(ApaModels.homeRows(json), ApaModels.favoriteIds(json))
    }

    suspend fun detail(id: String): Result<BookDetail> = call {
        ApaModels.detail(JSONObject(get("/apa/books/${enc(id)}")))
    }

    suspend fun search(query: String): Result<List<BookCard>> = call {
        ApaModels.cards(JSONObject(get("/apa/search?q=${enc(query)}")).optJSONArray("books"))
    }

    suspend fun setFlags(id: String, wishlist: Boolean? = null, favorite: Boolean? = null): Result<Unit> = call {
        val body = JSONObject().apply {
            wishlist?.let { put("wishlist", it) }
            favorite?.let { put("favorite", it) }
        }
        check(put("/apa/library/${enc(id)}", body) in 200..299) { "server refused the change" }
    }

    suspend fun addToLibrary(id: String): AddResult =
        call { put("/apa/library/${enc(id)}", JSONObject().put("add", true)) }.fold(
            onSuccess = { code ->
                when (code) {
                    in 200..299 -> AddResult.ADDED
                    PAYMENT_REQUIRED -> AddResult.PAYMENT_REQUIRED
                    else -> AddResult.FAILED
                }
            },
            onFailure = { AddResult.FAILED },
        )

    suspend fun devices(): Result<List<ApaDevice>> = call {
        ApaModels.devices(JSONObject(get("/apa/devices")))
    }

    suspend fun revokeDevice(id: String): Result<Unit> = call {
        val code = http.newCall(request("/apa/devices/${enc(id)}").delete().build()).execute().use { it.code }
        check(code in 200..299) { "server refused the change" }
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching { block() }
    }

    private suspend fun request(path: String): Request.Builder {
        val account = connection() ?: throw IOException("Not signed in to the APA cloud")
        return RemoteCredentials.ApiKey(account.apiKey)
            .signInto(Request.Builder().url(account.baseUrl.trimEnd('/') + path))
    }

    private suspend fun get(path: String): String =
        http.newCall(request(path).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $path")
            response.body.string()
        }

    private suspend fun put(path: String, body: JSONObject): Int =
        http.newCall(request(path).put(body.toString().toRequestBody(JSON)).build()).execute().use { it.code }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val JSON = "application/json".toMediaType()
        const val PAYMENT_REQUIRED = 402
    }
}
