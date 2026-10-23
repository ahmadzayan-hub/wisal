package com.wifeassistant.data

// نيّة الرسالة - السبب الحقيقي اللي المستخدم فاتح التطبيق عشانه.
// بتوجّه التوليد للموقف بدل موضوع عشوائي (اعتذار، تهنئة، مواساة...).
object Intents {
    data class Intent(
        val id: String,
        val label: String,
        val emoji: String,
        val hint: String, // توجيه للموديل يوصف المطلوب من الرسالة.
    )

    val ALL = listOf(
        Intent("check_in", "اطمئنان", "👋", "سؤال لطيف عن الحال، بدون لوم أو افتراض مشكلة."),
        Intent("follow_up", "متابعة", "📌", "متابعة واضحة ومحترمة بخصوص السياق، بلا ضغط أو مواعيد أو التزامات مخترعة."),
        Intent("request", "طلب", "💬", "طلب واضح ومهذب، يترك للشخص مساحة للاعتذار أو اختيار الوقت المناسب."),
        Intent("apology", "اعتذار", "🕊️", "رسالة اعتذار صادقة بتصالح وتكسر الزعل، بتعترف بالغلط بلطف من غير تبرير زيادة."),
        Intent("congrats", "تهنئة", "🎉", "تهنئة فرحانة بمناسبة سعيدة، بتحسّسه إنك مبسوط له وفخور بيه."),
        Intent("comfort", "مواساة", "🤍", "مواساة وتخفيف في وقت صعب، حضور وسند ودفء من غير كلام جارح ولا وعظ."),
        Intent("thanks", "شكر", "🙏", "شكر وامتنان على حاجة عملها، بتحسّسه إن اللي بيعمله واصل ومقدّر."),
        Intent("longing", "اشتياق", "💭", "اشتياق ولهفة ودفء، إنك فاكره وإنه وحشك من غير مبالغة."),
        Intent("reassure", "طمأنة", "🫂", "طمأنة وتهدئة قلق، إنك جنبه وإن كل حاجة هتبقى تمام."),
        Intent("support", "دعم", "💪", "تشجيع ودعم وثقة في قدراته قبل موقف أو تحدي مهم."),
        Intent("dua", "دعاء", "🤲", "دعاء من القلب بالخير والستر والصحة، بلمسة إيمانية بسيطة صادقة."),
        Intent("reconnect", "إعادة تواصل", "🌉", "رسالة بسيطة تكسر برود المسافة بعد فترة انقطاع، بتفتح الكلام بلطف من غير عتاب ولا لوم، بتحسّسه إنك افتكرته وإنه وحشك."),
    )

    fun labelOf(intent: Intent): String = t(intent.label, when (intent.id) {
        "check_in" -> "Check in"
        "follow_up" -> "Follow up"
        "request" -> "Ask"
        "apology" -> "Apologize"
        "congrats" -> "Celebrate"
        "comfort" -> "Comfort"
        "thanks" -> "Thank"
        "longing" -> "Miss you"
        "reassure" -> "Reassure"
        "support" -> "Encourage"
        "dua" -> "Prayer"
        "reconnect" -> "Reconnect"
        else -> intent.label
    })

    fun byId(id: String?): Intent? = if (id == null) null else ALL.firstOrNull { it.id == id }
}
