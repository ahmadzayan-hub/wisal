package com.wifeassistant.data

// أنواع العلاقات - هدف التطبيق: الترابط الأسري والتواصل العاطفي الإنساني.
// لكل علاقة نبرة مناسبة عشان الرسالة تطلع صادقة وطبيعية حسب مين المستقبِل.
object Relations {
    data class Relation(
        val id: String,
        val label: String,   // للعرض (زوجتي، ابني...)
        val toAddr: String,  // "لـ..." تستخدم في البرومبت
        val tone: String,    // النبرة المطلوبة
        val emoji: String,   // أفاتار بصري
    )

    val ALL = listOf(
        Relation("other", "شخص آخر", "لهذا الشخص", "واضحة ومحترمة، بدون افتراض درجة قرب أو وعود", "👤"),
        Relation("friend", "صديق / صديقة", "لصديق", "ودّية وطبيعية، بلا رومانسية مفترضة", "🤝"),
        Relation("colleague", "زميل / زميلة", "لزميل", "مهنية وودّية، واضحة ومختصرة وتحترم الوقت", "💼"),
        Relation("client", "عميل / عميلة", "لعميل", "مهنية ومحترمة، بلا وعود أو التزامات غير مؤكدة", "🗂️"),
        Relation("partner_wife", "زوجتي", "لمراتي", "حب رومانسي دافئ وصادق، حنية وشوق من غير مبالغة", "💗"),
        Relation("partner_husband", "زوجي", "لجوزي", "حب رومانسي دافئ وصادق، حنية وشوق من غير مبالغة", "💗"),
        Relation("son", "ابني", "لابني", "حنان وفخر وتشجيع وأمان، كلام بيقرّب", "👦"),
        Relation("daughter", "بنتي", "لبنتي", "حنان وفخر ولطف وحماية دافئة", "👧"),
        Relation("mother", "أمي", "لأمي", "احترام وحب عميق وامتنان وحنية", "👩"),
        Relation("father", "أبويا", "لأبويا", "احترام وحب وتقدير وامتنان", "👨"),
        Relation("brother", "أخويا", "لأخويا", "ود وسند وأخوّة وروح مرحة خفيفة", "🧑"),
        Relation("sister", "أختي", "لأختي", "ود وسند وحنية وأخوّة", "👩‍🦰"),
        // مجموعات - الرسالة تناسب أكتر من شخص (تتبعت عبر منتقي واتساب أو مشاركة).
        Relation("group_family", "العيلة", "للعيلة", "دفء أسري جامع يحسّس الكل بالحب والترابط", "👨‍👩‍👧‍👦"),
        Relation("group_friends", "الأصحاب", "للأصحاب", "ود وروح مرحة وصداقة حلوة", "🧑‍🤝‍🧑"),
        Relation("group", "مجموعة", "للمجموعة", "دافئ وجامع ومناسب لكذا شخص", "👥"),
    )

    fun byId(id: String): Relation = ALL.firstOrNull { it.id == id } ?: ALL[0]
    fun labelOf(id: String): String = t(byId(id).label, when (id) {
        "other" -> "Other person"
        "friend" -> "Friend"
        "colleague" -> "Colleague"
        "client" -> "Customer"
        "partner_wife" -> "Wife"
        "partner_husband" -> "Husband"
        "son" -> "Son"
        "daughter" -> "Daughter"
        "mother" -> "Mother"
        "father" -> "Father"
        "brother" -> "Brother"
        "sister" -> "Sister"
        "group_family" -> "Family group"
        "group_friends" -> "Friends group"
        "group" -> "Group"
        else -> "Other person"
    })
    fun emojiOf(id: String): String = byId(id).emoji
}
