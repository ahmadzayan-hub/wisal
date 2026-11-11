package com.wifeassistant.data.pairing

import com.wifeassistant.data.crypto.SignalPreKeyBundleDto

// نصوص الإثبات الموقّعة اللي wisal-direct-relay بيتحقق منها — لازم تتطابق
// حرفيًا مع lib/relay.js على الباك-إند. مركّزة هنا في مكان واحد عشان أي
// تغيير مستقبلي في الصيغة يتعمل مرة واحدة ويتحقق منه بالاختبارات، مش
// يتفرّق في كل نداء HTTP على حدة.
object DirectRelayProofs {
    fun register(deviceId: String): String = "wisal-direct-register:$deviceId"

    fun submit(senderDeviceId: String, recipientDeviceId: String, ciphertextB64: String, expiresAtEpochSec: Long): String =
        "$senderDeviceId:$recipientDeviceId:$ciphertextB64:$expiresAtEpochSec"

    fun fetch(deviceId: String, timestamp: Long): String = "fetch:$deviceId:$timestamp"

    fun ack(envelopeId: String): String = "ack:$envelopeId"

    // Field order is the published relay contract in lib/prekeys.js.
    fun publishPreKeys(bundle: SignalPreKeyBundleDto, timestamp: Long): String = listOf(
        "wisal-direct-prekeys", timestamp, bundle.deviceId, bundle.registrationId, bundle.identityKeyB64,
        bundle.signedPreKeyId, bundle.signedPreKeyPublicB64, bundle.signedPreKeySignatureB64,
        bundle.kyberPreKeyId, bundle.kyberPreKeyPublicB64, bundle.kyberPreKeySignatureB64,
        bundle.oneTimePreKeyId, bundle.oneTimePreKeyPublicB64,
    ).joinToString(":")

    fun claimPreKeys(requesterDeviceId: String, targetDeviceId: String, timestamp: Long): String =
        "claim-prekeys:$requesterDeviceId:$targetDeviceId:$timestamp"

    fun preKeyPublicationStatus(deviceId: String, timestamp: Long, requestNonce: String): String =
        "prekeys-status:$deviceId:$timestamp:$requestNonce"

    fun revoke(deviceId: String, timestamp: Long): String = "revoke:$deviceId:$timestamp"
}
