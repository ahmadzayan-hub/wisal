package com.wifeassistant.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wifeassistant.data.DraftStage
import com.wifeassistant.data.t

/** Read-only system preference. Compose's own clock scales non-zero animation durations. */
@Composable
internal fun rememberMotionAllowed(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun read(): Boolean = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
            ValueAnimator.areAnimatorsEnabled()
    }.getOrDefault(false) // Unknown preference: choose the quiet, static path.
    var allowed by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { allowed = read() }
        }
        val observing = runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
            true
        }.getOrDefault(false)
        if (!observing) allowed = false
        val resume = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = observing && read() }
        lifecycle.addObserver(resume)
        onDispose { if (observing) resolver.unregisterContentObserver(observer); lifecycle.removeObserver(resume) }
    }
    return allowed
}

/** One finite entrance. No exit trail containing stale text/actions; no remount on draft edits. */
@Composable
internal fun WisalReveal(motionAllowed: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val visible = remember { MutableTransitionState(!motionAllowed).apply { targetState = true } }
    val distance = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedVisibility(visibleState = visible, modifier = modifier,
        enter = if (motionAllowed) fadeIn(tween(180)) + slideInVertically(tween(180)) { distance } else EnterTransition.None, exit = ExitTransition.None) { content() }
}

/** Hidden options are removed immediately, including their accessibility nodes. */
@Composable
internal fun WisalDisclosure(visible: Boolean, motionAllowed: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (!visible) return
    val transition = remember { MutableTransitionState(!motionAllowed).apply { targetState = true } }
    AnimatedVisibility(visibleState = transition,
        enter = if (motionAllowed) fadeIn(tween(160)) + expandVertically(tween(180)) else EnterTransition.None, exit = ExitTransition.None) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Labels come from real execution callbacks. No percentage, guessed duration or simulated stages. */
@Composable
internal fun DraftProgress(stage: DraftStage?, motionAllowed: Boolean, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(24.dp))
                // The cancel control is outside the changing content and keeps its focus identity.
                Box(Modifier.weight(1f)) {
                    key(stage) {
                        WisalReveal(motionAllowed) {
                            Text(when (stage) {
                                DraftStage.CLARIFY -> t("توضيح الهدف", "Clarifying intent")
                                DraftStage.WRITE -> t("كتابة الصياغتين", "Writing drafts")
                                DraftStage.REVIEW -> t("مراجعة المعنى والخصوصية", "Reviewing meaning and privacy")
                                DraftStage.REPAIR -> t("محاولة إصلاح واحدة", "One repair attempt")
                                DraftStage.PREVIEW -> t("تجهيز المعاينة", "Preparing preview")
                                null -> t("تجهيز الصياغتين", "Preparing two drafts")
                            }, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("draft-stage").semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel-draft")) { Text(t("إلغاء", "Cancel")) }
            }
            Text(t("خذ وقتك. يمكنك التوقف والعودة إلى فكرتك.", "Take your time. You can stop and return to your idea."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Success is set only after the clipboard callback returns; opening Share never invokes this. */
@Composable
internal fun CopyDraftButton(text: String, enabled: Boolean, motionAllowed: Boolean, modifier: Modifier = Modifier, onCopy: () -> Unit) {
    var copied by remember(text) { mutableStateOf(false) }
    var failed by remember(text) { mutableStateOf(false) }
    val status = when { copied -> t("تم النسخ إلى الحافظة", "Copied to clipboard"); failed -> t("لم نتمكن من تأكيد النسخ", "Copy could not be confirmed"); else -> "" }
    FilledTonalButton(onClick = {
        copied = false; failed = false
        try { onCopy(); copied = true } catch (_: Exception) { failed = true }
    }, enabled = enabled && text.isNotBlank(), modifier = modifier.heightIn(min = 48.dp).semantics {
        if (status.isNotEmpty()) { stateDescription = status; liveRegion = LiveRegionMode.Polite }
    }) {
        if (copied) WisalReveal(motionAllowed) { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 3.dp)) }
        Text(when { copied -> t("تم النسخ", "Copied"); failed -> t("إعادة النسخ", "Retry copy"); else -> t("نسخ", "Copy") })
    }
}
