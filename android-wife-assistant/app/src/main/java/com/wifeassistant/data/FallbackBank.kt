package com.wifeassistant.data

// بنك رسائل جاهزة بيشتغل من غير نت أو من غير مفتاح Groq. عشان التطبيق ما يقفش أبداً:
// لو التوليد فشل، بنرجّع رسالتين دافئتين يقدر يعدّلهم ويبعتهم. مصري بسيط وصادق.
object FallbackBank {

    // نجمّع العلاقات في فئات عشان النبرة تبقى مناسبة.
    private fun category(relation: String): String = when {
        relation.startsWith("partner") -> "partner"
        relation == "son" || relation == "daughter" -> "child"
        relation == "mother" || relation == "father" -> "parent"
        relation == "brother" || relation == "sister" -> "sibling"
        relation.startsWith("group") -> "group"
        else -> "general"
    }

    // رسائل حسب النيّة (مستقلة عن العلاقة تقريباً) - أولوية لو المستخدم اختار نيّة.
    private val byIntent: Map<String, List<String>> = mapOf(
        "apology" to listOf(
            "آسف بجد لو زعّلتك، مكانش قصدي أبداً. إنت أغلى من أي خناقة، تعالى نصالح 🕊️",
            "غلطت وأنا عارف، بس قلبي معاك. سامحني وننسى اللي فات ونكمّل مع بعض ❤️",
        ),
        "congrats" to listOf(
            "مبروك من قلبي! إنت تستاهل كل خير وأنا فرحان لك بجد 🎉",
            "فرحان لك أوي، تستاهل اللحظة الحلوة دي وأكتر. ألف مبروك 🌟",
        ),
        "comfort" to listOf(
            "أنا معاك، متكونش لوحدك في اللي بتعدّي فيه. خد وقتك وأنا جنبك 🤍",
            "عارف إنها صعبة، بس إنت أقوى. أنا هنا في أي وقت تحتاجني 🫂",
        ),
        "thanks" to listOf(
            "شكراً من قلبي على كل حاجة بتعملها، واللي بتعمله واصل ومقدّر جداً 🙏",
            "متشكر ليك بجد، وجودك في حياتي نعمة مش بشكر ربنا عليها كفاية ❤️",
        ),
        "longing" to listOf(
            "وحشتني بجد، بفكّر فيك وأنا عايز أطمّن عليك وبس 💭",
            "قلبي معاك حتى وإنت بعيد، وحشتني وعايز أشوفك قريب ❤️",
        ),
        "reassure" to listOf(
            "متقلقش، كل حاجة هتبقى تمام وأنا جنبك خطوة بخطوة 🫂",
            "خد نفس، إنت مش لوحدك وأنا معاك مهما حصل. هنعدّيها سوا 🤍",
        ),
        "support" to listOf(
            "إنت قدها وقدود، ثقتي فيك كبيرة. اعمل اللي عليك وربنا معاك 💪",
            "قلبي معاك النهاردة، إنت أقوى مما تتخيل وهتنجح بإذن الله ✨",
        ),
        "dua" to listOf(
            "ربنا يحفظك ويسعدك ويبعد عنك كل وحشة، ويديمك في عافية 🤲",
            "دعوة من قلبي إن ربنا يوفّقك ويفرح قلبك ويكون معاك في كل خطوة 🌙",
        ),
    )

    // رسائل عامة حسب العلاقة - لو مفيش نيّة مختارة.
    private val byCategory: Map<String, List<String>> = mapOf(
        "general" to listOf("أتمنى أن تكون بخير. أحببت أن أطمئن عليك.", "مرحباً، أتمنى لك يوماً طيباً. يسعدني أن نتواصل عندما يناسبك."),
        "partner" to listOf(
            "وجودك في حياتي أحلى حاجة حصلتلي، بحبك وبشكر ربنا عليك كل يوم ❤️",
            "فاكرك دايماً وقلبي معاك، ربنا يخليكي ليا ويسعد قلبك 💗",
        ),
        "child" to listOf(
            "فخور بيك جداً يا حبيبي، ربنا يحفظك ويوفّقك في كل حاجة 🌟",
            "إنت فرحة قلبي، مهما كبرت هتفضل غالي عليا. بحبك ❤️",
        ),
        "parent" to listOf(
            "ربنا يخليك ليا ويطوّل في عمرك، إنت سندي وتاج راسي 🤍",
            "بشكرك على كل حاجة عملتها عشاني، محدش يعوّض تعبك. بحبك 🙏",
        ),
        "sibling" to listOf(
            "إنت سندي وضهري، مهما بعدنا فاكرك دايماً. ربنا يخليك ليا 🤝",
            "وحشتني يا أخويا، نفسي أطمّن عليك ونتكلم زمان. بحبك ❤️",
        ),
        "group" to listOf(
            "بفكّر فيكم كلكم وبتمنالكم كل خير، ربنا يجمعنا دايماً على خير 🤍",
            "وحشتوني بجد، قلبي معاكم وبتمنى نكون قريّبين من بعض دايماً ❤️",
        ),
    )

    // رسالتين مناسبتين للشخص (وللنية لو موجودة).
    fun two(recipient: Recipient?, intent: Intents.Intent?): List<Suggestion> {
        val english = Lang.resolve(recipient?.language, recipient?.name) == Lang.EN
        val professional = recipient?.relation in setOf("colleague", "client", "other", "friend") || recipient == null
        val ar = when (intent?.id) {
            "thanks" -> listOf("شكراً لك على مساعدتك، أقدّر وقتك وجهدك.", "أحببت أن أشكرك. مساعدتك محل تقدير.")
            "apology" -> listOf("أعتذر عمّا حدث. أود أن أفهم كيف يمكنني تصحيح الموقف.", "آسف على الإزعاج. أقدّر تفهّمك وأرحب بسماع رأيك.")
            "follow_up" -> listOf("أتابع معك بخصوص الموضوع الذي تحدثنا عنه. هل لديك أي مستجدات؟", "مرحباً، متى يناسبك أن نتابع هذا الموضوع؟")
            "request" -> listOf("هل يمكنك مساعدتي في هذا الموضوع عندما يناسبك؟", "أود أن أطلب مساعدتك. أخبرني إن كان الوقت مناسباً.")
            else -> byCategory.getValue("general")
        }
        val en = when (intent?.id) {
            "thanks" -> listOf("Thank you for your help. I appreciate your time and effort.", "I wanted to say thank you. Your help means a lot.")
            "apology" -> listOf("I'm sorry about what happened. I'd like to understand how I can put it right.", "I'm sorry for the inconvenience. I appreciate your understanding.")
            "follow_up" -> listOf("I'm following up on what we discussed. Do you have any updates?", "Hello, when would be a good time to follow up on this?")
            "request" -> listOf("Could you help me with this when you have a moment?", "I'd appreciate your help. Please let me know if this is a good time.")
            "congrats" -> listOf("Congratulations! I'm happy for you.", "Wonderful news. Wishing you all the best!")
            else -> listOf("I hope you're doing well. I wanted to check in.", "Hello, I hope you're having a good day. It would be lovely to catch up when it suits you.")
        }
        val theme = intent?.label ?: if (english) "Check in" else "تواصل"
        val texts = if (english) en else if (professional) ar else intent?.let { byIntent[it.id] }
            ?: byCategory[category(recipient?.relation.orEmpty())] ?: ar
        return texts.take(2).map { Suggestion(personalize(it, recipient, english), theme) }
    }

    // لمسة بسيطة: نضيف اسم الشخص لو موجود، بشكل طبيعي في الأول.
    private fun personalize(text: String, recipient: Recipient?, english: Boolean): String {
        val name = recipient?.name?.trim().orEmpty()
        return if (name.isNotBlank()) if (english) "$name, $text" else "$name، $text" else text
    }
}
