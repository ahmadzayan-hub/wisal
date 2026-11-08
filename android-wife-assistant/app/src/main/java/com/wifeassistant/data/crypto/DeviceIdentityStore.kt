package com.wifeassistant.data.crypto

import android.content.Context
import com.wifeassistant.data.SecureStore

// تخزين هوية الجهاز: بتتولّد مرة واحدة عند أول طلب وبتتخزن في التخزين المشفّر.
// المفتاح الخاص عمره ما يسيب SecureStore — أي واجهة بتاخد PublicIdentity بس.

object DeviceIdentityStore {
    private const val KEY = "deviceIdentity"

    fun getOrCreate(context: Context): LocalIdentity =
        DeviceIdentityPersistence.getOrCreate(
            read = { SecureStore.getSecret(context, KEY) },
            write = { SecureStore.setSecret(context, KEY, it) },
        )
}
