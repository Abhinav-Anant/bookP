package com.chmouel.liseur.data.apa

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/**
 * Signs a reader in to the APA cloud and trades the session for a device
 * key.
 *
 * The Supabase session is used once, to mint the key, and then dropped:
 * the device key is what every later request carries (as a Komga API
 * key), so there is no access token to refresh and an admin can cut off
 * one lost phone without signing the reader out everywhere.
 */
class ApaAuthClient(
    supabaseUrl: String,
    private val anonKey: String,
    private val http: OkHttpClient,
) {
    private val base = supabaseUrl.trimEnd('/')

    /** Emails a one-time code, creating the account on first use. */
    suspend fun sendEmailOtp(email: String): Result<Unit> = call {
        post("/auth/v1/otp", JSONObject().put("email", email.trim()).put("create_user", true))
        Unit
    }

    /** The access token for a correct [code], or a failure. */
    suspend fun verifyEmailOtp(email: String, code: String): Result<String> = call {
        post(
            "/auth/v1/verify",
            JSONObject().put("type", "email").put("email", email.trim()).put("token", code.trim()),
        ).required("access_token")
    }

    suspend fun signInWithPassword(email: String, password: String): Result<String> = call {
        post(
            "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email.trim()).put("password", password),
        ).required("access_token")
    }

    /** Exchanges a Google ID token from Credential Manager for a session. */
    suspend fun signInWithGoogle(idToken: String): Result<String> = call {
        post(
            "/auth/v1/token?grant_type=id_token",
            JSONObject().put("provider", "google").put("id_token", idToken),
        ).required("access_token")
    }

    /** A new device key for this phone, minted with a fresh session. */
    suspend fun mintDeviceKey(accessToken: String, deviceName: String): Result<String> = call {
        post(
            "/functions/v1/api/apa/devices",
            JSONObject().put("name", deviceName),
            bearer = accessToken,
        ).required("key")
    }

    private suspend fun <T> call(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching(block)
    }

    private fun post(path: String, body: JSONObject, bearer: String? = null): JSONObject {
        val request = Request.Builder()
            .url(base + path)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${bearer ?: anonKey}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            val parsed = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (_: JSONException) {
                JSONObject()
            }
            if (!response.isSuccessful) {
                throw IOException(parsed.message() ?: "HTTP ${response.code}")
            }
            return parsed
        }
    }

    private fun JSONObject.message(): String? =
        listOf("msg", "error_description", "error", "message")
            .firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }

    private fun JSONObject.required(key: String): String =
        optString(key).takeIf { it.isNotBlank() } ?: throw IOException("Answer had no $key")

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
