package com.gamefibre.stores

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Store-specific refresh tokens and account IDs; passwords and access tokens are never persisted. */
internal class StoreTokenVault(context: Context, store: GameStore) {
    private val prefs = context.getSharedPreferences("store_session_${store.name.lowercase()}", Context.MODE_PRIVATE)
    private val alias = "gamefibre.store.${store.name.lowercase()}.v1"
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(refreshToken: String, owner: String) {
        require(refreshToken.isNotBlank() && owner.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plaintext = JSONObject().put("refresh_token", refreshToken).put("owner", owner).toString()
        val bytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        check(prefs.edit().clear().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Could not save the account session." }
    }
    @Synchronized fun read(): JSONObject? = stored()?.takeUnless { prefs.getBoolean("rejected", false) }
    @Synchronized fun owner(): String? = stored()?.getString("owner")
    @Synchronized fun invalidate() { check(prefs.edit().putBoolean("rejected", true).commit()) { "Could not update the account session." } }
    private fun stored(): JSONObject? {
        val data = prefs.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            }
            JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
                .also { require(it.getString("refresh_token").isNotBlank() && it.getString("owner").matches(Regex("[A-Za-z0-9-]{1,128}"))) }
        } catch (error: Exception) {
            if (com.gamefibre.VaultRecovery.isPermanent(error)) clear()
            else android.util.Log.w("StoreTokenVault", "Store session temporarily unreadable: ${error.javaClass.simpleName}")
            null
        }
    }
    @Synchronized fun clear() { check(prefs.edit().clear().commit()) { "Could not forget the account session." } }
}
