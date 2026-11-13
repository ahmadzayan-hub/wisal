package com.wifeassistant

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wifeassistant.data.crypto.AndroidSignalProvider
import com.wifeassistant.data.crypto.DeviceIdentityStore
import com.wifeassistant.data.pairing.DirectPreKeyMaintenance
import com.wifeassistant.data.pairing.RelayRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class DirectReviewMaintenance(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!DirectReviewSession.isRegistered(applicationContext)) return@withContext Result.success()
            val identity = DeviceIdentityStore.getForDirect(applicationContext, enroll = false)
            val provider = AndroidSignalProvider.open(applicationContext, identity.deviceId, requireExistingCheckpoint = true)
            val relay = DirectReviewSession.openRelay(applicationContext)
            val maintenance = DirectPreKeyMaintenance(provider, relay)
            val result = maintenance.runOnce(identity).getOrThrow()
            if (!result.oneTimePreKeyAvailable) maintenance.runOnce(identity).getOrThrow()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            Result.retry()
        } catch (failed: RelayRequestException) {
            if (failed.httpCode == 429 || failed.httpCode >= 500) Result.retry() else Result.failure()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        private const val NAME = "WisalDirectReviewMaintenance"

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<DirectReviewMaintenance>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }

        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
