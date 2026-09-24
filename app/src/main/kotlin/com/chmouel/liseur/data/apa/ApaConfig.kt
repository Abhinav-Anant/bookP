package com.chmouel.liseur.data.apa

import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerKind

/**
 * Where the APA cloud lives, read from `local.properties` at build time
 * (`apa.supabaseUrl`, `apa.supabaseAnonKey`, `apa.googleWebClientId`).
 *
 * The app talks to the `api` Edge Function as if it were a Komga server,
 * so [apiBaseUrl] is what ends up in `remote_server.base_url`.
 */
object ApaConfig {
    val supabaseUrl: String = BuildConfig.APA_SUPABASE_URL.trimEnd('/')
    val anonKey: String = BuildConfig.APA_SUPABASE_ANON_KEY
    val googleWebClientId: String = BuildConfig.APA_GOOGLE_WEB_CLIENT_ID
    val apiBaseUrl: String get() = "$supabaseUrl/functions/v1/api"
    val configured: Boolean get() = supabaseUrl.isNotEmpty() && anonKey.isNotEmpty()

    /** The APA account among whatever server is connected, if it is one. */
    fun connectionOf(server: RemoteServer?): ApaConnection? {
        if (!configured || server == null || server.kind != ServerKind.KOMGA) return null
        if (!server.baseUrl.startsWith(supabaseUrl)) return null
        val key = (server.credentials as? RemoteCredentials.ApiKey)?.key ?: return null
        return ApaConnection(server.baseUrl, key)
    }
}
