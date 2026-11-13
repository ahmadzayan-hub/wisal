package com.wifeassistant.data.pairing

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Public pairing metadata only, in the existing preferences file and format. */
object AndroidPairingStore {
    private var instance: PairingAcceptanceStore? = null
    private var preferences: SharedPreferences? = null

    @Synchronized
    fun get(context: Context): PairingAcceptanceStore {
        val prefs = context.applicationContext.getSharedPreferences("wife_assistant_settings", Context.MODE_PRIVATE)
        if (preferences === prefs) instance?.let { return it }
        val json = Json { ignoreUnknownKeys = true }
        return PairingAcceptanceStore(
            read = {
                // Corrupt existing data must never become an empty trust/replay store.
                val raw = prefs.getString("pairedPeers", null)
                PairingState(
                    peers = if (raw == null) emptyList() else json.decodeFromString<List<PairedPeer>>(raw),
                    acceptedInvitationIds = prefs.getStringSet("acceptedInvitationIds", emptySet()).orEmpty().toSet(),
                )
            },
            commit = { state ->
                prefs.edit()
                    .putString("pairedPeers", json.encodeToString(state.peers))
                    .putStringSet("acceptedInvitationIds", state.acceptedInvitationIds)
                    .commit()
            },
        ).also { preferences = prefs; instance = it }
    }
}
