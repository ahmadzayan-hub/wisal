package com.wifeassistant.data

import kotlinx.serialization.Serializable

// مناسبة خاصة بشخص (عيد ميلاد/جواز...) - بتتكرر كل سنة بصيغة MM-DD.
@Serializable
data class PersonOccasion(val label: String, val date: String)

// شخص بتتواصل معاه (شريك/شريكة، ابن، بنت، أم، أب، أخ، أخت).
@Serializable
data class Recipient(
    val id: String,
    val name: String,
    val relation: String,        // Relations id
    val number: String = "",     // واتساب (اختياري)
    val notes: String = "",      // حاجات عنه تخصّص بيها الرسالة
    val occasions: List<PersonOccasion> = emptyList(), // مناسباته الخاصة (MM-DD)
    val social: String = "",     // روابط حساباته (مرجع سريع، ما بتتسحبش تلقائياً)
    val tone: String = "",       // نبرة خاصة بيه (فاضي = نبرة العلاقة الافتراضية)
    val dialect: String = "egyptian", // لهجة الرسالة له (مصري/خليجي/شامي/فصحى)
    val language: String = "auto",    // لغة الرسالة حسب لغته الأولى: auto/ar/en
    val photoPath: String = "",  // صورة محلية (اختارها المستخدم من المعرض)
)

// مثال من أسلوبي (رسالة اخترتها أو عدّلتها) - للتعلّم بالسياق، لكل شخص على حدة.
@Serializable
data class StyleExample(
    val text: String,
    val theme: String? = null,
    val date: String,
    val recipientId: String = "",
)

// تسجيل تفاعل واحد (تغذية راجعة).
@Serializable
data class Feedback(
    val date: String,
    val slot: String,
    val themesShown: List<String>,
    val choice: String,          // pick1 / pick2 / edited / ignore / regen
    val finalText: String? = null,
    val recipientId: String = "",
)

// اقتراح واحد + موضوعه.
@Serializable
data class Suggestion(val text: String, val theme: String)

// آخر جولة معروضة (عشان الإشعار والواجهة يشوفوا نفس الاقتراحين).
@Serializable
data class PendingRound(
    val slot: String,
    val themesShown: List<String>,
    val items: List<Suggestion>,
    val occasionLabel: String? = null,
    val recipientId: String = "",
    val offline: Boolean = false,
    val note: String? = null,
    val reviewNote: String? = null,
)

// تجاوز المستخدم لقاعدة أسلوب مشتقّة (شاشة «ما الذي تعلّمه وصال؟»):
// تعطيل، تعديل نص، أو حذف نهائي. مفتاحها id القاعدة الثابت.
@Serializable
data class StyleRuleOverride(
    val enabled: Boolean = true,
    val editedText: String? = null,
    val deleted: Boolean = false,
)

// كل بيانات التعلّم المحفوظة محلياً (JSON في filesDir).
@Serializable
data class AppData(
    val styleExamples: MutableList<StyleExample> = mutableListOf(),
    // تجاوزات قواعد الأسلوب (ruleId -> override) — محلية فقط زي باقي التعلّم.
    val styleRuleOverrides: MutableMap<String, StyleRuleOverride> = mutableMapOf(),
    val themeWeights: MutableMap<String, Double> = mutableMapOf(),
    val feedback: MutableList<Feedback> = mutableListOf(),
    val lastSentPerSlot: MutableMap<String, String> = mutableMapOf(),
    // آخر يوم تواصلت فيه مع كل شخص (recipientId -> YYYY-MM-DD) لتذكيرات "بقالك فترة".
    val lastContactedPerRecipient: MutableMap<String, String> = mutableMapOf(),
    // رسايل محفوظة كمفضّلة (النص) عشان ترجعلها بسرعة من السجل.
    val favorites: MutableList<String> = mutableListOf(),
    var pending: PendingRound? = null,
)

// إعداد مناسبة.
@Serializable
data class OccasionConfig(
    val type: String,                        // "fixed" أو "manual"
    val date: String? = null,                // MM-DD للـ fixed
    val dates: List<String> = emptyList(),   // YYYY-MM-DD للـ manual
    val label: String,
    val enabled: Boolean = true,
)

// نتيجة توليد جاهزة للعرض.
data class GenerationResult(
    val items: List<Suggestion>,
    val themesShown: List<String>,
    val slot: String,
    val offline: Boolean = false,  // true لو رجعنا لبنك الرسائل الجاهز (بدون نت/مفتاح)
    val note: String? = null,      // ملاحظة تتعرض للمستخدم (سبب الرجوع للجاهز)
    val reviewNote: String? = null,
)

// مناسبة النهاردة.
data class Occasion(val key: String, val label: String)

// عضو في مجموعة إرسال (اسم + رقم). مصدره جهات الاتصال أو ملف CSV.
@Serializable
data class GroupMember(val name: String, val number: String)

// حساب مُرسِل (رقم واتساب بتاعك). المستخدم ممكن يكون عنده كذا رقم (إمارات/مصر/أعمال).
// channel بيحدّد التطبيق اللي بيتفتح: واتساب عادي أو واتساب Business.
// ملاحظة: رقمين واتساب شخصيين على نفس التطبيق مايتفرّقش برمجيًا — ده قيد واتساب نفسه
// (محتاج تطبيق مزدوج/شريحتين على الجهاز). الحساب هنا بيظبط الكود والتوقيع والتطبيق الهدف.
@Serializable
data class SenderAccount(
    val id: String,
    val label: String,                 // "الإمارات" / "مصر" / "الأعمال"
    val channel: String = "whatsapp",  // whatsapp / whatsapp_business
    val countryCode: String = "",      // كود الدولة الافتراضي لأرقام الحساب ده
    val signature: String = "",        // توقيع بيتزاد آخر الرسالة (اختياري)
)

// مجموعة إرسال (شغل/مشروع/أسرة...) — تجميعة ناس بتبعتلهم رسالة مخصّصة بضغطة لكل واحد.
@Serializable
data class BroadcastGroup(
    val id: String,
    val name: String,
    val kind: String = "work",   // work/project/family/friends/clients/other
    val members: List<GroupMember> = emptyList(),
)
