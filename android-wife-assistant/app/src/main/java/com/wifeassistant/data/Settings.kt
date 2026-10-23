package com.wifeassistant.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// الإعدادات (المفتاح + رقم الواتساب + التخصيص + المواعيد + المناسبات).
// كله على الجهاز بس (SharedPreferences) - مفيش سر بيتبعت لأي حد غير Groq.
class Settings(context: Context) {
    private val appCtx = context.applicationContext
    private val prefs = context.getSharedPreferences("wife_assistant_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // مفتاح Groq مشفّر (Android Keystore) بدل التخزين العادي.
    var groqKey: String
        get() = SecureStore.getGroqKey(appCtx)
        set(v) = SecureStore.setGroqKey(appCtx, v)

    // رقم مراتك بالصيغة الدولية بأرقام بس (زي 201001234567).
    var wifeNumber: String
        get() = prefs.getString("wifeNumber", "").orEmpty()
        set(v) = prefs.edit().putString("wifeNumber", v).apply()

    var model: String
        get() = prefs.getString("model", AppConstants.DEFAULT_MODEL) ?: AppConstants.DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v).apply()

    // New installs and upgrades remain local until the user explicitly verifies free-plan access.
    var groqEnabled: Boolean
        get() = prefs.getBoolean("groqEnabled", false)
        set(v) = prefs.edit().putBoolean("groqEnabled", v).apply()

    var backgroundSuggestions: Boolean
        get() = prefs.getBoolean("backgroundSuggestions", false)
        set(v) = prefs.edit().putBoolean("backgroundSuggestions", v).apply()

    // ---- التخصيص والإنسانية ----
    var myName: String // اسم المُرسِل (انت)
        get() = prefs.getString("myName", "").orEmpty()
        set(v) = prefs.edit().putString("myName", v).apply()

    var wifeName: String // اسم/دلع مراتك
        get() = prefs.getString("wifeName", "").orEmpty()
        set(v) = prefs.edit().putString("wifeName", v).apply()

    // تفاصيل شخصية عنها (حاجات بتحبها، دلع، نكت بينكم) عشان الرسالة تبقى ليها هي بالذات.
    var relationshipNotes: String
        get() = prefs.getString("relationshipNotes", "").orEmpty()
        set(v) = prefs.edit().putString("relationshipNotes", v).apply()

    // لمسة دُعابة خفيفة في الاقتراحات؟
    var humor: Boolean
        get() = prefs.getBoolean("humor", false)
        set(v) = prefs.edit().putBoolean("humor", v).apply()

    // طول الرسالة: "short" أو "medium".
    var messageLength: String
        get() = prefs.getString("messageLength", "short") ?: "short"
        set(v) = prefs.edit().putString("messageLength", v).apply()

    // خلّص شاشة الترحيب؟
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    // إيموجي معبّر في الاقتراحات؟
    var emoji: Boolean
        get() = prefs.getBoolean("emoji", true)
        set(v) = prefs.edit().putBoolean("emoji", v).apply()

    // ---- المظهر ----
    // "system" (تلقائي) / "light" (فاتح) / "dark" (غامق).
    // الهوية العالمية فاتحة أولاً (Porcelain) والداكن (Midnight) خيار للمستخدم.
    var themeMode: String
        get() = prefs.getString("themeMode", "system") ?: "system"
        set(v) = prefs.edit().putString("themeMode", v).apply()

    // لغة واجهة التطبيق: "ar" (عربي RTL) أو "en" (إنجليزي LTR).
    var appLanguage: String
        get() = prefs.getString("appLanguage", "ar") ?: "ar"
        set(v) = prefs.edit().putString("appLanguage", if (Locales.isRegistered(v)) v else "ar").apply()

    // Material You (ألوان من خلفية الجهاز) - أندرويد 12+.
    // الافتراضي مطفي — هوية وصال أولاً؛ يتفعّل من الإعدادات لمن يحب.
    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamicColor", false)
        set(v) = prefs.edit().putBoolean("dynamicColor", v).apply()

    // تذكيرات "بقالك فترة ما كلّمت فلان".
    var reminders: Boolean
        get() = prefs.getBoolean("reminders", true)
        set(v) = prefs.edit().putBoolean("reminders", v).apply()

    var reminderDays: Int
        get() = prefs.getInt("reminderDays", 7)
        set(v) = prefs.edit().putInt("reminderDays", v.coerceIn(1, 90)).apply()

    // ---- المواعيد ----
    var morningTime: String
        get() = prefs.getString("morningTime", "07:00") ?: "07:00"
        set(v) = prefs.edit().putString("morningTime", v).apply()

    var eveningTime: String
        get() = prefs.getString("eveningTime", "21:00") ?: "21:00"
        set(v) = prefs.edit().putString("eveningTime", v).apply()

    var occasions: List<OccasionConfig>
        get() {
            val raw = prefs.getString("occasions", null) ?: return AppConstants.DEFAULT_OCCASIONS
            return runCatching { json.decodeFromString<List<OccasionConfig>>(raw) }
                .getOrDefault(AppConstants.DEFAULT_OCCASIONS)
        }
        set(v) = prefs.edit().putString("occasions", json.encodeToString(v)).apply()

    // ---- الأشخاص (الترابط الأسري) ----
    var recipients: List<Recipient>
        get() {
            val raw = prefs.getString("recipients", null) ?: return emptyList()
            return runCatching { json.decodeFromString<List<Recipient>>(raw) }.getOrDefault(emptyList())
        }
        set(v) = prefs.edit().putString("recipients", json.encodeToString(v)).apply()

    var selectedRecipientId: String
        get() = prefs.getString("selectedRecipientId", "").orEmpty()
        set(v) = prefs.edit().putString("selectedRecipientId", v).apply()

    // ---- الإقران (Wisal Direct) ----
    // بيانات عامة فقط (معرّف + مفتاح عام + اسم) — مفيش أسرار هنا.
    var pairedPeers: List<com.wifeassistant.data.pairing.PairedPeer>
        get() {
            val raw = prefs.getString("pairedPeers", null) ?: return emptyList()
            return runCatching {
                json.decodeFromString<List<com.wifeassistant.data.pairing.PairedPeer>>(raw)
            }.getOrDefault(emptyList())
        }
        set(v) = prefs.edit().putString("pairedPeers", json.encodeToString(v)).apply()

    // معرفات الدعوات المقبولة — منع إعادة استخدام نفس الدعوة على الجهاز ده.
    var acceptedInvitationIds: Set<String>
        get() = prefs.getStringSet("acceptedInvitationIds", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("acceptedInvitationIds", v).apply()

    // ---- الإرسال الجماعي ----
    // كود الدولة الافتراضي (أرقام بس، زي "20") — بيتكمّل للأرقام المستوردة من غير مقدمة.
    var defaultCountryCode: String
        get() = prefs.getString("defaultCountryCode", "20") ?: "20"
        set(v) = prefs.edit().putString("defaultCountryCode", v.filter { it.isDigit() }).apply()

    // مجموعات الإرسال المحفوظة (شغل/مشروع/أسرة...).
    var broadcastGroups: List<BroadcastGroup>
        get() {
            val raw = prefs.getString("broadcastGroups", null) ?: return emptyList()
            return runCatching { json.decodeFromString<List<BroadcastGroup>>(raw) }.getOrDefault(emptyList())
        }
        set(v) = prefs.edit().putString("broadcastGroups", json.encodeToString(v)).apply()

    // قوالب رسائل مفضّلة (نص فيه {الاسم}) — تحفظها وترجعها بضغطة.
    var broadcastTemplates: List<String>
        get() {
            val raw = prefs.getString("broadcastTemplates", null) ?: return emptyList()
            return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }
        set(v) = prefs.edit().putString("broadcastTemplates", json.encodeToString(v)).apply()

    // حسابات المُرسِل (أرقام واتساب بتاعتك: إمارات/مصر/أعمال...).
    var senderAccounts: List<SenderAccount>
        get() {
            val raw = prefs.getString("senderAccounts", null) ?: return emptyList()
            return runCatching { json.decodeFromString<List<SenderAccount>>(raw) }.getOrDefault(emptyList())
        }
        set(v) = prefs.edit().putString("senderAccounts", json.encodeToString(v)).apply()

    // الحساب المختار حاليًا للإرسال منه.
    var selectedSenderId: String
        get() = prefs.getString("selectedSenderId", "").orEmpty()
        set(v) = prefs.edit().putString("selectedSenderId", v).apply()

    // (متقدّم) باك-إند WhatsApp Business Cloud API — إرسال آلي مشروع للعملاء.
    // فاضي = مطفي (بنرجع لفتح واتساب اليدوي). التوكن الحقيقي لـ Meta بيفضل على السيرفر.
    var businessApiEndpoint: String
        get() = prefs.getString("businessApiEndpoint", "").orEmpty()
        set(v) = prefs.edit().putString("businessApiEndpoint", v.trim()).apply()

    var businessApiKey: String
        get() = prefs.getString("businessApiKey", "").orEmpty()
        set(v) = prefs.edit().putString("businessApiKey", v.trim()).apply()

    // اتعرض إشعار الشفافية عن الذكاء الاصطناعي مرة واحدة؟ (مسؤولية + خصوصية).
    var aiNoticeAck: Boolean
        get() = prefs.getBoolean("aiNoticeAck", false)
        set(v) = prefs.edit().putBoolean("aiNoticeAck", v).apply()

    // مناسبات مكتومة (مفيش إشعار ليها). المفتاح: "recipientId|label" للأشخاص، أو label للعامة.
    var mutedOccasions: List<String>
        get() {
            val raw = prefs.getString("mutedOccasions", null) ?: return emptyList()
            return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }
        set(v) = prefs.edit().putString("mutedOccasions", json.encodeToString(v)).apply()

    fun currentRecipient(): Recipient? {
        val list = recipients
        return list.firstOrNull { it.id == selectedRecipientId } ?: list.firstOrNull()
    }

    // أول تشغيل: لو مفيش أشخاص، اعمل واحد افتراضي (من بيانات الزوجة القديمة لو موجودة).
    fun ensureSeed() {
        if (recipients.isNotEmpty()) return
        if (wifeName.isBlank() && wifeNumber.isBlank() && relationshipNotes.isBlank()) return
        val seed = Recipient(
            id = "seed-1",
            name = wifeName,
            relation = "partner_wife",
            number = wifeNumber,
            notes = relationshipNotes,
        )
        recipients = listOf(seed)
        selectedRecipientId = seed.id
    }
}
