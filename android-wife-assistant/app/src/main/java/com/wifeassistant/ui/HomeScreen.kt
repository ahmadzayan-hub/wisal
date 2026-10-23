package com.wifeassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import com.wifeassistant.data.DraftStage
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifeassistant.data.Intents
import com.wifeassistant.data.Occasion
import com.wifeassistant.data.Recipient
import com.wifeassistant.data.Relations
import com.wifeassistant.data.Suggestion
import com.wifeassistant.data.t
import com.wifeassistant.ui.theme.GradientButton
import com.wifeassistant.util.CalendarReader
import com.wifeassistant.util.Share
import com.wifeassistant.util.WhatsApp
import com.wifeassistant.work.SendReminder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenBroadcast: () -> Unit = {},
    onOpenReply: () -> Unit = {},
    onOpenPolish: () -> Unit = {},
) {
    val motionAllowed = rememberMotionAllowed()
    var reviewedMode by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }
    var showIntent by remember { mutableStateOf(false) }
    var editBrief by remember { mutableStateOf(true) }
    val pageScroll = rememberScrollState()
    val state by vm.state.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.cancelGeneration() } }
    val ideas by vm.ideas.collectAsStateWithLifecycle()
    val next by vm.nextAction.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var moreTools by remember { mutableStateOf(false) }
    var selectedIntent by remember { mutableStateOf<String?>(null) }
    var contextText by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // إشعار شفافية الذكاء (مرة واحدة): مسؤولية + خصوصية قبل أول استخدام.
    val settings = remember { com.wifeassistant.data.Settings(context) }
    var showAiNotice by remember { mutableStateOf(!settings.aiNoticeAck && settings.groqEnabled) }
    if (showAiNotice) {
        AlertDialog(
            onDismissRequest = { }, // لازم يقر بيها عشان نضمن إنه شافها
            title = { Text(t("شفافية الذكاء الاصطناعي 🤖", "AI transparency 🤖")) },
            text = {
                Text(
                    t(
                        "• القوالب محلية، وصياغات Groq تستخدم ذكاء اصطناعياً عند تفعيله. راجع النص قبل المشاركة.\n" +
                            "• لما تطلب توليد، بيتبعت نص الموقف/السياق لمزوّد الذكاء (Groq) عشان يجهّز الرسالة.\n" +
                            "• التخزين محلي، لكن الاسم والعلاقة والملاحظات وأمثلة أسلوبك تدخل طلب Groq عند تفعيله. تجنّب المعلومات الحساسة.\n" +
                            "• مفيش أي رسالة بتتبعت تلقائيًا — الضغطة الأخيرة دايمًا بإيدك.",
                        "• Templates are local; Groq drafts use AI when enabled. Review the text before sharing.\n" +
                            "• When you ask for a suggestion, the situation/context text goes to the AI provider (Groq) to craft the message.\n" +
                            "• Storage is local, but the name, relationship, notes and style examples are included in Groq requests when enabled. Avoid sensitive information.\n" +
                            "• Nothing is ever sent automatically — the final tap is always yours.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = { settings.aiNoticeAck = true; showAiNotice = false }) { Text(t("فهمت 👍", "Got it 👍")) }
            },
        )
    }

    // كل ما نرجع للشاشة نحدّث قائمة الأشخاص (ممكن اتغيّرت من شاشة الأشخاص).
    LaunchedEffect(Unit) { vm.refreshRecipients() }
    val current = people.current
    var boundRecipient by remember { mutableStateOf(current?.id) }
    LaunchedEffect(current?.id) {
        if (boundRecipient != null && boundRecipient != current?.id) { contextText = ""; selectedIntent = null }
        boundRecipient = current?.id
        editBrief = true
    }
    LaunchedEffect(state.items.isNotEmpty()) { if (state.items.isNotEmpty()) { editBrief = false; if (motionAllowed) pageScroll.animateScrollTo(0) else pageScroll.scrollTo(0) } }

    // ---- تقويم الموبايل (Google Calendar وغيره) ----
    var calEvents by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCal by remember { mutableStateOf(false) }
    fun loadCalendar() {
        // قراءة التقويم في الخلفية (IO) — عشان ما نهنّجش الواجهة.
        scope.launch {
            val titles = withContext(Dispatchers.IO) { CalendarReader.events(context).map { it.title } }
            calEvents = titles
            showCal = true
        }
    }
    val calPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadCalendar()
        else Toast.makeText(context, t("محتاج إذن التقويم عشان أقرأ أجندتك", "I need calendar permission to read your agenda"), Toast.LENGTH_LONG).show()
    }
    fun openCalendar() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) loadCalendar() else calPermission.launch(Manifest.permission.READ_CALENDAR)
    }

    // نعرض رسائل الحالة (نجاح/خطأ) كـ Snackbar لطيف.
    LaunchedEffect(state.info) { if (!state.offline) state.info?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar("⚠️ $it") } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(t("وصال", "Wisal"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Settings, contentDescription = t("الإعدادات", "Settings")) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().imePadding()) {
        val wide = maxWidth >= 840.dp
        Column(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 1040.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(pageScroll).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.items.isEmpty() || editBrief) {
                Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t("رسالة تبدأ بفكرتك", "Start with what you mean"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(t("اكتب ما تريد قوله. نساعدك في الصياغة، والقرار لك.", "Bring the meaning. Shape the words. Keep the final say."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("إلى", "TO"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(current?.let { it.name.ifBlank { Relations.labelOf(it.relation) } } ?: t("اختر شخصاً", "Choose a person"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = { showPeople = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(if (current == null) t("اختيار شخص", "Choose person") else t("تغيير", "Change")); Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            TextField(value = contextText,
                                onValueChange = { if (state.loading) vm.cancelGeneration(); contextText = it },
                                placeholder = { Text(t("ماذا تريد أن تقول؟\nمثلاً: أريد متابعة موعد مناسب لمراجعة المسودة.", "What would you like to say?\nFor example: ask for a good time to review the draft."), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 7,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(onClick = { showIntent = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(Intents.byId(selectedIntent)?.let { Intents.labelOf(it) } ?: t("هدف الرسالة", "Message purpose"))
                                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                TextButton(onClick = { showOptions = !showOptions }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("  " + t("اللغة والأسلوب", "Language & style"))
                                }
                            }
                            WisalDisclosure(showOptions, motionAllowed) {
                                current?.let { person ->
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("ar" to "العربية", "en" to "English").forEach { (id, label) ->
                                            FilterChip(selected = com.wifeassistant.data.Lang.resolve(person.language, person.name) == id,
                                                onClick = { vm.updatePreferences(language = id) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                                        }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("" to t("حسب العلاقة", "Relationship default"), "واضحة ومحترمة، بدون افتراض قرب" to t("واضحة", "Clear"), "ودّية ودافئة بدون مبالغة" to t("ودّية", "Warm"), "مهنية ومختصرة" to t("مهنية", "Professional")).forEach { (id, label) ->
                                            FilterChip(selected = person.tone == id, onClick = { vm.updatePreferences(tone = id) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = reviewedMode, onCheckedChange = { vm.cancelGeneration(); reviewedMode = it })
                                    Text(t("أضف مراجعة آلية للمعنى", "Add an automated meaning review"), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (reviewedMode) Text(t("للعربية والإنجليزية فقط. حتى ٤ استدعاءات للنموذج وحصة إضافية. نرسل الاسم والعلاقة والسياق المكتوب واللغة والنبرة فقط، لا الملاحظات أو سجل الرسائل. قد تخطئ المراجعة.", "Arabic and English only. Up to 4 model calls and additional quota. Only the name, relationship, typed context, language and tone are sent, not saved notes or history. Review can be wrong."), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { editBrief = false; vm.generate("manual", intentId = selectedIntent, context = contextText.trim(), reviewed = reviewedMode) },
                                enabled = !state.loading && current != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                                Text(t("جهّز صياغتين", "Create two drafts"), style = MaterialTheme.typography.titleSmall)
                            }
                            Text(t("لا إرسال تلقائي. راجع الكلمات قبل مشاركتها.", "Nothing sends automatically. Review before sharing."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (contextText.isBlank() && state.items.isEmpty()) {
                        Text(t("أو ابدأ بفكرة بسيطة", "Or start with a simple intention"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("check_in", "thanks", "follow_up").forEach { id ->
                                TextButton(onClick = { vm.cancelGeneration(); selectedIntent = id; showIntent = id != "check_in" }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(Intents.byId(id)?.let { Intents.labelOf(it) } ?: id)
                                }
                            }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("مسودتك إلى ", "Your drafts for ") + (current?.name ?: ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(t("قارن وعدّل على مهل. المشاركة باختيارك.", "Take your time to compare and edit. Sharing is your choice."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editBrief = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("تعديل الفكرة", "Edit brief")) }
                }
            }
            state.reviewNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            if (showCal) {
                AlertDialog(
                    onDismissRequest = { showCal = false },
                    title = { Text(t("من أجندتك النهاردة 📅", "From your calendar today 📅")) },
                    text = {
                        if (calEvents.isEmpty()) {
                            Text(t("مفيش أحداث النهاردة في تقويمك.", "No events on your calendar today."))
                        } else {
                            Column {
                                calEvents.take(10).forEach { title ->
                                    TextButton(onClick = {
                                        showCal = false
                                        vm.generate("occasion", Occasion("cal", title))
                                    }) { Text("• $title") }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCal = false }) { Text(t("إغلاق", "Close")) }
                    },
                )
            }

            if (state.loading) {
                DraftProgress(state.reviewStage, motionAllowed) {
                    vm.cancelGeneration()
                    scope.launch { snackbar.showSnackbar(t("توقفت الصياغة. يمكنك متابعة تعديل فكرتك.", "Stopped. You can keep editing your idea.")) }
                }
            }

            state.occasionLabel?.let {
                Text(t("💝 مناسبة: ", "💝 Occasion: ") + it, style = MaterialTheme.typography.titleMedium)
            }

            if (state.items.isNotEmpty()) {
                Text(if (state.offline) t("قوالب محلية · ليست نتيجة AI", "Local templates · not AI output") else t("صياغة Groq · راجعها قبل المشاركة", "Groq drafts · review before sharing"),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (state.offline) state.info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            val isGroup = current?.relation?.startsWith("group") == true
            val draftContent: @Composable (Int, Suggestion, Modifier) -> Unit = { idx, item, cardModifier ->
                SuggestionCard(
                    modifier = cardModifier,
                    motionAllowed = motionAllowed,
                    index = idx,
                    item = item,
                    enabled = !state.loading,
                    onEdit = { vm.updateDraft(idx, it) },
                    onConfirmContact = { vm.confirmContact(idx) },
                    sendLabel = sendLabelFor(current),
                    onChoose = { vm.choose(idx) },
                    onCopy = { clipboard.setText(AnnotatedString(item.text)) },
                    onShare = { Share.text(context, item.text) },
                    onWhatsApp = {
                        // مجموعة أو من غير رقم: نفتح منتقي واتساب. شخص بيه رقم: نفتح شاته مباشرة.
                        if (isGroup) WhatsApp.chooser(context, item.text)
                        else WhatsApp.send(context, current?.number.orEmpty(), item.text)
                    },
                    onGroup = { WhatsApp.chooser(context, item.text) },
                    onRefine = { style -> vm.refine(idx, style) },
                    onRemind = {
                        val who = current?.let { it.name.ifBlank { Relations.labelOf(it.relation) } } ?: t("حد بتحبه", "someone you love")
                        SendReminder.schedule(context, who, item.text, "tomorrow_morning")
                        Toast.makeText(context, t("هفكّرك بكرة الصبح ⏰", "I'll remind you tomorrow morning ⏰"), Toast.LENGTH_SHORT).show()
                    },
                )
            }

            if (wide && state.items.size == 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    state.items.forEachIndexed { i, item -> WisalReveal(motionAllowed, Modifier.weight(1f)) { draftContent(i, item, Modifier.fillMaxWidth()) } }
                }
            } else {
                state.items.forEachIndexed { i, item -> WisalReveal(motionAllowed, Modifier.fillMaxWidth()) { draftContent(i, item, Modifier.fillMaxWidth()) } }
            }

            if (state.items.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.regenerate() }, modifier = Modifier.weight(1f)) {
                        Text(t("صياغتان جديدتان", "Try two more"))
                    }
                    OutlinedButton(onClick = { vm.ignore() }, modifier = Modifier.weight(1f)) {
                        Text(t("مسودة جديدة", "Start again"))
                    }
                }
            }

            TextButton(onClick = { moreTools = !moreTools }, modifier = Modifier.fillMaxWidth()) {
                Text(if (moreTools) t("إخفاء الأدوات الإضافية", "Hide more tools") else t("أدوات إضافية وتذكيرات", "More tools & reminders"))
            }
            if (moreTools) {
                PersonOccasionChips(current) { label -> vm.generate("occasion", Occasion("person", label), context = contextText.trim()) }
                StreakCard(streak)
                next?.let { na -> NextActionCard(na) { vm.writeToNext(na) } }
                OutlinedButton(onClick = onOpenReply, modifier = Modifier.fillMaxWidth()) { Text(t("رد على رسالة", "Reply to a message")) }
                OutlinedButton(onClick = onOpenPolish, modifier = Modifier.fillMaxWidth()) { Text(t("حسّن مسودتي", "Polish my draft")) }
                OutlinedButton(onClick = { openCalendar() }, modifier = Modifier.fillMaxWidth()) { Text(t("مناسبة من التقويم", "Occasion from calendar")) }
                OutlinedButton(onClick = onOpenBroadcast, modifier = Modifier.fillMaxWidth()) { Text(t("أدوات المجموعات", "Group tools")) }
                OutlinedButton(onClick = { vm.requestOccasion() }, modifier = Modifier.fillMaxWidth()) { Text(t("اكتب لمناسبة", "Write for an occasion")) }
                UpcomingOccasions(people = people,
                    onWrite = { rid, label -> vm.selectRecipient(rid); vm.generate("occasion", Occasion("person", label)) },
                    onIdeas = { rid, label -> vm.selectRecipient(rid); vm.loadGiftIdeas(label) })
            }

            Spacer(Modifier.height(24.dp))
        }

        if (showPeople) AlertDialog(onDismissRequest = { showPeople = false },
            title = { Text(t("اختر الشخص", "Choose a person")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                people.recipients.forEach { person ->
                    OutlinedButton(onClick = { vm.selectRecipient(person.id); showPeople = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(person.name.ifBlank { Relations.labelOf(person.relation) }) }
                }
                TextButton(onClick = { showPeople = false; onOpenPeople() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("إضافة أو إدارة الأشخاص", "Add or manage people")) }
            } }, confirmButton = { TextButton(onClick = { showPeople = false }) { Text(t("إغلاق", "Close")) } })
        if (showIntent) AlertDialog(onDismissRequest = { showIntent = false },
            title = { Text(t("هدف الرسالة", "Message purpose")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Intents.ALL.forEach { intent ->
                    TextButton(onClick = { vm.cancelGeneration(); selectedIntent = intent.id; showIntent = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(Intents.labelOf(intent)) }
                }
            } }, confirmButton = { TextButton(onClick = { showIntent = false }) { Text(t("إغلاق", "Close")) } })
        // نافذة أفكار المناسبة (هدية/كارت/ورد/لمسة).
        if (ideas.open) {
            AlertDialog(
                onDismissRequest = { vm.closeIdeas() },
                title = { Text(t("🎁 أفكار لـ", "🎁 Ideas for ") + ideas.title) },
                text = {
                    if (ideas.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(22.dp))
                            Text(t("  بجهّزلك أفكار...", "  Getting ideas ready..."))
                        }
                    } else {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(ideas.text)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                t("دي أفكار وفئة سعرية تقريبية - مش أسعار حقيقية لحظية.", "These are ideas with rough price ranges — not live prices."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.closeIdeas() }) { Text(t("تمام", "Done")) } },
            )
        }
        }
    }
}

// المناسبات الجاية عبر كل الأشخاص (خلال ~45 يوم)، الأقرب الأول.
@Composable
private fun UpcomingOccasions(
    people: PeopleState,
    onWrite: (String, String) -> Unit,
    onIdeas: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { com.wifeassistant.data.Settings(context) }
    var muted by remember { mutableStateOf(settings.mutedOccasions.toSet()) }

    data class Up(val rid: String, val name: String, val label: String, val days: Int)
    val upcoming = people.recipients.flatMap { r ->
        r.occasions.mapNotNull { o ->
            com.wifeassistant.data.DateUtil.daysUntilMMDD(o.date)?.let { d ->
                if (d <= 45) Up(r.id, r.name.ifBlank { Relations.labelOf(r.relation) }, o.label, d) else null
            }
        }
    }.sortedBy { it.days }.take(4)

    if (upcoming.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("🎀 مناسبات جايّة", "🎀 Upcoming occasions"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            upcoming.forEach { u ->
                val whenTxt = when (u.days) {
                    0 -> t("النهاردة", "today")
                    1 -> t("بكرة", "tomorrow")
                    else -> t("بعد ${u.days} يوم", "in ${u.days} days")
                }
                val key = u.rid + "|" + u.label
                val isMuted = muted.contains(key)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        t("${u.label} لـ${u.name} · $whenTxt", "${u.label} for ${u.name} · $whenTxt") + if (isMuted) "  🔕" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(onClick = { onWrite(u.rid, u.label) }, label = { Text(t("✍️ اكتب", "✍️ Write")) })
                        AssistChip(onClick = { onIdeas(u.rid, u.label) }, label = { Text(t("🎁 أفكار", "🎁 Ideas")) })
                        AssistChip(
                            onClick = {
                                muted = if (isMuted) muted - key else muted + key
                                settings.mutedOccasions = muted.toList()
                            },
                            label = { Text(if (isMuted) t("🔔 تفعيل التنبيه", "🔔 Unmute") else t("🔕 كتم", "🔕 Mute")) },
                        )
                    }
                }
            }
        }
    }
}

// مناسبات الشخص المختار كأزرار سريعة (لو ليه مناسبات مسجّلة).
@Composable
private fun PersonOccasionChips(current: Recipient?, onPick: (String) -> Unit) {
    val occ = current?.occasions ?: emptyList()
    if (occ.isEmpty()) return
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        occ.forEach { o ->
            AssistChip(onClick = { onPick(o.label) }, label = { Text("🎉 ${o.label}") })
        }
    }
}

// 🔥 «سلسلة الدفء»: كام يوم متتالي وانت بتوصّل حب — تحفيز لطيف يحافظ على العادة.
// بتظهر بس لما فيه سلسلة فعلاً (مفيش تأنيب لو مفيش).
@Composable
private fun StreakCard(streak: Int) {
    if (streak < 1) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🔥", style = MaterialTheme.typography.headlineMedium)
            Column {
                Text(
                    if (streak == 1) t("يوم دفء متواصل", "1 warm day in a row") else t("$streak يوم دفء متواصل", "$streak warm days in a row"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    com.wifeassistant.data.Streak.message(streak),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

// 💡 اللفتة الجاية: البرنامج بيرشّح مين تطمّن عليه (مناسبة قريّبة أو بقالك فترة) بضغطة كتابة.
@Composable
private fun NextActionCard(a: NextAction, onWrite: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    t("💡 اللفتة الجاية", "💡 Next gesture"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    a.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    a.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            FilledTonalButton(onClick = onWrite) { Text(t("✍️ اكتبله", "✍️ Write to them")) }
        }
    }
}

// نص زر الواتساب حسب الشخص المختار: "ابعت لأمي"، "ابعت لسوسو"، أو "ابعت للعيلة".
private fun sendLabelFor(current: Recipient?): String {
    if (current == null) return t("📲 إرسال واتساب", "📲 Send on WhatsApp")
    val rel = Relations.byId(current.relation)
    val who = current.name.ifBlank { rel.label }
    return if (current.relation.startsWith("group")) t("📣 ابعت لـ$who", "📣 Send to $who") else t("📲 ابعت لـ$who", "📲 Send to $who")
}

@Composable
internal fun SuggestionCard(
    modifier: Modifier = Modifier,
    motionAllowed: Boolean = false,
    index: Int,
    item: Suggestion,
    enabled: Boolean = true,
    onEdit: (String) -> Unit,
    onConfirmContact: () -> Unit,
    sendLabel: String,
    onChoose: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onWhatsApp: () -> Unit,
    onGroup: () -> Unit,
    onRefine: (String) -> Unit,
    onRemind: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("صياغة ${index + 1}", "Draft ${index + 1}"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = item.text, onValueChange = onEdit, enabled = enabled,
                label = { Text(t("راجع وعدّل", "Review and edit")) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp), shape = RoundedCornerShape(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyDraftButton(item.text, enabled, motionAllowed, Modifier.weight(1f), onCopy)
                Button(onClick = onShare, enabled = enabled && item.text.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(t("مشاركة", "Share")) }
            }
            TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (more) t("إخفاء الخيارات", "Hide options") else t("خيارات المسودة", "Draft options")) }
            WisalDisclosure(more, motionAllowed) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("shorter" to t("أقصر", "Shorter"), "simpler" to t("أوضح", "Clearer"), "formal" to t("أكثر رسمية", "More formal")).forEach { (id, label) ->
                        AssistChip(onClick = { onRefine(id) }, enabled = enabled, label = { Text(label) })
                    }
                }
                OutlinedButton(onClick = onWhatsApp, enabled = enabled && item.text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(t("افتح في واتساب للمراجعة", "Review in WhatsApp")) }
                TextButton(onClick = onChoose, enabled = enabled && item.text.isNotBlank()) { Text(t("احفظ كأسلوب أفضّله", "Save as my preferred style")) }
                TextButton(onClick = onRemind, enabled = enabled && item.text.isNotBlank()) { Text(t("ذكّرني غداً", "Remind me tomorrow")) }
                TextButton(onClick = onConfirmContact, enabled = enabled && item.text.isNotBlank()) { Text(t("أؤكد أنني تواصلت بالفعل", "I confirm I reached out")) }
            }
        }
    }
}
