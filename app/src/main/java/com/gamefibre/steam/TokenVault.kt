package com.gamefibre.steam

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the refresh token is persisted, encrypted with a non-exportable Android Keystore key. */
class TokenVault(context: Context, private val alias: String = "gamefibre.session.v1") {
    private val prefs = context.getSharedPreferences("steam_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(token: String) {
        tokenSteamId(token) // Reject unusable tokens before replacing a saved session.
        write(token)
    }
    private fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().clear().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Cannot save Steam session" }
    }
    @Synchronized fun read(): String? = stored()?.takeIf { token ->
        !prefs.getBoolean("rejected", false) && runCatching { tokenSteamId(token) }.isSuccess
    }
    @Synchronized fun ownerId(): String? = stored()?.let { tokenSteamId(it, requireUnexpired = false).toString() }
    @Synchronized fun invalidate() {
        check(prefs.edit().putBoolean("rejected", true).commit()) { "Cannot update Steam session" }
    }
    private fun stored(): String? = try {
        val data = prefs.getString("data", null)
        if (data == null) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            }
            val token = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
            tokenSteamId(token, requireUnexpired = false)
            token
        }
    } catch (error: Exception) {
        if (com.gamefibre.VaultRecovery.isPermanent(error)) clear()
        else android.util.Log.w("TokenVault", "Steam session temporarily unreadable: ${error.javaClass.simpleName}")
        null
    }
    @android.annotation.SuppressLint("ApplySharedPref")
    @Synchronized fun clear() { check(prefs.edit().clear().commit()) { "Cannot forget Steam session" } }
}
