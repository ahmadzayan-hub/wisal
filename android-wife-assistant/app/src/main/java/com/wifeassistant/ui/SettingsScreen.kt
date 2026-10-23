package com.wifeassistant.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wifeassistant.data.AppConstants
import com.wifeassistant.data.BackupManager
import com.wifeassistant.data.Settings
import com.wifeassistant.data.t
import com.wifeassistant.ui.theme.GradientButton
import com.wifeassistant.util.Share
import com.wifeassistant.work.Scheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onThemeChanged: () -> Unit = {}, onOpenStyleTransparency: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var dynamicColor by remember { mutableStateOf(settings.dynamicColor) }
    var appLanguage by remember { mutableStateOf(settings.appLanguage) }

    var groqKey by remember { mutableStateOf(settings.groqKey) }
    var myName by remember { mutableStateOf(settings.myName) }
    var humor by remember { mutableStateOf(settings.humor) }
    var emoji by remember { mutableStateOf(settings.emoji) }
    var messageLength by remember { mutableStateOf(settings.messageLength) }
    var groqEnabled by remember { mutableStateOf(settings.groqEnabled) }
    var backgroundSuggestions by remember { mutableStateOf(settings.backgroundSuggestions) }
    var model by remember { mutableStateOf(settings.model) }
    var morning by remember { mutableStateOf(settings.morningTime) }
    var evening by remember { mutableStateOf(settings.eveningTime) }
    var reminders by remember { mutableStateOf(settings.reminders) }
    var reminderDays by remember { mutableStateOf(settings.reminderDays.toString()) }
    var apiEndpoint by remember { mutableStateOf(settings.businessApiEndpoint) }
    var apiKey by remember { mutableStateOf(settings.businessApiKey) }
    var showRestore by remember { mutableStateOf(false) }
    var restoreText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("الإعدادات", "Settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("رجوع", "Back"))
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(t("اللغة والمظهر", "Language & Appearance"))
            Text(t("لغة التطبيق", "App language"), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // اللغات من سجل Locales بأسمائها الأصلية — من غير أعلام دول.
                com.wifeassistant.data.Locales.registered.forEach { loc ->
                    FilterChip(
                        selected = appLanguage == loc.code,
                        onClick = {
                            appLanguage = loc.code
                            settings.appLanguage = loc.code
                            onThemeChanged() // بيعيد تكوين الشجرة فتتبدّل اللغة والاتجاه فورًا
                        },
                        label = { Text(if (loc.machineDrafted) "${loc.nativeName} (beta)" else loc.nativeName) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(t("الوضع", "Theme"), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "system" to t("تلقائي", "Auto"),
                    "light" to t("فاتح", "Light"),
                    "dark" to t("غامق", "Dark"),
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = themeMode == id,
                        onClick = {
                            themeMode = id
                            settings.themeMode = id
                            onThemeChanged() // تطبيق فوري
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("ألوان Material You (أندرويد 12+)", "Material You colors (Android 12+)"), modifier = Modifier.weight(1f))
                Switch(
                    checked = dynamicColor,
                    onCheckedChange = {
                        dynamicColor = it
                        settings.dynamicColor = it
                        onThemeChanged() // تطبيق فوري
                    },
                )
            }

            Section(t("الأساسيات", "Essentials"))
            OutlinedTextField(
                value = groqKey,
                onValueChange = { groqKey = it },
                label = { Text(t("مفتاح Groq (console.groq.com/keys)", "Groq key (console.groq.com/keys)")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Section(t("عنك", "About you"))
            OutlinedTextField(
                value = myName,
                onValueChange = { myName = it },
                label = { Text(t("اسمك", "Your name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                t(
                    "الأشخاص وأرقامهم بتتظبط من شاشة \"الأشخاص\" في الصفحة الرئيسية.",
                    "People and their numbers are managed from the \"People\" screen.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section(t("نبرة الرسالة", "Message tone"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("لمسة دُعابة خفيفة", "A light touch of humor"), modifier = Modifier.weight(1f))
                Switch(checked = humor, onCheckedChange = { humor = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("إيموجي معبّر في الرسالة", "Expressive emoji in messages"), modifier = Modifier.weight(1f))
                Switch(checked = emoji, onCheckedChange = { emoji = it })
            }
            Text(t("طول الرسالة", "Message length"), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppConstants.LENGTHS.forEach { (id, label) ->
                    FilterChip(
                        selected = messageLength == id,
                        onClick = { messageLength = id },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section(t("الذكاء الاصطناعي · Groq", "AI provider · Groq"))
            Text(t("القوالب المحلية متاحة بلا حساب أو شبكة. Groq خدمة خارجية بحصص وشروط حسب حسابك، وليست مجانية بلا حدود.", "Local templates need no account or network. Groq is an external service with account-specific limits and terms, not unlimited free access."))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("راجعت خطة حسابي المجانية وحدودها وأوافق على إرسال سياق الرسالة إلى Groq", "I checked my organization's free plan and limits, and allow message context to be sent to Groq"), modifier = Modifier.weight(1f))
                Switch(checked = groqEnabled, onCheckedChange = { groqEnabled = it })
            }
            Text(t("الطلب يشمل الاسم والعلاقة والملاحظات وأمثلة أسلوبك. تجنّب البيانات الحساسة. لا يتم التحويل تلقائياً لمزوّد أو خطة مدفوعة.", "Requests include the name, relationship, notes and style examples. Avoid sensitive data. Wisal never switches to another provider or paid plan automatically."), style = MaterialTheme.typography.bodySmall)
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(onClick = { uriHandler.openUri(AppConstants.MODEL_LIMITS_SOURCE) }) { Text(t("راجع حدود Groq", "Check Groq limits")) }
            if (AppConstants.MODELS.none { it.first == model }) {
                Text(t("النموذج المحفوظ غير متاح في القائمة الحالية: $model. اختر بديلاً بنفسك؛ لم نغيّر اختيارك تلقائياً.", "Your saved model is not in the current catalog: $model. Choose a replacement; your selection was not changed automatically."), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppConstants.MODELS.forEach { (id, label) ->
                    FilterChip(
                        selected = model == id,
                        onClick = { model = id },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(t("تمت مراجعة قائمة النماذج: ", "Model catalog reviewed: ") + AppConstants.MODEL_CATALOG_REVIEWED, style = MaterialTheme.typography.bodySmall)

            Section(t("الخصوصية والتعلّم", "Privacy & learning"))
            OutlinedButton(
                onClick = onOpenStyleTransparency,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t("🧠 ما الذي تعلّمه وصال؟", "🧠 What has Wisal learned?")) }
            Text(
                t(
                    "شوف القواعد اللي اتعلمها من أسلوبك، وعطّل أو عدّل أو امسح أي واحدة. كله على جهازك.",
                    "See the rules learned from your style, and turn off, edit, or delete any of them. All on your device.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section(t("مواعيد الاقتراحات", "Suggestion times"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("توليد في الخلفية عبر Groq (يستهلك من الحصة)", "Background Groq suggestions (uses quota)"), modifier = Modifier.weight(1f))
                Switch(checked = backgroundSuggestions, onCheckedChange = { backgroundSuggestions = it })
            }
            Text(t("مواعيد الخلفية الحالية بتوقيت دبي. التذكير ليس إرسالاً.", "Background times currently use Dubai time. A reminder never sends a message."), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = morning,
                    onValueChange = { morning = it },
                    label = { Text(t("الصباح HH:mm", "Morning HH:mm")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = evening,
                    onValueChange = { evening = it },
                    label = { Text(t("المساء HH:mm", "Evening HH:mm")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Section(t("تذكيرات التواصل", "Connection reminders"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("ذكّرني لو بقالي فترة ما كلّمت حد", "Remind me when I haven't reached out in a while"), modifier = Modifier.weight(1f))
                Switch(checked = reminders, onCheckedChange = { reminders = it })
            }
            if (reminders) {
                OutlinedTextField(
                    value = reminderDays,
                    onValueChange = { reminderDays = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text(t("بعد كام يوم من غير تواصل", "After how many quiet days")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    t(
                        "هنبعتلك إشعار لطيف يفكّرك، والاختيار ليك تبعت أو لأ. مفيش أي حاجة بتتبعت تلقائي.",
                        "You'll get a gentle notification — sending is always your choice. Nothing is ever sent automatically.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("WhatsApp Business API")
            Text(
                t(
                    "للإرسال الآلي المشروع للعملاء عبر Cloud API من Meta. سيبها فاضية لو مش محتاجها — " +
                        "هيفضل الإرسال بفتح واتساب بضغطة يدوية. خطوات النشر في wisal-cloud-api/README.",
                    "For compliant automated customer messaging via Meta's Cloud API. Leave empty if unused — " +
                        "sending stays manual via WhatsApp. Setup steps in wisal-cloud-api/README.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiEndpoint,
                onValueChange = { apiEndpoint = it },
                label = { Text("Endpoint (…/api/send)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("APP_API_KEY") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Section(t("نسخة احتياطية واستعادة", "Backup & restore"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { Share.text(context, BackupManager.export(context)) },
                    modifier = Modifier.weight(1f),
                ) { Text(t("📤 نسخة احتياطية", "📤 Backup")) }
                OutlinedButton(
                    onClick = { restoreText = ""; showRestore = true },
                    modifier = Modifier.weight(1f),
                ) { Text(t("📥 استعادة", "📥 Restore")) }
            }
            Text(
                t(
                    "النسخة مفيهاش مفتاح Groq (سر). احفظها في مكان أمين واستعيدها لأي جهاز.",
                    "Backups never include your Groq key. Keep it somewhere safe and restore on any device.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GradientButton(
                onClick = {
                    if (groqKey.trim() != settings.groqKey) {
                        try { settings.groqKey = groqKey.trim() } catch (e: IllegalStateException) {
                            Toast.makeText(context, t("تعذّر حفظ المفتاح مشفّراً. لم نحفظه كنص مكشوف.", "Encrypted key storage failed. The key was not saved as plaintext."), Toast.LENGTH_LONG).show()
                            return@GradientButton
                        }
                    }
                    settings.groqEnabled = groqEnabled
                    settings.backgroundSuggestions = backgroundSuggestions
                    settings.myName = myName.trim()
                    settings.humor = humor
                    settings.emoji = emoji
                    settings.messageLength = messageLength
                    settings.model = model
                    settings.morningTime = morning.trim()
                    settings.eveningTime = evening.trim()
                    settings.reminders = reminders
                    reminderDays.toIntOrNull()?.let { settings.reminderDays = it }
                    settings.businessApiEndpoint = apiEndpoint
                    settings.businessApiKey = apiKey
                    Scheduler.scheduleDaily(context)
                    Toast.makeText(context, t("اتحفظ ✅", "Saved ✅"), Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(t("💾 حفظ", "💾 Save"), fontWeight = FontWeight.Bold) }

            if (showRestore) {
                AlertDialog(
                    onDismissRequest = { showRestore = false },
                    title = { Text(t("استعادة نسخة", "Restore backup")) },
                    text = {
                        OutlinedTextField(
                            value = restoreText,
                            onValueChange = { restoreText = it },
                            label = { Text(t("الصق نص النسخة الاحتياطية هنا", "Paste your backup text here")) },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val ok = BackupManager.import(context, restoreText)
                            showRestore = false
                            Toast.makeText(
                                context,
                                if (ok) t("اتستعادت ✅ - أعد فتح التطبيق", "Restored ✅ — reopen the app")
                                else t("النص مش صحيح", "That text isn't a valid backup"),
                                Toast.LENGTH_LONG,
                            ).show()
                            if (ok) onBack()
                        }) { Text(t("استعادة", "Restore")) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestore = false }) { Text(t("إلغاء", "Cancel")) }
                    },
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary, // ذهبي الهوية
    )
}
