package com.wifeassistant.data.crypto

import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage

/** Existing type-byte transport format, shared by sessions and the durable queue. */
internal object SignalWireCodec {
    fun isPreKeyMessage(ciphertext: ByteArray): Boolean = ciphertext.firstOrNull()?.toInt() == CiphertextMessage.PREKEY_TYPE

    fun encrypt(cipher: SessionCipher, plaintext: ByteArray): ByteArray {
        val message = cipher.encrypt(plaintext)
        return byteArrayOf(message.type.toByte()) + message.serialize()
    }

    fun decrypt(cipher: SessionCipher, ciphertext: ByteArray): ByteArray {
        require(ciphertext.isNotEmpty()) { "empty ciphertext" }
        val body = ciphertext.copyOfRange(1, ciphertext.size)
        return when (val type = ciphertext[0].toInt()) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(body))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(body))
            else -> throw IllegalArgumentException("unsupported Signal ciphertext type $type")
        }
    }
}
