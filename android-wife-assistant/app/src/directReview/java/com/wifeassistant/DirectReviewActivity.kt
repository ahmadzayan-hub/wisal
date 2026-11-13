package com.wifeassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wifeassistant.ui.theme.WifeAssistantTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DirectReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = DirectReviewSession(applicationContext)
        setContent { WifeAssistantTheme { DirectReviewScreen(session) } }
    }
}

@Composable
private fun DirectReviewScreen(session: DirectReviewSession) {
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<DirectReviewView?>(null) }
    var relayPin by remember { mutableStateOf("") }
    var peerCode by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        try {
            view = withContext(Dispatchers.IO) { session.resume() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "Local Direct state could not be opened."
        }
    }

    fun run(success: String, operation: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                view = withContext(Dispatchers.IO) {
                    operation()
                    session.view()
                }
                status = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                view = runCatching { withContext(Dispatchers.IO) { session.view() } }.getOrNull()
                status = "Operation failed. Check the connection, verified pairing and device storage."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Wisal Direct Review", style = MaterialTheme.typography.headlineSmall)
        Text("Synthetic test messages only. Release acceptance is on hold.")
        if (view == null) {
            OutlinedTextField(relayPin, { relayPin = it.take(60) }, label = { Text("Test relay SHA-256 pin") },
                modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
            Button(enabled = !busy && relayPin.isNotBlank(), onClick = {
                val pin = relayPin
                run("Device registered") { session.register(pin) }
            }) { Text("Register test device") }
        }
        view?.let { current ->
            Text("Compare fingerprints on the two devices before pairing.")
            SelectionContainer { Text(current.fingerprint.chunked(8).joinToString(" ")) }
            SelectionContainer { Text(current.code) }
            current.peerFingerprint?.let { Text("Verified peer ${it.take(16)}") }
            Text("Queued messages ${current.queued}")
            if (current.expired > 0) {
                Text("Expired messages ${current.expired}")
                Button(enabled = !busy, onClick = { run("Expired messages removed", session::discardExpired) }) {
                    Text("Discard expired messages")
                }
            }
            if (current.canRestartInitialSession) {
                Button(enabled = !busy, onClick = { run("Ready for a new session", session::restartInitialSession) }) {
                    Text("Start a new session")
                }
            }
            current.received.forEach { Text(it) }
        }
        OutlinedTextField(peerCode, { peerCode = it.take(4096) }, label = { Text("Peer pairing code") },
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(fingerprint, { fingerprint = it.take(80) }, label = { Text("Fingerprint read from peer device") },
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(enabled = !busy && view != null && peerCode.isNotBlank() && fingerprint.isNotBlank(), onClick = {
            val code = peerCode.trim()
            val observed = fingerprint
            run("Peer verified") { session.confirmPeer(DirectReviewPeer.fromCode(code), observed) }
        }) { Text("Verify and pair") }
        OutlinedTextField(draft, { draft = it.take(1024) }, label = { Text("Test message") },
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(enabled = !busy && view?.peerFingerprint != null && draft.isNotBlank(), onClick = {
            val message = draft
            run("Message queued") {
                session.send(message)
                withContext(Dispatchers.Main) { draft = "" }
            }
        }) { Text("Queue message") }
        Button(enabled = !busy && view?.peerFingerprint != null, onClick = { run("Sync completed", session::sync) }) {
            Text("Sync with relay")
        }
        Button(enabled = !busy && view != null, onClick = { run("Device revoked", session::revoke) }) {
            Text("Revoke this test device")
        }
        if (status.isNotEmpty()) Text(status)
    }
}
