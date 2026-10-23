package com.wifeassistant.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wifeassistant.data.GroqClient
import com.wifeassistant.data.Occasions
import com.wifeassistant.data.PendingRound
import com.wifeassistant.data.Settings
import com.wifeassistant.data.Store
import com.wifeassistant.data.SuggestionEngine
import com.wifeassistant.util.Notifications

// عامل الخلفية: يولّد اقتراحين في ميعاد الصباح/المساء ويبعت إشعار.
// بيحترم ثبات الحالة (مش نفس الخانة مرتين/يوم) ويفحص مناسبة اليوم صباحاً.
class SuggestionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val slot = inputData.getString("slot") ?: "morning"
        val settings = Settings(applicationContext)
        if (!settings.backgroundSuggestions || !settings.groqEnabled || settings.groqKey.isBlank()) return Result.success() // مفيش مفتاح - نتخطى بهدوء

        val store = Store(applicationContext)
        val occasion = if (slot == "morning") Occasions(settings).todaysOccasion() else null
        val effectiveSlot = if (occasion != null) "occasion" else slot
        if (store.wasSlotSentToday(effectiveSlot)) return Result.success()

        return try {
            val engine = SuggestionEngine(store, GroqClient(settings), settings)
            val res = engine.generate(effectiveSlot, occasion)
            // نحفظ الجولة عشان الواجهة تفتح على نفس الاقتراحين لما تدوس الإشعار.
            store.setPending(PendingRound(res.slot, res.themesShown, res.items, occasion?.label, settings.currentRecipient()?.id.orEmpty(), res.offline, res.note))

            val title = when {
                occasion != null -> "💌 ${occasion.label}"
                slot == "morning" -> "🌅 اقتراح الصباح"
                else -> "🌙 اقتراح المساء"
            }
            val body = "1️⃣ ${res.items[0].text}\n\n2️⃣ ${res.items[1].text}"
            Notifications.show(applicationContext, title, body, effectiveSlot.hashCode())
            store.markSlotSentToday(effectiveSlot)
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.retry()
        }
    }
}
