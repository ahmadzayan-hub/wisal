package com.wifeassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ReviewMessages(
    view: DirectReviewView,
    busy: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAction: (DirectReviewAction) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.direct_review_messages),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.direct_review_verified_peer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (view.expired > 0) {
                item {
                    Text(
                        pluralStringResource(R.plurals.direct_review_expired, view.expired, view.expired),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { onAction(DirectReviewAction.DiscardExpired) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.direct_review_discard_expired)) }
                }
            }
            if (view.canRestartInitialSession) {
                item {
                    OutlinedButton(
                        onClick = { onAction(DirectReviewAction.RestartSession) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.direct_review_restart_session)) }
                }
            }
            if (view.received.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.direct_review_no_messages),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(view.received) { message ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.widthIn(max = 400.dp),
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                draft,
                { onDraftChange(it.take(1024)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.direct_review_test_message)) },
                shape = MaterialTheme.shapes.small,
                maxLines = 3,
                enabled = !busy,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.direct_review_pending, view.queued, view.queued),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onAction(DirectReviewAction.Queue) },
                    enabled = !busy && draft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.direct_review_queue_message))
                }
            }
            Button(
                onClick = { onAction(DirectReviewAction.Sync) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !busy,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Sync, contentDescription = null)
                    Text(stringResource(R.string.direct_review_sync))
                }
            }
        }
    }
}
