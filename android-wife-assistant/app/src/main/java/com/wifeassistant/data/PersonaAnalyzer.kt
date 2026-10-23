package com.wifeassistant.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// نتيجة تحليل شخصية المستقبِل (من معلومات المستخدم بيلصقها بنفسه - مش سحب تلقائي).
data class PersonaResult(
    val nickname: String = "",
    val interests: String = "",
    val toneHint: String = "",
    val notes: String = "",
    val occasions: List<PersonOccasion> = emptyList(),
)

// بيحلّل المعلومات اللي المستخدم بيلصقها عن الشخص (بايو/بوست/رسايل) ويبني ملف
// يخصّص بيه الرسائل + يطلّع المناسبات لو مذكورة. بيشتغل على المكتوب بس، ما بيخترعش.
class PersonaAnalyzer(private val groq: GroqClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mmdd = Regex("^\\d{2}-\\d{2}$")

    suspend fun analyze(
        relationLabel: String,
        name: String,
        social: String,
        info: String,
    ): PersonaResult {
        val sys = "انت بتحلّل معلومات عن شخص عشان تساعد حد يكتبله رسائل مخصّصة. " +
            "اشتغل بس على المعلومات المكتوبة تحت وما تخترعش أي حاجة. " +
            "لا تستنتج الشخصية أو الصحة أو الدين أو السياسة أو أي صفات حساسة. استخرج فقط تفضيلات تواصل صريحة. " +
            "لو معلومة مش موجودة سيبها فاضية. رجّع JSON بس من غير أي كلام قبله ولا بعده."
        val user = buildString {
            appendLine("العلاقة: $relationLabel")
            if (name.isNotBlank()) appendLine("الاسم/الدلع الحالي: $name")
            if (social.isNotBlank()) appendLine("روابط حساباته (للاستئناس بالاسم بس، مش هتفتحها): $social")
            appendLine("المعلومات اللي المستخدم لصقها عنه:")
            appendLine(info.ifBlank { "(مفيش معلومات ملصوقة)" })
            appendLine()
            appendLine("رجّع بالظبط بالشكل ده:")
            appendLine("{\"nickname\":\"\",\"interests\":\"\",\"toneHint\":\"\",\"notes\":\"\",\"occasions\":[{\"label\":\"\",\"date\":\"MM-DD\"}]}")
            appendLine("- nickname: دلع مناسب لو ظهر، غير كده فاضي.")
            appendLine("- interests: اهتماماته واللي بيحبه في جملة أو اتنين.")
            appendLine("- toneHint: نبرة طلبها هذا الشخص صراحة فقط، وإلا فاضي.")
            appendLine("- notes: ملخّص يخصّص بيه الرسائل (حاجات عنه).")
            appendLine("- occasions: مناسبات بتاريخ MM-DD بس لو مذكورة صراحة، غير كده مصفوفة فاضية.")
        }
        val raw = groq.complete(
            listOf(ChatMessage("system", sys), ChatMessage("user", user)),
            temperature = 0.3,
        )
        return parse(raw)
    }

    private fun parse(raw: String): PersonaResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return PersonaResult(notes = raw.trim())
        val obj = runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return PersonaResult()

        fun field(key: String): String =
            obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val occs = obj["occasions"]?.jsonArray?.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val label = o["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val date = o["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (label.isNotBlank() && mmdd.matches(date)) PersonOccasion(label, date) else null
        } ?: emptyList()

        return PersonaResult(
            nickname = field("nickname"),
            interests = field("interests"),
            toneHint = field("toneHint"),
            notes = field("notes"),
            occasions = occs,
        )
    }
}
