package com.gamefibre

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

class VaultRecoveryTest {
    @Test fun corruptOrInvalidatedRecordsAreDiscarded() {
        assertTrue(VaultRecovery.isPermanent(AEADBadTagException("tag mismatch")))
        assertTrue(VaultRecovery.isPermanent(IllegalArgumentException("bad base-64")))
        assertTrue(VaultRecovery.isPermanent(org.json.JSONException("unterminated")))
        assertTrue(VaultRecovery.isPermanent(IllegalStateException("Invalid Steam session")))
    }

    @Test fun keystoreOutagesKeepTheSavedSession() {
        assertFalse(VaultRecovery.isPermanent(KeyStoreException("keystore unavailable")))
        assertFalse(VaultRecovery.isPermanent(UnrecoverableKeyException("locked")))
        assertFalse(VaultRecovery.isPermanent(ProviderException("Keystore operation failed")))
        assertFalse(VaultRecovery.isPermanent(java.security.InvalidKeyException("key not yet available")))
    }
}
