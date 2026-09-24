package com.chmouel.liseur.data.apa

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig

/**
 * This device's book key: a Tink keyset kept in private preferences,
 * itself encrypted by an Android Keystore key that cannot leave the
 * device. The preferences file is kept out of backups (see
 * `backup_rules.xml`), and books are too, so a restore simply means
 * saving books for offline again.
 */
object ApaVaultKey {
    private const val PREFS = "apa_vault"
    private const val KEYSET = "book_key"
    private const val MASTER_KEY = "android-keystore://apa_book_master"

    @Volatile
    private var cached: StreamingAead? = null

    fun streamingAead(context: Context): StreamingAead = cached ?: synchronized(this) {
        cached ?: run {
            StreamingAeadConfig.register()
            AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET, PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
                .withMasterKeyUri(MASTER_KEY)
                .build()
                .keysetHandle
                .getPrimitive(StreamingAead::class.java)
                .also { cached = it }
        }
    }
}
