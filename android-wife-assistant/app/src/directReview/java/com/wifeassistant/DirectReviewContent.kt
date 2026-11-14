package com.wifeassistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DirectReviewContent(
    stage: DirectReviewStage,
    busy: Boolean,
    error: DirectReviewError?,
    draft: String,
    onDraftChange: (String) -> Unit,
    snackbar: SnackbarHostState,
    onAction: (DirectReviewAction) -> Unit,
) {
    val view = (stage as? DirectReviewStage.Registered)?.view
    var details by remember(view != null) { mutableStateOf(false) }
    BackHandler(enabled = details) { details = false }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ReviewHeader(
                onOpenDetails = if (view != null && !details) { { details = true } } else null,
                onBack = if (details) { { details = false } } else null,
                busy = busy || stage == DirectReviewStage.Loading,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                if (error != null && stage != DirectReviewStage.Unavailable &&
                    (view == null || details == error.inDetails)
                ) {
                    Text(
                        stringResource(error.message),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when (stage) {
                    DirectReviewStage.Loading -> Unit
                    DirectReviewStage.Enrollment -> ReviewEnrollment(busy, onAction)
                    DirectReviewStage.Revoked -> ReviewForm {
                        ReviewHeading(R.string.direct_review_revoked, R.string.direct_review_revoked_body)
                    }
                    DirectReviewStage.Unavailable -> ReviewForm {
                        ReviewHeading(R.string.direct_review_unavailable, R.string.direct_review_unavailable_body)
                        Button(onClick = { onAction(DirectReviewAction.Retry) }) {
                            Text(stringResource(R.string.direct_review_retry))
                        }
                    }
                    is DirectReviewStage.Registered -> when {
                        details -> ReviewDeviceDetails(stage.view, busy, snackbar, onAction)
                        stage.view.peerFingerprint == null -> ReviewPairing(stage.view, busy, snackbar, onAction)
                        else -> ReviewMessages(stage.view, busy, draft, onDraftChange, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewHeader(onOpenDetails: (() -> Unit)?, onBack: (() -> Unit)?, busy: Boolean) {
    val working = stringResource(R.string.direct_review_working)
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.direct_review_back))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.direct_review_app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.direct_review_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onOpenDetails != null) {
                    IconButton(onClick = onOpenDetails) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.direct_review_device_details))
                    }
                }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = working })
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
