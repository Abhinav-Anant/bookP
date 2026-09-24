package com.chmouel.liseur.data.apa

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApaAuthClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun client() = ApaAuthClient("http://127.0.0.1:${server.port}", "anon", OkHttpClient())

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    @Test
    fun `sending a code asks Supabase to create the account if needed`() = runBlocking {
        server.enqueue(ok("{}"))
        assertTrue(client().sendEmailOtp("reader@anant.in").isSuccess)
        val request = server.takeRequest()
        assertEquals("/auth/v1/otp", request.target)
        assertEquals("anon", request.headers["apikey"])
        val body = JSONObject(request.body!!.utf8())
        assertEquals("reader@anant.in", body.getString("email"))
        assertEquals(true, body.getBoolean("create_user"))
    }

    @Test
    fun `a verified code yields the access token`() = runBlocking {
        server.enqueue(ok("""{"access_token":"jwt1","refresh_token":"r"}"""))
        assertEquals("jwt1", client().verifyEmailOtp("reader@anant.in", "123456").getOrThrow())
        val request = server.takeRequest()
        assertEquals("/auth/v1/verify", request.target)
        val body = JSONObject(request.body!!.utf8())
        assertEquals("email", body.getString("type"))
        assertEquals("123456", body.getString("token"))
    }

    @Test
    fun `a wrong code is a failure carrying the server's message`() = runBlocking {
        server.enqueue(MockResponse(code = 403, body = """{"msg":"Token has expired or is invalid"}"""))
        val result = client().verifyEmailOtp("reader@anant.in", "000000")
        assertTrue(result.isFailure)
        assertEquals("Token has expired or is invalid", result.exceptionOrNull()?.message)
    }

    @Test
    fun `password sign-in uses the password grant`() = runBlocking {
        server.enqueue(ok("""{"access_token":"jwt2"}"""))
        assertEquals("jwt2", client().signInWithPassword("reader@anant.in", "pw").getOrThrow())
        assertEquals("/auth/v1/token?grant_type=password", server.takeRequest().target)
    }

    @Test
    fun `google sign-in exchanges the id token`() = runBlocking {
        server.enqueue(ok("""{"access_token":"jwt3"}"""))
        assertEquals("jwt3", client().signInWithGoogle("google-id-token").getOrThrow())
        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=id_token", request.target)
        val body = JSONObject(request.body!!.utf8())
        assertEquals("google", body.getString("provider"))
        assertEquals("google-id-token", body.getString("id_token"))
    }

    @Test
    fun `a device key is minted with the session as bearer`() = runBlocking {
        server.enqueue(ok("""{"key":"k123"}"""))
        assertEquals("k123", client().mintDeviceKey("jwt1", "Pixel 9").getOrThrow())
        val request = server.takeRequest()
        assertEquals("/functions/v1/api/apa/devices", request.target)
        assertEquals("Bearer jwt1", request.headers["Authorization"])
        assertEquals("Pixel 9", JSONObject(request.body!!.utf8()).getString("name"))
    }

    @Test
    fun `an answer without a token is a failure, not a blank key`() = runBlocking {
        server.enqueue(ok("{}"))
        assertTrue(client().mintDeviceKey("jwt1", "Pixel").isFailure)
    }
}
