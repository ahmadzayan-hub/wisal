package com.wifeassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wifeassistant.data.AppConstants
import com.wifeassistant.data.GroqClient
import com.wifeassistant.data.PersonOccasion
import com.wifeassistant.data.PersonaAnalyzer
import com.wifeassistant.data.Recipient
import com.wifeassistant.data.Relations
import com.wifeassistant.data.Settings
import com.wifeassistant.data.t
import com.wifeassistant.ui.theme.GradientButton
import com.wifeassistant.util.Avatars
import com.wifeassistant.util.ContactsReader
import com.wifeassistant.util.Share
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    val scope = rememberCoroutineScope()
    val analyzer = remember { PersonaAnalyzer(GroqClient(settings)) }

    var people by remember { mutableStateOf(settings.recipients) }
    var name by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf(Relations.ALL.first().id) }
    var number by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var occText by remember { mutableStateOf("") }
    var social by remember { mutableStateOf("") }
    var pasteInfo by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var tone by remember { mutableStateOf("") }
    var dialect by remember { mutableStateOf("egyptian") }
    var language by remember { mutableStateOf("auto") }
    var photoPath by remember { mutableStateOf("") }
    // معرّف ثابت للفورمة عشان نربط الصورة بالشخص حتى لو لسه ما اتحفظش.
    var workingId by remember { mutableStateOf(UUID.randomUUID().toString()) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            Avatars.saveFromUri(context, workingId, uri)?.let { photoPath = it }
        }
    }

    // ---- استيراد أعياد الميلاد من جهات الاتصال ----
    var contacts by remember { mutableStateOf<List<ContactsReader.ContactBirthday>>(emptyList()) }
    var showContacts by remember { mutableStateOf(false) }
    fun loadContacts() {
        contacts = runCatching { ContactsReader.withBirthdays(context) }.getOrDefault(emptyList())
        showContacts = true
        if (contacts.isEmpty()) {
            Toast.makeText(context, t("مفيش جهات اتصال بأعياد ميلاد مسجّلة", "No contacts with saved birthdays"), Toast.LENGTH_LONG).show()
        }
    }
    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadContacts()
        else Toast.makeText(context, t("محتاج إذن جهات الاتصال عشان أستورد المواعيد", "I need contacts permission to import dates"), Toast.LENGTH_LONG).show()
    }
    fun openContacts() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) loadContacts() else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    fun clearForm() {
        name = ""; number = ""; notes = ""; occText = ""; social = ""; pasteInfo = ""
        tone = ""; dialect = "egyptian"; language = "auto"; photoPath = ""
        workingId = UUID.randomUUID().toString()
        relation = Relations.ALL.first().id; editingId = null
    }

    // تحليل المعلومات الملصوقة بالـ LLM وملء الحقول (المستخدم يراجع قبل الحفظ).
    fun analyze() {
        if (social.isBlank() && pasteInfo.isBlank()) {
            Toast.makeText(context, t("حط رابط أو الصق معلومات عنه الأول", "Add a link or paste some info about them first"), Toast.LENGTH_SHORT).show()
            return
        }
        analyzing = true
        scope.launch {
            try {
                val res = analyzer.analyze(Relations.labelOf(relation), name, social, pasteInfo)
                if (name.isBlank() && res.nickname.isNotBlank()) name = res.nickname
                val extra = listOfNotNull(
                    res.notes.ifBlank { null },
                    res.interests.ifBlank { null }?.let { "بيحب: $it" },
                    res.toneHint.ifBlank { null }?.let { "نبرة مناسبة: $it" },
                ).joinToString("\n")
                notes = listOf(notes.trim(), extra).filter { it.isNotBlank() }.joinToString("\n")
                if (res.occasions.isNotEmpty()) {
                    val lines = res.occasions.joinToString("\n") { "${it.label}=${it.date}" }
                    occText = listOf(occText.trim(), lines).filter { it.isNotBlank() }.joinToString("\n")
                }
                Toast.makeText(context, t("تم التحليل ✅ راجع وعدّل قبل الحفظ", "Analyzed ✅ review and edit before saving"), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, t("التحليل مش متاح دلوقتي (محتاج نت + مفتاح Groq)", "Analysis unavailable right now (needs internet + Groq key)"), Toast.LENGTH_LONG).show()
            }
            analyzing = false
        }
    }

    fun save() {
        if (name.isBlank()) {
            Toast.makeText(context, t("اكتب الاسم الأول", "Write the name first"), Toast.LENGTH_SHORT).show()
            return
        }
        val occs = parseOccasions(occText)
        val list = people.toMutableList()
        val id = editingId
        if (id == null) {
            list.add(
                Recipient(
                    id = workingId, name = name.trim(), relation = relation,
                    number = number.trim(), notes = notes.trim(), occasions = occs, social = social.trim(),
                    tone = tone.trim(), dialect = dialect, language = language, photoPath = photoPath,
                )
            )
        } else {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) list[i] = list[i].copy(
                name = name.trim(), relation = relation, number = number.trim(),
                notes = notes.trim(), occasions = occs, social = social.trim(),
                tone = tone.trim(), dialect = dialect, language = language, photoPath = photoPath,
            )
        }
        settings.recipients = list
        if (settings.selectedRecipientId.isBlank()) settings.selectedRecipientId = list.first().id
        people = list
        clearForm()
        Toast.makeText(context, t("اتحفظ ✅", "Saved ✅"), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("الأشخاص 👨‍👩‍👧‍👦", "People 👨‍👩‍👧‍👦")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("رجوع", "Back"))
                    }
                },
                actions = {
                    // دعوة لوصال: بتولّد رابط إقران منتهي الصلاحية (48 ساعة) وتفتح
                    // شيت المشاركة — الإرسال بإيدك دايمًا، ومفيش بيانات غير اسمك ومفتاحك.
                    TextButton(onClick = {
                        val me = com.wifeassistant.data.crypto.DeviceIdentityStore.getOrCreate(context)
                        val myName = Settings(context).myName.ifBlank { t("صديقك على وصال", "Your friend on Wisal") }
                        val inv = com.wifeassistant.data.pairing.Pairing.create(
                            inviterDeviceId = me.deviceId,
                            inviterPublicKeyB64 = me.publicKeyB64,
                            inviterName = myName,
                            nowEpochSec = System.currentTimeMillis() / 1000,
                        )
                        val link = com.wifeassistant.data.pairing.Pairing.payload(inv)
                        Share.text(
                            context,
                            t(
                                "$myName بيدعوك لوصال 💗 — افتح الرابط من موبايلك بعد تثبيت التطبيق (صالح 48 ساعة):\n$link",
                                "$myName invites you to Wisal 💗 — open this link on your phone after installing the app (valid 48h):\n$link",
                            ),
                        )
                    }) { Text(t("🔗 دعوة", "🔗 Invite")) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (editingId == null) t("إضافة شخص", "Add a person") else t("تعديل شخص", "Edit person"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(t("الاسم أو الدلع", "Name or nickname")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(t("العلاقة", "Relationship"), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Relations.ALL.forEach { rel ->
                    FilterChip(
                        selected = relation == rel.id,
                        onClick = { relation = rel.id },
                        label = { Text("${rel.emoji} ${Relations.labelOf(rel.id)}") },
                    )
                }
            }
            // معاينة حيّة: نبرة الرسالة بتتغيّر حسب العلاقة المختارة.
            Relations.byId(relation).let { rel ->
                Text(
                    t("نبرة العلاقة الافتراضية لـ${rel.label}: ${rel.tone}", "Default tone for ${rel.label}: ${rel.tone}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // صورة الشخص (من معرض الموبايل - مش سحب من السوشيال).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (photoPath.isNotBlank()) {
                    val bmp = remember(photoPath) { Avatars.load(photoPath) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = t("صورة الشخص", "Person photo"),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape),
                        )
                    }
                }
                OutlinedButton(onClick = { photoPicker.launch("image/*") }) {
                    Text(if (photoPath.isBlank()) t("📷 أضف صورة", "📷 Add photo") else t("📷 غيّر الصورة", "📷 Change photo"))
                }
            }

            // لهجة الرسالة الخاصة بيه.
            Text(t("لهجة الرسالة", "Message dialect"), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppConstants.DIALECTS.forEach { (id, label) ->
                    FilterChip(
                        selected = dialect == id,
                        onClick = { dialect = id },
                        label = { Text(label) },
                    )
                }
            }

            // لغة الرسالة حسب لغته الأولى: تلقائي (نكشفها) / عربي / إنجليزي.
            Text(t("لغة الرسالة المفضّلة", "Preferred message language"), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("auto" to t("🌐 تلقائي", "🌐 Auto"), "ar" to "العربية", "en" to "English").forEach { (id, label) ->
                    FilterChip(
                        selected = language == id,
                        onClick = { language = id },
                        label = { Text(label) },
                    )
                }
            }

            // نبرة خاصة بيه (تغلب على الافتراضية لو اتكتبت).
            OutlinedTextField(
                value = tone,
                onValueChange = { tone = it },
                label = { Text(t("نبرة خاصة بيه (اختياري)", "Custom tone for them (optional)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text(t("رقم واتساب (اختياري، دولي بأرقام)", "WhatsApp number (optional, international digits)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(t("حاجات عنه (بيحب إيه، ذكريات، نكت بينكم)", "Things about them (likes, memories, inside jokes)")) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = occText,
                onValueChange = { occText = it },
                label = { Text(t("مناسباته (سطر لكل واحدة: عيد ميلاد=08-24)", "Their occasions (one per line: birthday=08-24)")) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- تحليل الشخصية بالذكاء (من معلومات انت بتجيبها، مش سحب تلقائي) ----
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                t("تحليل بالذكاء 🧠", "AI analysis 🧠"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = social,
                onValueChange = { social = it },
                label = { Text(t("روابط تواصله (سطر لكل رابط - مرجع سريع)", "Their social links (one per line — quick reference)")) },
                minLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pasteInfo,
                onValueChange = { pasteInfo = it },
                label = { Text(t("الصق معلومات عنه (البايو/بوست/رسايل/اهتماماته)", "Paste info about them (bio/posts/messages/interests)")) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                t("بنحلّل اللي انت بتلصقه بس. الذكاء يكمّل الملف والمناسبات، وانت تراجع قبل الحفظ.", "We only analyze what you paste. AI fills in the profile and occasions, and you review before saving."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { analyze() },
                enabled = !analyzing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (analyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(t("  بحلّل...", "  Analyzing..."))
                } else {
                    Text(t("🧠 حلّل وكمّل الملف", "🧠 Analyze & complete profile"))
                }
            }
            OutlinedButton(onClick = { openContacts() }, modifier = Modifier.fillMaxWidth()) {
                Text(t("📇 استورد عيد ميلاد من جهات الاتصال", "📇 Import a birthday from contacts"))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton(onClick = { save() }, modifier = Modifier.weight(1f)) {
                    Text(if (editingId == null) t("➕ إضافة", "➕ Add") else t("💾 حفظ التعديل", "💾 Save changes"))
                }
                if (editingId != null) {
                    OutlinedButton(onClick = { clearForm() }) { Text(t("إلغاء", "Cancel")) }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                t("أشخاصك", "Your people"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (people.isEmpty()) {
                Text(
                    t("لسه مفيش حد. ضيف أول شخص عشان تبدأ تكتب له.", "No one yet. Add your first person to start writing."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            people.forEach { p ->
                val selected = p.id == settings.selectedRecipientId
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (p.photoPath.isNotBlank()) {
                                val bmp = remember(p.photoPath) { Avatars.load(p.photoPath) }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(40.dp).clip(CircleShape),
                                    )
                                }
                            }
                            Text(
                                (if (selected) "✅ " else "") + Relations.emojiOf(p.relation) + " " +
                                    (p.name.ifBlank { t("(بدون اسم)", "(unnamed)") }) + " · " + Relations.labelOf(p.relation),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    settings.selectedRecipientId = p.id
                                    onBack()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(t("اختار", "Select")) }
                            OutlinedButton(onClick = {
                                name = p.name; relation = p.relation; number = p.number; notes = p.notes
                                occText = p.occasions.joinToString("\n") { "${it.label}=${it.date}" }
                                social = p.social; pasteInfo = ""
                                tone = p.tone; dialect = p.dialect; language = p.language; photoPath = p.photoPath
                                workingId = p.id; editingId = p.id
                            }) { Text(t("تعديل", "Edit")) }
                            TextButton(onClick = {
                                val list = people.toMutableList()
                                list.removeAll { it.id == p.id }
                                settings.recipients = list
                                if (settings.selectedRecipientId == p.id) {
                                    settings.selectedRecipientId = list.firstOrNull()?.id ?: ""
                                }
                                people = list
                            }) { Text(t("حذف", "Delete")) }
                        }
                    }
                }
            }

            // نافذة اختيار جهة اتصال بعيد ميلاد لملء الفورمة (المستخدم يراجع ويحفظ).
            if (showContacts && contacts.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { showContacts = false },
                    title = { Text(t("استورد عيد ميلاد 📇", "Import a birthday 📇")) },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            contacts.take(50).forEach { cb ->
                                TextButton(onClick = {
                                    if (name.isBlank()) name = cb.name
                                    if (number.isBlank() && cb.number.isNotBlank()) number = cb.number
                                    val line = "عيد ميلاد=${cb.mmdd}"
                                    occText = listOf(occText.trim(), line)
                                        .filter { it.isNotBlank() }.joinToString("\n")
                                    showContacts = false
                                    Toast.makeText(context, t("اتضاف - راجع واحفظ ✅", "Added — review and save ✅"), Toast.LENGTH_SHORT).show()
                                }) { Text("🎂 ${cb.name} · ${cb.mmdd}") }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showContacts = false }) { Text(t("إغلاق", "Close")) }
                    },
                )
            }
        }
    }
}

// تحويل نص "الاسم=MM-DD" (سطر لكل مناسبة) لقائمة مناسبات.
private fun parseOccasions(text: String): List<PersonOccasion> {
    val re = Regex("^\\d{2}-\\d{2}$")
    return text.split("\n").mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty()) return@mapNotNull null
        val idx = t.lastIndexOf('=')
        if (idx <= 0) return@mapNotNull null
        val label = t.substring(0, idx).trim()
        val date = t.substring(idx + 1).trim()
        if (label.isEmpty() || !re.matches(date)) null else PersonOccasion(label, date)
    }
}
