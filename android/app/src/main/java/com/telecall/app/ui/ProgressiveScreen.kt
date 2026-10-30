package com.telecall.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.telecall.app.call.ProgressiveSession
import com.telecall.app.call.ProgressiveSession.Phase
import com.telecall.app.call.SimOption
import com.telecall.app.data.CallStatus
import com.telecall.app.data.Lead
import com.telecall.app.data.LeadQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Progressive calling: the app dials leads back to back (see
 * [ProgressiveSession]); finished calls collect in a list to save later.
 * Calling is the main thing on this screen — the save list sits below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveScreen(
    ui: ProgressiveSession.Ui,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onSkip: () -> Unit,
    onRefreshSims: () -> Unit,
    onChooseSim: (SimOption) -> Unit,
    onDismissSimPicker: () -> Unit,
    onOpenSave: (ProgressiveSession.Pending) -> Unit,
    onCloseSave: () -> Unit,
    onStatus: (CallStatus) -> Unit,
    onQuality: (LeadQuality) -> Unit,
    onRemarks: (String) -> Unit,
    onCallbackAt: (Long) -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    // The screen has to stay lit and in front for calls to keep being placed.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val callPerms = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshSims()
        onStart()
    }

    fun startOrAsk() {
        val allGranted = callPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) onStart() else permissionLauncher.launch(callPerms)
    }

    // Same as normal mode: asked right when a Call Later is saved, and saving proceeds either way.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onSave() }

    fun saveOutcome() {
        if (ui.formStatus == CallStatus.CALL_LATER &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onSave()
        }
    }

    val running = ui.phase == Phase.ON_CALL || ui.phase == Phase.COUNTDOWN || ui.phase == Phase.CLAIMING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progressive calling", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ---------- Round progress ----------
            Text(
                text = "Calls this round: ${ui.dialsThisRound.coerceAtMost(ui.roundSize)} of ${ui.roundSize}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { ui.dialsThisRound.coerceAtMost(ui.roundSize).toFloat() / ui.roundSize },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            // ---------- What is happening now ----------
            StatusCard(ui = ui, onStart = { startOrAsk() }, onPause = onPause, onSkip = onSkip, running = running)

            // ---------- The lead being called ----------
            val current = ui.current
            if (current != null && ui.phase in setOf(Phase.COUNTDOWN, Phase.ON_CALL)) {
                Spacer(Modifier.height(16.dp))
                CurrentLeadCard(current)
            }

            // ---------- Calls waiting for their outcome ----------
            Spacer(Modifier.height(24.dp))
            SectionLabel("To save (${ui.pending.size})")
            Spacer(Modifier.height(8.dp))
            if (ui.pending.isEmpty()) {
                Text(
                    text = "Finished calls appear here. Save each one's outcome any time — " +
                        "after ${ui.roundSize} calls it is required before more are dialled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ui.pending.forEach { item ->
                    PendingRow(item = item, onSave = { onOpenSave(item) })
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (ui.askSim && ui.phase != Phase.ON_CALL) {
        SimPickerDialog(sims = ui.sims, onPick = onChooseSim, onDismiss = onDismissSimPicker)
    }

    val editing = ui.editing
    if (editing != null) {
        SaveDialog(
            ui = ui,
            item = editing,
            onClose = onCloseSave,
            onStatus = onStatus,
            onQuality = onQuality,
            onRemarks = onRemarks,
            onCallbackAt = onCallbackAt,
            onSave = { saveOutcome() }
        )
    }
}

@Composable
private fun StatusCard(
    ui: ProgressiveSession.Ui,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onSkip: () -> Unit,
    running: Boolean
) {
    val status: Pair<String, String?> = when (ui.phase) {
        Phase.IDLE -> "Ready" to "Calls are placed one after another. Save the outcomes from the list below; " +
            "after ${ui.roundSize} calls you must save them all before more are dialled."
        Phase.CLAIMING -> "Finding the next lead…" to null
        Phase.COUNTDOWN -> "Calling in ${ui.countdown}…" to "Press Pause or Skip to stop this call from starting."
        Phase.ON_CALL ->
            (if (ui.pausing) "On a call — pausing after it" else "On a call") to
                "The next call starts ${ProgressiveSession.COUNTDOWN_SECS} seconds after this one ends."
        Phase.SAVE_REQUIRED -> "Save your outcomes to continue" to
            "${ui.roundSize} calls are done. Save all ${ui.pending.size} below and dialling carries on by itself."
        Phase.PAUSED -> "Paused" to "Nothing will be dialled until you press Start."
        Phase.WAITING_FOREGROUND -> "Waiting for the app" to "Open this screen to carry on dialling."
        Phase.NO_LEADS -> "No more leads" to (ui.message ?: "No more leads in queue. Contact manager.")
        Phase.STOPPED -> "Stopped" to ui.message
    }
    val headline = status.first
    val detail = status.second
    val warn = ui.phase == Phase.SAVE_REQUIRED || ui.phase == Phase.STOPPED || ui.phase == Phase.NO_LEADS

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.phase == Phase.CLAIMING || ui.phase == Phase.ON_CALL) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                }
                Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (detail != null) {
                Spacer(Modifier.height(6.dp))
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
            // A message set while a call runs (e.g. "Skipped a lead with no valid number.").
            if (ui.message != null && ui.phase in setOf(Phase.ON_CALL, Phase.COUNTDOWN, Phase.CLAIMING)) {
                Spacer(Modifier.height(6.dp))
                Text(ui.message, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    ui.phase == Phase.COUNTDOWN -> {
                        Button(onClick = onPause) { Text("Pause") }
                        OutlinedButton(onClick = onSkip) { Text("Skip lead") }
                    }
                    running && !ui.pausing -> Button(onClick = onPause) { Text("Pause after this call") }
                    running -> Unit
                    ui.phase == Phase.SAVE_REQUIRED || ui.phase == Phase.WAITING_FOREGROUND -> Unit
                    else -> Button(onClick = onStart) {
                        Text(if (ui.phase == Phase.PAUSED || ui.phase == Phase.STOPPED) "Start again" else "Start")
                    }
                }
            }
        }
    }
}

/** The customer on the line — what the agent needs to see while talking. No mobile number is shown. */
@Composable
private fun CurrentLeadCard(lead: Lead) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Field("Name", lead.name)
            Field("Company", lead.company ?: "—")
            Field("Annual Income Range", lead.annualIncomeRange ?: "—")
            Field("ICI Cr Lmt", maskAmount(lead.iciCrLmt), emphasise = true)
            if (lead.attempts > 0) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    text = "Previous attempts: ${lead.attempts}" +
                        (lead.status?.let { " · last outcome ${it.label}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!lead.lastRemarks.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Last remark: ${lead.lastRemarks}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingRow(item: ProgressiveSession.Pending, onSave: () -> Unit) {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(item.attemptedAt))
    val talk = when (val t = item.talkSecs) {
        null -> "talk time: checking…"
        0 -> "no talk time — not answered?"
        else -> "talk ${formatSecs(t)}"
    }
    val wait = item.dwellRemainingSecs
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.lead.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "called $time · $talk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onSave) { Text(if (wait > 0) "Save ($wait s)" else "Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveDialog(
    ui: ProgressiveSession.Ui,
    item: ProgressiveSession.Pending,
    onClose: () -> Unit,
    onStatus: (CallStatus) -> Unit,
    onQuality: (LeadQuality) -> Unit,
    onRemarks: (String) -> Unit,
    onCallbackAt: (Long) -> Unit,
    onSave: () -> Unit
) {
    val wait = item.dwellRemainingSecs
    AlertDialog(
        onDismissRequest = { if (!ui.saving) onClose() },
        title = { Text(item.lead.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val talk = ui.editing?.talkSecs ?: item.talkSecs
                Text(
                    text = when (talk) {
                        null -> "Phone's talk time: still checking"
                        0 -> "Phone's talk time: none — the call was not answered"
                        else -> "Phone's talk time: ${formatSecs(talk)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (talk == 0 && ui.formStatus != null &&
                    ui.formStatus !in setOf(CallStatus.NO_ANSWER, CallStatus.SWITCHED_OFF, CallStatus.WRONG_NUMBER)
                ) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "The phone shows no talk time for this call. Check the customer really answered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(12.dp))

                SectionLabel("Call status")
                Spacer(Modifier.height(8.dp))
                CallStatus.entries.forEach { status ->
                    StatusRow(status = status, selected = ui.formStatus == status, onClick = { onStatus(status) })
                    Spacer(Modifier.height(6.dp))
                }

                if (ui.needsQuality) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Lead strength")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LeadQuality.entries.forEach { q ->
                            FilterChip(
                                selected = ui.formQuality == q,
                                onClick = { onQuality(q) },
                                label = { Text(q.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }

                if (ui.needsCallback) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Call back at")
                    Spacer(Modifier.height(8.dp))
                    CallbackPicker(currentMillis = ui.formCallbackAt, onPicked = onCallbackAt)
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("Remarks")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.formRemarks,
                    onValueChange = onRemarks,
                    placeholder = { Text("Anything worth recording about this call") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                if (ui.formError != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(ui.formError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (wait > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "You can save in $wait s.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = ui.canSaveForm) {
                if (ui.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Save outcome")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !ui.saving) { Text("Cancel") }
        }
    )
}

private fun formatSecs(secs: Int): String =
    if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
