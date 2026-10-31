package com.gamefibre

import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * Decides whether a failed vault read means the stored record is unusable or the keystore is
 * temporarily unavailable. Only the former may discard a saved session.
 */
internal object VaultRecovery {
    fun isPermanent(error: Throwable): Boolean = when {
        // The key was invalidated or no longer matches the ciphertext: the record can never decrypt again.
        error is android.security.keystore.KeyPermanentlyInvalidatedException -> true
        error is javax.crypto.AEADBadTagException -> true
        // Keystore daemon trouble, locked key material, or missing provider: retry later with the record intact.
        error is GeneralSecurityException || error is ProviderException -> false
        error.javaClass.name == "android.security.KeyStoreException" -> false
        // Malformed Base64, invalid JSON, or a token that fails validation.
        else -> true
    }
}
