package com.wifeassistant.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wifeassistant.data.AppConstants
import com.wifeassistant.data.Settings
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

// جدولة إشعارات الصباح/المساء يومياً عبر WorkManager (بتوقيت Asia/Dubai).
object Scheduler {
    private val zone: ZoneId = ZoneId.of(AppConstants.TIMEZONE)

    fun scheduleDaily(context: Context) {
        val settings = Settings(context)
        if (settings.backgroundSuggestions && settings.groqEnabled) {
            scheduleSlot(context, "morning", settings.morningTime)
            scheduleSlot(context, "evening", settings.eveningTime)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork("suggestion_morning")
            WorkManager.getInstance(context).cancelUniqueWork("suggestion_evening")
        }
        scheduleReminders(context)
    }

    // فحص يومي للتذكيرات ("بقالك فترة") - الساعة 11 صباحاً بتوقيت Asia/Dubai.
    private fun scheduleReminders(context: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes(11, 0), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "contact_reminder",
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    private fun scheduleSlot(context: Context, slot: String, hhmm: String) {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

        val req = PeriodicWorkRequestBuilder<SuggestionWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes(hour, minute), TimeUnit.MINUTES)
            .setInputData(workDataOf("slot" to slot))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "suggestion_$slot",
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    private fun initialDelayMinutes(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now(zone)
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMinutes().coerceAtLeast(1)
    }
}
