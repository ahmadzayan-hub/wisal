package com.wifeassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wifeassistant.ui.theme.WifeAssistantTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DirectReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = DirectReviewSession(applicationContext)
        enableEdgeToEdge()
        setContent { WifeAssistantTheme { DirectReviewScreen(session) } }
    }
}

internal sealed interface DirectReviewStage {
    data object Loading : DirectReviewStage
    data object Enrollment : DirectReviewStage
    data object Revoked : DirectReviewStage
    data object Unavailable : DirectReviewStage
    data class Registered(val view: DirectReviewView) : DirectReviewStage
}

internal sealed interface DirectReviewAction {
    data class Register(val pin: String) : DirectReviewAction
    data class Pair(val code: String, val fingerprint: String) : DirectReviewAction
    data object Queue : DirectReviewAction
    data object Sync : DirectReviewAction
    data object DiscardExpired : DirectReviewAction
    data object RestartSession : DirectReviewAction
    data object Revoke : DirectReviewAction
    data object Retry : DirectReviewAction
}

internal data class DirectReviewError(@StringRes val message: Int, val inDetails: Boolean)

@Composable
private fun DirectReviewScreen(session: DirectReviewSession) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var stage by remember { mutableStateOf<DirectReviewStage>(DirectReviewStage.Loading) }
    var error by remember { mutableStateOf<DirectReviewError?>(null) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun readStage(read: () -> DirectReviewView?): DirectReviewStage = withContext(Dispatchers.IO) {
        try {
            val view = read()
            when {
                view != null -> DirectReviewStage.Registered(view)
                session.isRevoked() -> DirectReviewStage.Revoked
                else -> DirectReviewStage.Enrollment
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DirectReviewStage.Unavailable
        }
    }

    suspend fun load() {
        stage = DirectReviewStage.Loading
        error = null
        stage = readStage(session::resume)
    }
    LaunchedEffect(session) { load() }

    fun run(
        @StringRes failure: Int,
        @StringRes success: Int? = null,
        inDetails: Boolean = false,
        operation: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        error = null
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = DirectReviewError(failure, inDetails)
            } finally {
                try {
                    stage = readStage(session::view)
                } finally {
                    busy = false
                }
            }
            if (success != null && error == null && stage != DirectReviewStage.Unavailable) {
                snackbar.showSnackbar(context.getString(success))
            }
        }
    }

    DirectReviewContent(stage, busy, error, draft, { draft = it }, snackbar) { action ->
        when (action) {
            is DirectReviewAction.Register -> run(R.string.direct_review_register_failed) {
                session.register(action.pin)
            }
            is DirectReviewAction.Pair -> run(R.string.direct_review_pair_failed) {
                session.confirmPeer(DirectReviewPeer.fromCode(action.code), action.fingerprint)
            }
            DirectReviewAction.Queue -> {
                val message = draft
                run(R.string.direct_review_queue_failed, R.string.direct_review_queued) {
                    session.send(message)
                    withContext(Dispatchers.Main) { draft = "" }
                }
            }
            DirectReviewAction.Sync -> run(
                R.string.direct_review_sync_failed, R.string.direct_review_synced, operation = session::sync,
            )
            DirectReviewAction.DiscardExpired -> run(R.string.direct_review_action_failed) { session.discardExpired() }
            DirectReviewAction.RestartSession -> run(R.string.direct_review_action_failed) { session.restartInitialSession() }
            DirectReviewAction.Revoke -> run(
                R.string.direct_review_action_failed, inDetails = true, operation = session::revoke,
            )
            DirectReviewAction.Retry -> scope.launch { load() }
        }
    }
}
