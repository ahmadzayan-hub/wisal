package com.wifeassistant

import com.wifeassistant.data.crypto.LibsignalCryptoProvider
import com.wifeassistant.data.crypto.LibsignalKeys
import com.wifeassistant.data.crypto.SignalPreKeyBundleDto
import com.wifeassistant.data.crypto.SignalStatePersistence
import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.crypto.SignalPeerBinding
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Release acceptance contracts, not a claim that persistence exists.
 * Run separately until implementation satisfies them. Synthetic libsignal keys only.
 */
class SignalReleaseAcceptanceTest {
    private fun bytes(bundle: SignalPreKeyBundleDto) =
        Json.encodeToString(SignalPreKeyBundleDto.serializer(), bundle).toByteArray(Charsets.UTF_8)

    @Test fun providerRecreationWithSameIdentityMustRetainEstablishedSession() {
        var disk: String? = null
        fun open() = LibsignalCryptoProvider(SignalStatePersistence({ disk }, { disk = it }))
        val alice = open()
        val bob = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        val bobSigning = DeviceIdentityCodec.generate().copy(deviceId = "B")
        val bobBundle = bob.publishableBundle("B")
        alice.trustPeer(bobSigning.public, bobBundle, SignalPeerBinding.sign(bobSigning, bobBundle))
        alice.establishSession("B", bytes(bobBundle))
        assertTrue(alice.hasSession("B"))
        // Models recreation of the provider, not an actual Android process restart.
        val recreated = open()
        assertTrue("release requires durable session reloading", recreated.hasSession("B"))
    }

    @Test fun repeatedBundleGenerationMustNotDestroyKeysForAlreadyPublishedBundle() {
        val alice = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        val bob = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        alice.establishSession("B", bytes(bob.publishableBundle("B")))
        // A later publication may be rejected by the relay for reusing id=1.
        // Reject generation or preserve the pending private keys until a safe rotation.
        runCatching { bob.publishableBundle("B") }
        val plaintext = "synthetic pending message".toByteArray(Charsets.UTF_8)
        val ciphertext = alice.session("B").encrypt(plaintext)
        assertArrayEquals(plaintext, bob.session("A").decrypt(ciphertext))
    }
}
