package com.telecall.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.telecall.app.UiState

/**
 * One tap → the next batch (up to 100) of the admin's recruitment contact
 * list is texted from this phone, one message every ~1.5 s, with progress.
 * The list, message and daily cap live on the server (backend/46_recruit_sms.sql);
 * the agent never sees the numbers. SEND_SMS is requested here, on the first
 * tap, and nowhere else.
 */
@Composable
fun RecruitSmsDialog(
    state: UiState,
    onClose: () -> Unit,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    // The batch only runs while the app is alive, so keep the screen on during it.
    DisposableEffect(state.recruitSending) {
        view.keepScreenOn = state.recruitSending
        onDispose { view.keepScreenOn = false }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onStart() }

    val info = state.recruitInfo
    val canSend = info != null && info.enabled && info.remainingToday > 0 &&
        info.pendingTotal > 0 && !state.recruitSending

    AlertDialog(
        onDismissRequest = { if (!state.recruitSending) onClose() },
        title = { Text("Send job message") },
        text = {
            Column {
                when {
                    state.recruitLoading -> Text("Loading…")
                    info == null -> Text(state.recruitMessage ?: "Could not load the recruitment settings.")
                    !info.enabled -> Text("Recruitment messages are switched off by the admin.")
                    else -> {
                        Text("Message:", style = MaterialTheme.typography.labelMedium)
                        Text(info.message)
                        Spacer(Modifier.height(10.dp))
                        Text("You can send ${info.remainingToday} more today. ${info.pendingTotal} contacts are waiting.")
                    }
                }
                if (state.recruitSending || state.recruitTotal > 0) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (state.recruitTotal == 0) 0f else state.recruitDone.toFloat() / state.recruitTotal },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("${state.recruitDone} of ${state.recruitTotal} done — ${state.recruitSentOk} sent")
                    if (state.recruitSending) {
                        Text("Keep the app open until this finishes.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!state.recruitMessage.isNullOrBlank() && info != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.recruitMessage, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSend,
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) onStart() else permissionLauncher.launch(Manifest.permission.SEND_SMS)
                }
            ) { Text(if (state.recruitSending) "Sending…" else "Send to next batch") }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !state.recruitSending) { Text("Close") }
        }
    )
}
