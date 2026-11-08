package com.wifeassistant.data.crypto

import android.content.Context
import com.wifeassistant.data.SecureStore

/** Single-process Android adapter. All protocol state uses the same verified
 * encrypted storage boundary as the device identity. Never put this snapshot in
 * the application's manual backup or a cloud/OEM transfer.
 */
object AndroidSignalProvider {
    private var state: SignalStatePersistence? = null
    private var provider: LibsignalCryptoProvider? = null

    @Synchronized
    fun get(context: Context): LibsignalCryptoProvider {
        provider?.let { return it }
        val app = context.applicationContext
        val owner = DeviceIdentityStore.getOrCreate(app).deviceId
        val persistence = SignalStatePersistence(
            read = { SecureStore.getSecret(app, "signalProtocolState") },
            write = { SecureStore.setSecret(app, "signalProtocolState", it) },
            ownerDeviceId = owner,
        )
        state = persistence
        return LibsignalCryptoProvider(persistence).also { provider = it }
    }

    @Synchronized
    fun deliveryQueue(context: Context): SignalMessageQueue {
        get(context)
        return SignalMessageQueue(checkNotNull(state))
    }

}
