package com.wifeassistant

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ReviewForm(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
internal fun ReviewHeading(@StringRes title: Int, @StringRes description: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            stringResource(title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ReviewEnrollment(busy: Boolean, onAction: (DirectReviewAction) -> Unit) {
    var pin by remember { mutableStateOf("") }
    ReviewForm {
        ReviewHeading(R.string.direct_review_register, R.string.direct_review_register_body)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.direct_review_relay),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReviewTechnicalText(DirectReviewSession.RELAY_ORIGIN)
        }
        ReviewTechnicalField(pin, { pin = it.take(60) }, R.string.direct_review_pin, busy)
        Button(
            onClick = { onAction(DirectReviewAction.Register(pin)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && pin.isNotBlank(),
        ) { Text(stringResource(R.string.direct_review_register)) }
    }
}

@Composable
internal fun ReviewPairing(
    view: DirectReviewView,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onAction: (DirectReviewAction) -> Unit,
) {
    var peerCode by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    ReviewForm {
        ReviewHeading(R.string.direct_review_pair, R.string.direct_review_pair_body)
        ReviewIdentity(view, snackbar)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReviewTechnicalField(peerCode, { peerCode = it.take(4096) }, R.string.direct_review_peer_code, busy)
        ReviewTechnicalField(
            fingerprint, { fingerprint = it.take(80) }, R.string.direct_review_peer_fingerprint, busy,
            multiline = true,
        )
        Button(
            onClick = { onAction(DirectReviewAction.Pair(peerCode.trim(), fingerprint)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && peerCode.isNotBlank() && fingerprint.isNotBlank(),
        ) { Text(stringResource(R.string.direct_review_verify)) }
    }
}

@Composable
internal fun ReviewDeviceDetails(
    view: DirectReviewView,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onAction: (DirectReviewAction) -> Unit,
) {
    var confirmRevoke by remember { mutableStateOf(false) }
    ReviewForm {
        Text(
            stringResource(R.string.direct_review_device_details),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ReviewIdentity(view, snackbar)
        view.peerFingerprint?.let {
            Text(stringResource(R.string.direct_review_peer_fingerprint), style = MaterialTheme.typography.titleSmall)
            ReviewFingerprint(it)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedButton(
            onClick = { confirmRevoke = true },
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.direct_review_revoke)) }
    }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(R.string.direct_review_revoke_question)) },
            text = { Text(stringResource(R.string.direct_review_revoke_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = false
                        onAction(DirectReviewAction.Revoke)
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.direct_review_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ReviewIdentity(view: DirectReviewView, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.direct_review_copied)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.direct_review_my_fingerprint), style = MaterialTheme.typography.titleSmall)
        ReviewFingerprint(view.fingerprint)
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(view.code))
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(copied)
            }
        }) { Text(stringResource(R.string.direct_review_copy_code)) }
    }
}

@Composable
private fun ReviewFingerprint(fingerprint: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            ReviewTechnicalText(
                fingerprint.chunked(8).chunked(2).joinToString("\n") { it.joinToString("  ") }
            )
        }
    }
}

@Composable
internal fun ReviewTechnicalText(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            textDirection = TextDirection.Ltr,
        ),
    )
}

@Composable
private fun ReviewTechnicalField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    busy: Boolean,
    multiline: Boolean = false,
) {
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            textDirection = TextDirection.Ltr,
        ),
        shape = MaterialTheme.shapes.small,
        enabled = !busy,
        singleLine = !multiline,
        maxLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
}
