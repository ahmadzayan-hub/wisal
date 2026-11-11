package com.wifeassistant.data.crypto

import java.util.Base64

/** The trusted P-256 public identity MUST come from verified pairing, never from
 * the same untrusted relay response as the bundle. This is a separate binding
 * proof, not a replacement for libsignal's signed-pre-key verification.
 */
object SignalPeerBinding {
    private fun proof(bundle: SignalPreKeyBundleDto): ByteArray {
        require(bundle.hasValidWireFields())
        return "wisal-signal-binding:1:${bundle.deviceId}:${bundle.registrationId}:${bundle.identityKeyB64}"
            .toByteArray(Charsets.UTF_8)
    }

    fun sign(identity: LocalIdentity, bundle: SignalPreKeyBundleDto): String {
        require(identity.deviceId == bundle.deviceId)
        return Base64.getEncoder().encodeToString(DeviceIdentityCodec.sign(identity, proof(bundle)))
    }

    fun verify(trusted: PublicIdentity, bundle: SignalPreKeyBundleDto, signature: String) {
        require(trusted.deviceId == bundle.deviceId && signature.length in 1..512)
        check(DeviceIdentityCodec.verify(trusted.publicKeyB64, proof(bundle), Base64.getDecoder().decode(signature))) {
            "Signal identity is not bound to the verified pairing identity."
        }
    }
}
