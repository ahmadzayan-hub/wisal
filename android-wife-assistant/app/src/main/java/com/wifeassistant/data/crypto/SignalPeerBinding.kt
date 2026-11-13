package com.wifeassistant.data.crypto

import java.util.Base64

data class SignalPeerIdentity(val deviceId: String, val registrationId: Int, val identityKeyB64: String)

fun SignalPreKeyBundleDto.peerIdentity() = SignalPeerIdentity(deviceId, registrationId, identityKeyB64)

/** The trusted P-256 public identity MUST come from verified pairing, never from
 * the same untrusted relay response as the bundle. This is a separate binding
 * proof, not a replacement for libsignal's signed-pre-key verification.
 */
object SignalPeerBinding {
    private fun proof(peer: SignalPeerIdentity): ByteArray {
        require(peer.deviceId.length in 1..200 && ':' !in peer.deviceId && peer.registrationId in 1..16_380)
        require(peer.identityKeyB64.length in 1..256 && Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(peer.identityKeyB64)) == peer.identityKeyB64)
        return "wisal-signal-binding:1:${peer.deviceId}:${peer.registrationId}:${peer.identityKeyB64}"
            .toByteArray(Charsets.UTF_8)
    }

    fun sign(identity: DeviceSigningIdentity, bundle: SignalPreKeyBundleDto): String {
        require(bundle.hasValidWireFields())
        return sign(identity, bundle.peerIdentity())
    }

    fun verify(trusted: PublicIdentity, bundle: SignalPreKeyBundleDto, signature: String) {
        require(bundle.hasValidWireFields())
        verify(trusted, bundle.peerIdentity(), signature)
    }

    fun sign(identity: DeviceSigningIdentity, peer: SignalPeerIdentity): String {
        require(identity.deviceId == peer.deviceId)
        return Base64.getEncoder().encodeToString(identity.sign(proof(peer)))
    }

    fun verify(trusted: PublicIdentity, peer: SignalPeerIdentity, signature: String) {
        require(trusted.deviceId == peer.deviceId && signature.length in 1..512)
        check(DeviceIdentityCodec.verify(trusted.publicKeyB64, proof(peer), Base64.getDecoder().decode(signature))) {
            "Signal identity is not bound to the verified pairing identity."
        }
    }
}
