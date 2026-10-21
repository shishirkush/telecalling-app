package com.telecall.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.telecall.app.recording.AudioPlayer
import com.telecall.app.recording.AudioRecorder
import com.telecall.app.recording.Recording
import com.telecall.app.recording.RecordingStore
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A recording that has been stopped but not yet labelled. */
private data class Unlabelled(val id: String, val file: File, val durationMs: Long)

private const val MAX_RECORDING_MS = 30 * 60 * 1000L
private const val MAX_LABEL_LENGTH = 60

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun defaultLabel(now: Long): String =
    "Recording " + SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(now))

/**
 * "T" — Training material. A practice recorder for new agents: tap Record,
 * speak (a pitch, an objection reply), tap Stop, then give the recording a
 * label. Recordings are stored on this phone (see [RecordingStore]) and can be
 * played back, renamed or deleted from the list below. Nothing is uploaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val store = remember { RecordingStore(context) }
    val recorder = remember { AudioRecorder(context) }
    val player = remember { AudioPlayer() }

    var recordings by remember { mutableStateOf(store.list()) }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var currentTarget by remember { mutableStateOf<Pair<String, File>?>(null) }
    var unlabelled by remember { mutableStateOf<Unlabelled?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var playingId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Recording?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Recording?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun startRecording() {
        player.stop()
        playingId = null
        val target = store.newTarget()
        val error = recorder.start(target.second)
        if (error != null) {
            toast(error)
        } else {
            currentTarget = target
            elapsedMs = 0L
            recording = true
        }
    }

    fun stopRecording() {
        val target = currentTarget
        val length = recorder.stop()
        recording = false
        currentTarget = null
        if (target == null) return
        if (length == null) {
            store.discard(target.second)
            toast("That recording was too short — nothing was saved.")
        } else {
            labelInput = ""
            unlabelled = Unlabelled(target.first, target.second, length)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("Microphone permission is needed to record.")
    }

    // The timer, and an automatic stop so one forgotten recording can't fill the phone.
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val t0 = SystemClock.elapsedRealtime()
        while (recording) {
            elapsedMs = SystemClock.elapsedRealtime() - t0
            if (elapsedMs >= MAX_RECORDING_MS) {
                stopRecording()
                toast("Stopped at 30 minutes.")
                break
            }
            delay(200)
        }
    }

    // Keep the screen on while recording: the microphone is only reliable
    // while the app is in the foreground.
    DisposableEffect(recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            player.stop()
        }
    }

    // Leaving mid-recording would lose it — make the agent stop it first.
    BackHandler {
        if (recording || unlabelled != null) toast("Stop and label the recording first.") else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Training material") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (recording || unlabelled != null) toast("Stop and label the recording first.") else onBack()
                    }) {
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Practise your pitch: record yourself, label it, and play it back any time. Recordings stay on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
            )
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatClock(if (recording) elapsedMs else 0L),
                        style = MaterialTheme.typography.displaySmall
                    )
                    Text(
                        text = if (recording) "Recording… speak now" else "Tap Record, then speak",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    if (recording) {
                        Button(
                            onClick = { stopRecording() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text("Stop")
                        }
                    } else {
                        Button(
                            enabled = unlabelled == null,
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (granted) startRecording() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text("Record")
                        }
                    }
                }
            }

            if (recordings.isEmpty()) {
                Text(
                    "No practice recordings yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(recordings, key = { it.id }) { rec ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            IconButton(
                                enabled = !recording,
                                onClick = {
                                    if (playingId == rec.id) {
                                        player.stop()
                                        playingId = null
                                    } else {
                                        val started = player.play(store.fileFor(rec)) { playingId = null }
                                        if (started) playingId = rec.id else toast("Could not play this recording.")
                                    }
                                }
                            ) {
                                Icon(
                                    if (playingId == rec.id) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playingId == rec.id) "Stop playback" else "Play"
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(rec.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(rec.createdAt)) +
                                        "  ·  " + formatClock(rec.durationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { renameTarget = rec; renameInput = rec.label }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename")
                            }
                            IconButton(onClick = { deleteTarget = rec }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Name the recording that was just stopped. Must be answered — Save or Discard.
    unlabelled?.let { pending ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Name this recording") },
            text = {
                Column {
                    Text("Length ${formatClock(pending.durationMs)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { if (it.length <= MAX_LABEL_LENGTH) labelInput = it },
                        label = { Text("Label") },
                        placeholder = { Text("e.g. Customer objection - fees") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val now = System.currentTimeMillis()
                    store.add(
                        Recording(
                            id = pending.id,
                            label = labelInput.trim().ifBlank { defaultLabel(now) },
                            createdAt = now,
                            durationMs = pending.durationMs,
                            fileName = pending.file.name
                        )
                    )
                    recordings = store.list()
                    unlabelled = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    store.discard(pending.file)
                    unlabelled = null
                }) { Text("Discard") }
            }
        )
    }

    renameTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename recording") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { if (it.length <= MAX_LABEL_LENGTH) renameInput = it },
                    label = { Text("Label") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        store.rename(rec.id, renameInput.trim())
                        recordings = store.list()
                        renameTarget = null
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text("\"${rec.label}\" will be deleted from this phone. This can't be undone.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        if (playingId == rec.id) {
                            player.stop()
                            playingId = null
                        }
                        store.delete(rec.id)
                        recordings = store.list()
                        deleteTarget = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}
