package com.wifeassistant.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object OwnSigningIdentity {
    @Suppress("DEPRECATION")
    fun sha256(context: Context): String? = runCatching {
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.singleOrNull()?.toByteArray()
        } ?: return null
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()
}
