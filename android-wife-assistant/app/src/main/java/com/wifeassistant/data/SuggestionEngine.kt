package com.wifeassistant.data

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

// قلب التوليد - نظير generateSuggestion.js.
// تعلّم بالسياق (few-shot) + ترجيح المواضيع + تنويع + مرساة صوت ثابتة.
class SuggestionEngine(
    private val store: Store,
    private val groq: GroqClient,
    private val settings: Settings,
) {
    suspend fun generate(
        slot: String,
        occasion: Occasion? = null,
        intentId: String? = null,
        context: String = "",
    ): GenerationResult {
        val intent = Intents.byId(intentId)
        val themes = when {
            intent != null -> listOf(intent.label, intent.label)
            occasion != null -> listOf(occasion.label, occasion.label)
            else -> pickThemes(2)
        }
        return try {
            val raw = groq.complete(
                listOf(
                    ChatMessage("system", buildSystemPrompt(intent)),
                    ChatMessage("user", buildUserPrompt(slot, occasion, themes, intent, context)),
                )
            )
            GenerationResult(parseTwo(raw, themes), themes, slot)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            // مفيش نت أو مفيش مفتاح: نرجّع بنك الرسائل الجاهز عشان التطبيق ما يقفش.
            val items = FallbackBank.two(settings.currentRecipient(), intent)
            val reason = when ((e as? GenerationException)?.reason) {
                "disabled" -> t("Groq غير مفعّل.", "Groq is off.")
                "missing_key" -> t("أضف مفتاح Groq في الإعدادات.", "Add a Groq key in Settings.")
                "authentication" -> t("مفتاح Groq غير صالح.", "Groq rejected the API key.")
                "permission" -> t("الحساب لا يسمح بهذا النموذج.", "Your account cannot access this model.")
                "retired_model" -> t("النموذج متوقف. اختر بديلاً في الإعدادات.", "This model is retired. Choose a replacement in Settings.")
                "quota" -> t("انتهت حصة الحساب. القوالب المحلية ما زالت متاحة.", "Your account quota is exhausted. Local templates remain available.")
                "model" -> t("راجع النموذج المختار في الإعدادات؛ قد يكون متوقفاً.", "Check the model in Settings; it may be retired.")
                "rate_limit" -> t("وصلت لحد استخدام Groq. حاول لاحقاً.", "Groq's usage limit was reached. Try later.")
                "too_long" -> t("السياق طويل جداً. اختصره وحاول مجدداً.", "The context is too long. Shorten it and retry.")
                "invalid_response" -> t("لم يرجع Groq نصاً صالحاً.", "Groq returned no usable text.")
                else -> t("تعذّر الاتصال بـ Groq الآن.", "Groq is unavailable right now.")
            }
            val note = reason + " " + t("هذه قوالب محلية، وليست نتيجة ذكاء اصطناعي. راجع اللغة والتفاصيل.", "These are local templates, not AI output. Review their language and details.")
            GenerationResult(items, items.map { it.theme }, slot, offline = true, note = note)
        }
    }

    // تحرير تكراري لاقتراح موجود (أطول/أقصر/أرومانسي/أبسط...). يرجّع نص واحد.
    suspend fun refine(text: String, styleId: String): String {
        val hint = when (styleId) {
            "longer" -> "أطول شوية وأدفى، من غير حشو ولا تكرار"
            "shorter" -> "أقصر وأكثف، في سطر أو اتنين بحد أقصى"
            "romantic" -> "أرومانسي وأحنّ، مشاعر أوضح من غير مبالغة"
            "simpler" -> "أبسط وأوضح، بكلمات يومية سهلة"
            "formal" -> "أكتر احترام ورسمية شوية"
            // نبرة سريعة: نفس المعنى بمزاج مختلف بضغطة.
            "playful" -> "أخف وأمرح، لمسة هزار خفيف ولطيف من غير سخافة"
            "serious" -> "أجدّ وأهدى، نبرة رزينة صادقة من غير هزار"
            "apology" -> "فيها اعتذار وطيبة، بتصالح وتذوّب الزعل بصدق من غير تبرير زيادة"
            "grateful" -> "فيها امتنان وشكر واضح، بتحسّسه إن اللي عمله واصل ومقدّر"
            "reassure" -> "بتطمّن وتهدّي، إنك جنبه وإن كل حاجة هتبقى تمام"
            else -> "أحلى وأصدق"
        }
        val rel = Relations.byId(settings.currentRecipient()?.relation ?: "other")
        val lang = Lang.resolve(settings.currentRecipient()?.language, settings.currentRecipient()?.name)
        val sys = Lang.promptDirective(lang) + " انت بتعيد صياغة رسالة قصيرة. " +
            "حافظ على نفس المعنى والصدق والدفء، ورجّع النص بس من غير أي كلام قبله أو بعده."
        val user = "أعد صياغة الرسالة دي بحيث تبقى $hint (النبرة تناسب ${rel.label}):\n\n$text"
        return groq.complete(listOf(ChatMessage("system", sys), ChatMessage("user", user)), 0.7).trim()
    }

    // أفكار عملية للمناسبة (هدية/كارت/ورد/لمسة) مخصّصة بالشخصية. أفكار وفئة سعرية
    // تقريبية بس - مفيش أسعار حقيقية (التطبيق مش متصفّح).
    suspend fun giftIdeas(occasionLabel: String): String {
        val r = settings.currentRecipient()
        val rel = Relations.byId(r?.relation ?: "other")
        val who = r?.name?.ifBlank { rel.label } ?: rel.label
        val notes = r?.notes?.takeIf { it.isNotBlank() }
        val sys = "انت مستشار لطيف بتقترح أفكار عملية لمناسبة. اكتب مصري بسيط. " +
            "ماتخترعش أسعار حقيقية ولا أسماء محلات، اكتب أفكار وفئة سعرية تقريبية بس (رخيّص/متوسط)."
        val user = buildString {
            appendLine("المناسبة: $occasionLabel")
            appendLine("الشخص: $who (${rel.label}).")
            if (notes != null) appendLine("حاجات عنه تساعد في الاختيار: $notes")
            appendLine()
            appendLine("اقترح 3 أو 4 أفكار عملية تناسب شخصيته والمناسبة (هدية بسيطة/كارت/ورد/لفتة/طلعة).")
            appendLine("كل فكرة في سطر تبدأ بإيموجي، ومعاها فئة سعرية تقريبية بين قوسين (رخيّص/متوسط).")
            appendLine("خلّي فكرة على الأقل اقتصادية بلمسة شخصية. الأفكار بس من غير مقدّمة.")
        }
        return groq.complete(listOf(ChatMessage("system", sys), ChatMessage("user", user)), 0.8).trim()
    }

    // اختيار موضوعين: تجنّب المستخدم مؤخراً + ترجيح بالوزن.
    private fun pickThemes(count: Int): List<String> {
        val weights = store.themeWeights()
        val recent = store.recentThemes(AppConstants.AVOID_RECENT_THEME_DAYS)
        var pool = AppConstants.THEMES.filter { it !in recent }
        if (pool.size < count) pool = AppConstants.THEMES.toList()

        val available = pool.toMutableList()
        val chosen = mutableListOf<String>()
        repeat(count) {
            if (available.isEmpty()) return@repeat
            val picked = weightedRandom(available, weights)
            chosen.add(picked)
            available.remove(picked)
        }
        return chosen
    }

    private fun weightedRandom(items: List<String>, weights: Map<String, Double>): String {
        val total = items.sumOf { weights[it] ?: 1.0 }
        var r = Random.nextDouble() * total
        for (it in items) {
            r -= weights[it] ?: 1.0
            if (r <= 0) return it
        }
        return items.last()
    }

    // مرساة الصوت - النبرة الأساسية حسب نوع العلاقة (شريك/ابن/أم/أخ...).
    private fun buildSystemPrompt(intent: Intents.Intent? = null): String {
        val recipient = settings.currentRecipient()
        val rel = Relations.byId(recipient?.relation ?: "other")
        // نبرة ولهجة خاصة بالشخص لو موجودة، غير كده الافتراضي.
        val tone = recipient?.tone?.takeIf { it.isNotBlank() } ?: rel.tone
        val dialect = AppConstants.dialectPhrase(recipient?.dialect ?: "egyptian")
        // لغة الرسالة حسب لغة الشخص الأولى (auto: نكشف من اسمه).
        val lang = Lang.resolve(recipient?.language, recipient?.name)
        val lines = mutableListOf(
            "انت بتساعد شخص يكتب رسالة قصيرة ${rel.toAddr} (${rel.label}) بـ$dialect.",
            "النبرة المناسبة: $tone.",
            "اكتب كإنسان حقيقي بمشاعر صادقة ودفء إنساني - مش كلام آلة.",
            "قواعد ثابتة لا تتغيّر:",
            "- لا تستنتج شخصية أو صفات حساسة من الاسم أو العلاقة. استخدم فقط تفضيلات التواصل التي اختارها المستخدم.",
            "- لا تضف وعوداً أو التزامات أو معلومات غير موجودة في السياق. العلاقة ليست دليلاً على درجة القرب.",
            if (settings.messageLength == "medium")
                "- الرسالة من سطرين لـ 3 أسطر."
            else
                "- الرسالة قصيرة ومركّزة: سطر أو سطرين بحد أقصى.",
            "- $dialect طبيعية، كأنه هو اللي كتبها.",
            "- صدق وبساطة، من غير مبالغة ولا كلام مصنوع ولا شِعر متكلّف.",
            if (settings.emoji)
                "- استخدم إيموجي أو اتنين معبّرين عن روح الرسالة ومناسبين للعلاقة والشخص، بإبداع وذوق ومن غير مبالغة."
            else
                "- من غير إيموجي خالص.",
        )
        if (settings.humor) {
            lines.add("- حطّ لمسة خفيفة من الدُعابة الحلوة اللطيفة، من غير سخافة ولا مبالغة.")
        }
        if (intent != null) {
            lines.add("نوع الرسالة المطلوب: ${intent.label}. ${intent.hint}")
        }
        lines.add("هدفك: اقتراح هو يبعته بنفسه ${rel.toAddr} عشان يقرّب ويقوّي الترابط.")
        // لو لغة الشخص إنجليزي، نحقن توجيه غالب في الأول عشان يكتب بالإنجليزي بالكامل.
        if (lang == Lang.EN) lines.add(0, Lang.promptDirective(Lang.EN))
        return lines.joinToString("\n")
    }

    // بلوك التخصيص - بيخلّي الرسالة للشخص ده بالذات ومنّه هو (شخصنة إنسانية).
    private fun buildPersonaBlock(): String {
        val r = settings.currentRecipient()
        val parts = mutableListOf<String>()
        if (settings.myName.isNotBlank()) parts.add("اسم اللي بيبعت: ${settings.myName.trim()}.")
        if (r != null && r.name.isNotBlank()) {
            parts.add("اسم اللي بيتبعتله: ${r.name.trim()} - نادِه باسمه أو دلعه بشكل طبيعي.")
        }
        if (r != null && r.notes.isNotBlank()) {
            parts.add("حاجات عنه تخصّص بيها الرسالة: ${r.notes.trim()}.")
        }
        if (parts.isEmpty()) return ""
        return ("تخصيص (خلّي التفاصيل دي محسوسة في الرسالة، بس من غير ما تسردها صريحة):\n" +
            parts.joinToString("\n"))
    }

    // حقن أمثلة الأسلوب (few-shot) من اختياراتي السابقة للشخص ده.
    private fun buildStyleBlock(): String {
        val rid = settings.currentRecipient()?.id ?: ""
        val examples = store.styleExamples(rid)
        if (examples.isEmpty()) return "لسه مفيش أمثلة من أسلوبي. اكتب بنبرة دافئة بسيطة طبيعية."
        val lines = examples.takeLast(AppConstants.STYLE_EXAMPLES_MAX)
            .mapIndexed { i, e -> "${i + 1}) ${e.text}" }
            .joinToString("\n")
        // قواعد الأسلوب المفعّلة (من شاشة «ما الذي تعلّمه وصال؟») —
        // اللي المستخدم عطّله أو مسحه ما بيدخلش التوجيه خالص.
        val rules = store.activeStyleRules(rid)
        val rulesBlock = if (rules.isEmpty()) "" else
            "\nعادات مؤكّدة من أسلوبي التزم بيها: " + rules.joinToString("؛ ") { it.text }
        return listOf(
            "دي أمثلة من رسايل اخترتها أو عدّلتها قبل كده - ده أسلوبي وصوتي.",
            "قلّد روح الأمثلة دي (اختيار الكلمات والإيقاع) من غير ما تنسخها حرفياً:",
            lines,
        ).joinToString("\n") + rulesBlock
    }

    private fun buildUserPrompt(
        slot: String,
        occasion: Occasion?,
        themes: List<String>,
        intent: Intents.Intent? = null,
        context: String = "",
    ): String {
        val slotLabel = when (slot) {
            "morning" -> "الصبح"
            "evening" -> "بالليل"
            else -> "دلوقتي"
        }
        val situation = when {
            intent != null -> "المطلوب دلوقتي: ${intent.label}. ${intent.hint}"
            occasion != null -> "النهاردة مناسبة: ${occasion.label}. اكتب رسالة مخصصة للمناسبة دي."
            else -> "الوقت: $slotLabel. الموضوع المطلوب لكل اقتراح موجود تحت."
        }
        val themeLine = when {
            intent != null -> "الاتنين عن: ${intent.label}، بس كل واحدة بأسلوب وزاوية مختلفة."
            occasion != null -> "الاتنين عن: ${occasion.label}."
            else -> "الاقتراح الأول موضوعه: ${themes[0]}. الاقتراح التاني موضوعه: ${themes[1]}."
        }

        val persona = buildPersonaBlock()
        val blocks = mutableListOf(buildStyleBlock())
        if (persona.isNotBlank()) { blocks.add(""); blocks.add(persona) }
        if (context.isNotBlank()) {
            blocks.add("")
            blocks.add("سياق مهم من المستخدم عن الموقف (خلّي الرسالة تتكلم عنه بشكل طبيعي من غير ما تنقله حرفياً): ${context.trim()}")
        }
        blocks.addAll(
            listOf(
                "",
                situation, "",
                "اكتب اقتراحين مختلفين تماماً عن بعض، وكإنهم من قلبه هو.", themeLine, "",
                "رجّع الرد بالظبط بالصيغة دي ومن غير أي كلام زيادة:",
                "١- <نص الاقتراح الأول>",
                "٢- <نص الاقتراح التاني>",
            )
        )
        return blocks.joinToString("\n")
    }

    companion object {
        // تحليل رد الموديل لاقتراحين - يفضّل الأسطر المرقّمة فعلاً ويتجاهل التمهيد.
        // دالة نقية (بدون I/O) عشان تتختبر بسهولة.
        internal fun parseTwo(raw: String, themes: List<String>): List<Suggestion> {
            val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val numberedRe = Regex("^\\s*[١٢12]\\s*[-.)]\\s*(.+)$")
            val numbered = lines.mapNotNull { numberedRe.find(it)?.groupValues?.get(1)?.trim() }

            val first: String
            val second: String
            if (numbered.size >= 2) {
                first = numbered[0]; second = numbered[1]
            } else {
                val stripRe = Regex("^\\s*[١٢12]\\s*[-.)]\\s*")
                val cleaned = lines.map { it.replace(stripRe, "").trim() }.filter { it.isNotEmpty() }
                first = cleaned.getOrNull(0) ?: raw.trim()
                second = cleaned.getOrNull(1) ?: cleaned.getOrNull(0) ?: raw.trim()
            }
            val t0 = themes.getOrElse(0) { "" }
            val t1 = themes.getOrElse(1) { t0 }
            return listOf(Suggestion(first, t0), Suggestion(second, t1))
        }
    }
}
