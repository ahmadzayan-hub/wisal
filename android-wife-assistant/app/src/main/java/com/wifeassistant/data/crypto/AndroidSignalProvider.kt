package com.wifeassistant.data.crypto

import android.content.Context
import android.util.AtomicFile
import java.io.FileNotFoundException
import com.wifeassistant.data.SecureStore

/** Single-process Android adapter. All protocol state uses the same verified
 * encrypted storage boundary as the device identity. Never put this snapshot in
 * the application's manual backup or a cloud/OEM transfer.
 */
object AndroidSignalProvider {
    private var state: SignalStatePersistence? = null
    private var provider: LibsignalCryptoProvider? = null
    @Volatile private var checkpointRequired = false

    @Synchronized
    fun get(context: Context): LibsignalCryptoProvider = open(context, DeviceIdentityStore.getOrCreate(context).deviceId)

    @Synchronized
    internal fun open(context: Context, deviceId: String, requireExistingCheckpoint: Boolean = false): LibsignalCryptoProvider {
        state?.requireOwner(deviceId)
        if (requireExistingCheckpoint) checkpointRequired = true
        provider?.let { return it }
        val app = context.applicationContext
        val checkpoint = AtomicFile(app.noBackupFilesDir.resolve("WisalSignalCheckpoint"))
        val persistence = SignalStatePersistence(
            read = { SecureStore.getSecret(app, "signalProtocolState") },
            write = { SecureStore.setSecret(app, "signalProtocolState", it) },
            ownerDeviceId = deviceId,
            checkpoint = SignalSnapshotCheckpoint(
                read = {
                    try {
                        checkpoint.openRead().use { input ->
                            check(input.channel.size() in 1L..89L)
                            input.readBytes().toString(Charsets.UTF_8)
                        }
                    } catch (_: FileNotFoundException) {
                        check(!checkpoint.baseFile.exists() && !checkpointRequired)
                        null
                    }
                },
                write = { value ->
                    val output = checkpoint.startWrite()
                    try {
                        output.write(value.toByteArray(Charsets.UTF_8))
                        checkpoint.finishWrite(output)
                    } catch (failure: Exception) {
                        checkpoint.failWrite(output)
                        throw failure
                    }
                },
            ),
        )
        state = persistence
        return LibsignalCryptoProvider(persistence).also { provider = it }
    }

    @Synchronized
    fun deliveryQueue(context: Context): SignalMessageQueue {
        get(context)
        return SignalMessageQueue(checkNotNull(state))
    }

    @Synchronized
    internal fun deliveryQueue(context: Context, deviceId: String, requireExistingCheckpoint: Boolean): SignalMessageQueue {
        open(context, deviceId, requireExistingCheckpoint)
        return SignalMessageQueue(checkNotNull(state))
    }

}
