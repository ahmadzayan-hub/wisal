package com.wifeassistant.data

// ثوابت المشروع (نظير config.js في بوت التيليجرام).
object AppConstants {
    val THEMES = listOf(
        "امتنان", "اشتياق", "تمني يوم جميل", "تقدير", "دعم", "دعاء", "كلمة من القلب"
    )
    const val STYLE_EXAMPLES_MAX = 30
    const val AVOID_RECENT_THEME_DAYS = 3L
    const val TIMEZONE = "Asia/Dubai"
    const val TONE = "دافئة، صادقة، بسيطة، من غير مبالغة ولا كلام مصنوع"
    const val DEFAULT_MODEL = "openai/gpt-oss-20b"

    // Official catalog checked 2026-10-23. Free-plan access depends on the user's organization.
    val MODELS = listOf(
        "openai/gpt-oss-20b" to "GPT OSS 20B",
        "openai/gpt-oss-120b" to "GPT OSS 120B",
    )
    const val MODEL_CATALOG_REVIEWED = "2026-10-23"
    const val MODEL_CATALOG_SOURCE = "https://console.groq.com/docs/models"
    const val MODEL_LIMITS_SOURCE = "https://console.groq.com/docs/rate-limits"
    val RETIRED_MODELS = setOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")

    // طول الرسالة (id -> اسم).
    val LENGTHS = listOf(
        "short" to "قصيرة",
        "medium" to "متوسطة",
    )

    // اللهجات المتاحة لكل شخص (id -> اسم).
    val DIALECTS = listOf(
        "egyptian" to "مصري",
        "gulf" to "خليجي",
        "levantine" to "شامي",
        "msa" to "فصحى",
    )

    // وصف اللهجة للبرومبت.
    fun dialectPhrase(id: String): String = when (id) {
        "gulf" -> "اللهجة الخليجية"
        "levantine" -> "اللهجة الشامية"
        "msa" -> "العربية الفصحى البسيطة"
        else -> "اللهجة المصرية العامية"
    }

    // مناسبات افتراضية - نفس اللي في بوت التيليجرام. المستخدم يقدر يعدّلها.
    val DEFAULT_OCCASIONS = listOf(
        OccasionConfig("fixed", date = "08-24", label = "عيد ميلاد مراتي"),
        OccasionConfig("fixed", date = "02-14", label = "عيد الحب (الفلانتين)"),
        OccasionConfig("fixed", date = "11-04", label = "عيد الحب المصري"),
        OccasionConfig("manual", dates = listOf("2026-08-25", "2027-08-14"), label = "المولد النبوي"),
        OccasionConfig("manual", dates = listOf("2027-02-08"), label = "أول رمضان"),
        OccasionConfig("manual", dates = listOf("2027-03-10"), label = "عيد الفطر"),
        OccasionConfig("manual", dates = listOf("2027-05-16"), label = "عيد الأضحى"),
        OccasionConfig("manual", dates = listOf("2027-06-06"), label = "رأس السنة الهجرية"),
    )
}
