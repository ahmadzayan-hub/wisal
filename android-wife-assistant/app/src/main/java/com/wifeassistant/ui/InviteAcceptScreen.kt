package com.wifeassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wifeassistant.data.Settings
import com.wifeassistant.data.pairing.Pairing
import com.wifeassistant.data.pairing.PairingInvitation
import com.wifeassistant.data.pairing.PairingResult
import com.wifeassistant.data.t
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// شاشة قبول دعوة الإقران (wisal://pair): المدعو بيشوف مين بيدعوه ويقبل أو يرفض.
// القرار دايمًا بإيد المستخدم — مفيش قبول تلقائي، والدعوة المنتهية/المعادة بترفض.
@Composable
fun InviteAcceptScreen(
    invitation: PairingInvitation,
    onClose: () -> Unit,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    // الحالة الأولية: منتهية، مستخدمة قبل كده على الجهاز ده، أو معلّقة.
    var phase by remember(invitation) {
        mutableStateOf(
            runCatching {
                when {
                    invitation.id in settings.acceptedInvitationIds -> "already"
                    Pairing.accept(invitation, invitation.token, clock()) !is PairingResult.Ok -> "expired"
                    else -> "pending"
                }
            }.getOrDefault("storage_error")
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                t("دعوة لوصال 🔗", "Invitation to Wisal 🔗"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            when (phase) {
                "pending" -> {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                t(
                                    "«${invitation.inviterName}» بيدعوك تتواصلوا في وصال.",
                                    "“${invitation.inviterName}” invites you to connect on Wisal.",
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val expiry = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .format(Instant.ofEpochSecond(invitation.expiresAtEpochSec).atZone(ZoneId.systemDefault()))
                            Text(
                                t("الدعوة صالحة حتى $expiry.", "Valid until $expiry."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                t(
                                    "لو قبلت، بيتسجل على جهازك معرّف جهازه ومفتاحه العام واسمه — بس كده. مفيش رقم تليفون ولا جهات اتصال بتتبادل.",
                                    "If you accept, only their device id, public key, and name are stored on your device. No phone numbers or contacts are exchanged.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                // Time is read when clicked, not when the screen opened.
                                phase = runCatching {
                                    when (val result = settings.acceptInvitation(invitation, clock())) {
                                        is PairingResult.Ok -> "accepted"
                                        is PairingResult.Refused -> when (result.reason) {
                                            "already accepted" -> "already"
                                            "already paired" -> "paired"
                                            "identity changed" -> "key_changed"
                                            else -> "expired"
                                        }
                                    }
                                }.getOrDefault("storage_error")
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(t("قبول الدعوة", "Accept invitation")) }
                        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                            Text(t("رفض", "Decline"))
                        }
                    }
                }

                "accepted" -> {
                    Text(
                        t("تم القبول ✅", "Accepted ✅"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        // صدق المرحلة: مفيش قناة مشفرة شغالة لسه — ده تسجيل محلي.
                        t(
                            "اتسجل الإقران على جهازك. المحادثة المشفرة بين الجهازين بتتفعّل مع خدمة وصال المباشر — قيد التطوير، ولسه ما بنوصفهاش بالمشفرة قبل ما تتبني وتتراجع.",
                            "Pairing is recorded on your device. Encrypted chat between devices activates with the Wisal Direct service — in development, and we won't call it encrypted before it's built and reviewed.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                    )
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("تمام", "Done")) }
                }

                "already" -> {
                    Text(
                        t("الدعوة دي اتقبلت قبل كده على الجهاز ده.", "This invitation was already accepted on this device."),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("تمام", "Done")) }
                }

                "storage_error", "key_changed", "paired" -> {
                    Text(
                        when (phase) {
                            "key_changed" -> t(
                                "مفتاح الجهاز مختلف عن الإقران المحفوظ. لم يتم استبداله. تحقق من الطرف الآخر قبل إعادة الإقران.",
                                "This device key differs from the saved pairing. It was not replaced. Verify the other person before pairing again.",
                            )
                            "paired" -> t("الجهاز ده مقترن بالفعل.", "This device is already paired.")
                            else -> t(
                                "تعذّر تأكيد حفظ الإقران. لا تمسح بيانات التطبيق؛ أعد تشغيل وصال وحاول مرة أخرى.",
                                "Pairing could not be confirmed saved. Keep app data intact; restart Wisal and try again.",
                            )
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("إغلاق", "Close")) }
                }

                else -> {
                    Text(
                        t("الدعوة انتهت صلاحيتها أو مش صالحة. اطلب دعوة جديدة.", "This invitation is expired or invalid. Ask for a new one."),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(t("إغلاق", "Close")) }
                }
            }
        }
    }
}
